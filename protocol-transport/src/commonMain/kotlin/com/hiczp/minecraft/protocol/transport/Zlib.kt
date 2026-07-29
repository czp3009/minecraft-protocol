package com.hiczp.minecraft.protocol.transport

import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

internal object Zlib {
    private const val COMPRESSION_METHOD_DEFLATE = 8
    private const val PRESET_DICTIONARY = 0x20
    private const val MOD_ADLER = 65_521

    suspend fun compress(input: ByteArray): ByteArray {
        val rawDeflate = DeflateEncoder
            .encode(ByteReadChannel(input))
            .readRemaining()
            .readByteArray()
        val output = ByteArray(rawDeflate.size + 6)
        output[0] = 0x78
        output[1] = 0x9C.toByte()
        rawDeflate.copyInto(output, destinationOffset = 2)
        val checksum = adler32(input)
        output[output.lastIndex - 3] = (checksum ushr 24).toByte()
        output[output.lastIndex - 2] = (checksum ushr 16).toByte()
        output[output.lastIndex - 1] = (checksum ushr 8).toByte()
        output[output.lastIndex] = checksum.toByte()
        return output
    }

    suspend fun decompress(
        input: ByteArray,
        expectedSize: Int,
    ): ByteArray {
        if (input.size < 6) {
            throw MinecraftTransportException("Truncated zlib stream")
        }
        val compressionMethodAndInfo = input[0].toInt() and 0xFF
        val flags = input[1].toInt() and 0xFF
        if (compressionMethodAndInfo and 0x0F != COMPRESSION_METHOD_DEFLATE) {
            throw MinecraftTransportException("Unsupported zlib compression method")
        }
        if (compressionMethodAndInfo ushr 4 > 7) {
            throw MinecraftTransportException("Invalid zlib window size")
        }
        if ((compressionMethodAndInfo shl 8 or flags) % 31 != 0) {
            throw MinecraftTransportException("Invalid zlib header checksum")
        }
        if (flags and PRESET_DICTIONARY != 0) {
            throw MinecraftTransportException(
                "Minecraft packets cannot use a preset zlib dictionary",
            )
        }

        val rawDeflate = input.copyOfRange(2, input.size - 4)
        val decoded = DeflateEncoder.decode(ByteReadChannel(rawDeflate))
        val output = try {
            val bytes = decoded.readByteArray(expectedSize)
            val extra = ByteArray(1)
            val count = decoded.readAvailable(extra)
            if (count >= 0) {
                decoded.cancel(
                    MinecraftTransportException(
                        "Compressed packet exceeds its declared size $expectedSize",
                    ),
                )
                throw MinecraftTransportException(
                    "Compressed packet exceeds its declared size $expectedSize",
                )
            }
            bytes
        } catch (failure: MinecraftTransportException) {
            throw failure
        } catch (failure: Throwable) {
            throw MinecraftTransportException("Invalid zlib-compressed packet", failure)
        }

        val expectedChecksum =
            ((input[input.lastIndex - 3].toInt() and 0xFF) shl 24) or
                    ((input[input.lastIndex - 2].toInt() and 0xFF) shl 16) or
                    ((input[input.lastIndex - 1].toInt() and 0xFF) shl 8) or
                    (input[input.lastIndex].toInt() and 0xFF)
        if (adler32(output) != expectedChecksum) {
            throw MinecraftTransportException("Invalid zlib Adler-32 checksum")
        }
        return output
    }

    private fun adler32(bytes: ByteArray): Int {
        var first = 1
        var second = 0
        bytes.forEach { byte ->
            first = (first + (byte.toInt() and 0xFF)) % MOD_ADLER
            second = (second + first) % MOD_ADLER
        }
        return second shl 16 or first
    }
}
