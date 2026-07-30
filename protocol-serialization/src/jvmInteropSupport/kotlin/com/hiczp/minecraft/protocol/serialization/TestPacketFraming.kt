package com.hiczp.minecraft.protocol.serialization

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Test-only Minecraft packet framing and compression.
 *
 * Production currently stops at packet payloads. Keeping this implementation
 * in jvmTest lets the payload codecs be exercised over the real protocol
 * transport without accidentally exposing an incomplete transport API.
 */
internal object TestPacketFraming {
    const val DEFAULT_MAXIMUM_FRAME_SIZE: Int = 2_097_152

    fun writeFrame(
        output: OutputStream,
        packetData: ByteArray,
        compressionThreshold: Int?,
        maximumFrameSize: Int = DEFAULT_MAXIMUM_FRAME_SIZE,
    ) {
        val frameBody = encodeFrameBody(
            packetData,
            compressionThreshold,
            maximumFrameSize,
        )
        writeVarInt(output, frameBody.size)
        output.write(frameBody)
    }

    fun readFrame(
        input: InputStream,
        compressionThreshold: Int?,
        maximumFrameSize: Int = DEFAULT_MAXIMUM_FRAME_SIZE,
    ): ByteArray {
        val frameLength = readVarInt(input)
        require(frameLength in 1..maximumFrameSize) {
            "Invalid frame length $frameLength; maximum is $maximumFrameSize"
        }
        val frameBody = input.readExactly(frameLength)
        return decodeFrameBody(
            frameBody,
            compressionThreshold,
            maximumFrameSize,
        )
    }

    private fun encodeFrameBody(
        packetData: ByteArray,
        compressionThreshold: Int?,
        maximumFrameSize: Int,
    ): ByteArray {
        require(maximumFrameSize > 0) {
            "Maximum frame size must be positive"
        }
        require(packetData.size <= maximumFrameSize) {
            "Packet data has ${packetData.size} bytes; maximum is $maximumFrameSize"
        }
        if (compressionThreshold == null) {
            return packetData
        }
        require(compressionThreshold >= 0) {
            "Compression threshold must be non-negative"
        }

        val body = ByteArrayOutput()
        if (packetData.size < compressionThreshold) {
            body.writeVarInt(0)
            body.write(packetData)
        } else {
            body.writeVarInt(packetData.size)
            body.write(deflate(packetData))
        }
        return body.toByteArray().also {
            require(it.size <= maximumFrameSize) {
                "Compressed frame body has ${it.size} bytes; maximum is $maximumFrameSize"
            }
        }
    }

    private fun decodeFrameBody(
        frameBody: ByteArray,
        compressionThreshold: Int?,
        maximumFrameSize: Int,
    ): ByteArray {
        if (compressionThreshold == null) {
            return frameBody
        }
        require(compressionThreshold >= 0) {
            "Compression threshold must be non-negative"
        }

        val input = ByteArrayInput(frameBody)
        val uncompressedLength = input.readVarInt()
        if (uncompressedLength == 0) {
            val packetData = input.remainingBytes()
            require(packetData.size < compressionThreshold) {
                "Uncompressed packet has ${packetData.size} bytes, meeting compression " +
                        "threshold $compressionThreshold"
            }
            return packetData
        }
        require(uncompressedLength in compressionThreshold..maximumFrameSize) {
            "Invalid declared uncompressed length $uncompressedLength for threshold " +
                    "$compressionThreshold and maximum $maximumFrameSize"
        }
        return inflateExactly(input.remainingBytes(), uncompressedLength)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutput()
            val buffer = ByteArray(512)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0) { "Deflater made no progress" }
                output.write(buffer, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateExactly(
        compressed: ByteArray,
        expectedLength: Int,
    ): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val result = ByteArray(expectedLength)
            var position = 0
            while (position < result.size && !inflater.finished()) {
                val count = try {
                    inflater.inflate(result, position, result.size - position)
                } catch (cause: DataFormatException) {
                    throw IllegalArgumentException("Invalid zlib-compressed packet", cause)
                }
                if (count == 0) {
                    check(!inflater.needsDictionary()) {
                        "Compressed packet unexpectedly requires a dictionary"
                    }
                    check(!inflater.needsInput()) {
                        "Inflater made no progress"
                    }
                    break
                }
                position += count
            }
            require(position == expectedLength && inflater.finished()) {
                "Compressed packet produced $position bytes; expected $expectedLength"
            }
            require(inflater.remaining == 0) {
                "Compressed packet has ${inflater.remaining} trailing byte(s)"
            }
            result
        } finally {
            inflater.end()
        }
    }

    private fun writeVarInt(output: OutputStream, value: Int) {
        var remaining = value
        do {
            var current = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) {
                current = current or 0x80
            }
            output.write(current)
        } while (remaining != 0)
    }

    private fun readVarInt(input: InputStream): Int {
        var result = 0
        var shift = 0
        var count = 0
        while (shift < 35) {
            val current = input.read()
            if (current < 0) {
                throw EOFException("EOF while reading VarInt")
            }
            count++
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                require(count == varIntSize(result)) {
                    "Non-minimal VarInt encoding"
                }
                return result
            }
            shift += 7
        }
        error("VarInt is wider than five bytes")
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var position = 0
        while (position < length) {
            val count = read(result, position, length - position)
            if (count < 0) {
                throw EOFException(
                    "Expected $length frame bytes, received $position",
                )
            }
            if (count == 0) {
                val next = read()
                if (next < 0) {
                    throw EOFException(
                        "Expected $length frame bytes, received $position",
                    )
                }
                result[position++] = next.toByte()
            } else {
                position += count
            }
        }
        return result
    }

    private fun varIntSize(value: Int): Int {
        var remaining = value
        var size = 1
        while (remaining and 0x7F.inv() != 0) {
            size++
            remaining = remaining ushr 7
        }
        return size
    }

    private class ByteArrayOutput {
        private var bytes = ByteArray(32)
        private var size = 0

        fun writeVarInt(value: Int) {
            var remaining = value
            do {
                var current = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) {
                    current = current or 0x80
                }
                write(current)
            } while (remaining != 0)
        }

        fun write(value: ByteArray, length: Int = value.size) {
            require(length in 0..value.size)
            ensure(size + length)
            value.copyInto(
                bytes,
                destinationOffset = size,
                endIndex = length,
            )
            size += length
        }

        private fun write(value: Int) {
            ensure(size + 1)
            bytes[size++] = value.toByte()
        }

        private fun ensure(required: Int) {
            if (required > bytes.size) {
                bytes = bytes.copyOf(maxOf(required, bytes.size * 2))
            }
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    private class ByteArrayInput(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun readVarInt(): Int {
            var result = 0
            var shift = 0
            var count = 0
            while (shift < 35) {
                check(position < bytes.size) { "Truncated VarInt" }
                val current = bytes[position++].toInt() and 0xFF
                count++
                result = result or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) {
                    require(count == varIntSize(result)) {
                        "Non-minimal VarInt encoding"
                    }
                    return result
                }
                shift += 7
            }
            error("VarInt is wider than five bytes")
        }

        fun remainingBytes(): ByteArray = bytes.copyOfRange(position, bytes.size)
    }
}
