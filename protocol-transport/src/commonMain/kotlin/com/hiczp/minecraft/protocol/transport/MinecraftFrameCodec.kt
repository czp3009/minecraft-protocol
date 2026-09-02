package com.hiczp.minecraft.protocol.transport

import kotlinx.io.*

/**
 * Minecraft packet framing and compression-envelope codec.
 *
 * Streaming methods never close caller-owned sources or sinks. Encoding only
 * stages a compressed frame body when its compressed length must be known
 * before the outer frame prefix can be emitted. Byte-array methods are
 * adapters over the same streaming path.
 */
class MinecraftFrameCodec(
    val minecraftTransportConfiguration: MinecraftTransportConfiguration = MinecraftTransportConfiguration(),
) {
    var compressionThreshold: Int? = null
        private set

    fun configureCompression(threshold: Int?) {
        compressionThreshold = threshold?.takeIf { it >= 0 }
    }

    /** Encodes exactly [packetDataByteCount] bytes from [packetData]. */
    fun encodeFrameToSink(
        packetData: Source,
        packetDataByteCount: Int,
        sink: Sink,
    ): Long {
        validatePacketDataLength(packetDataByteCount)
        val packet = ExactLengthRawSource(
            packetData,
            packetDataByteCount,
            "packet data",
        ).buffered()
        return try {
            val threshold = compressionThreshold
            when {
                threshold == null -> {
                    validateFrameLength(packetDataByteCount)
                    val prefixBytes = sink.writeVarInt(packetDataByteCount)
                    packet.transferTo(sink) + prefixBytes
                }

                packetDataByteCount < threshold -> {
                    val bodyBytes = 1 + packetDataByteCount
                    validateFrameLength(bodyBytes)
                    val prefixBytes = sink.writeVarInt(bodyBytes)
                    sink.writeByte(0)
                    packet.transferTo(sink) + prefixBytes + 1
                }

                else -> encodeCompressedFrame(
                    packet,
                    packetDataByteCount,
                    sink,
                )
            }
        } finally {
            packet.close()
        }
    }

    /** Decodes one length-prefixed frame from [source] into [sink]. */
    fun decodeFrameToSink(source: Source, sink: Sink): Long {
        val frameLength = source.readVarInt(
            maximumBytes = 3,
            rejectNonMinimal = minecraftTransportConfiguration.rejectNonMinimalVarInts,
        ).value
        return decodeFrameBodyToSink(source, frameLength, sink)
    }

    /**
     * Decodes exactly [frameBodyByteCount] bytes from [source] into [sink].
     */
    fun decodeFrameBodyToSink(
        source: Source,
        frameBodyByteCount: Int,
        sink: Sink,
    ): Long {
        validateFrameLength(frameBodyByteCount)
        val body = ExactLengthRawSource(
            source,
            frameBodyByteCount,
            "frame body",
        ).buffered()
        return try {
            val threshold = compressionThreshold
            if (threshold == null) {
                if (
                    frameBodyByteCount >
                    minecraftTransportConfiguration.maximumUncompressedPacketSize
                ) {
                    throw MinecraftTransportException(
                        "Packet data has $frameBodyByteCount bytes; maximum is ${minecraftTransportConfiguration.maximumUncompressedPacketSize}",
                    )
                }
                body.transferTo(sink)
            } else {
                decodeCompressedFrameBody(body, threshold, sink)
            }
        } finally {
            body.close()
        }
    }

    /** In-memory adapter over [encodeFrameToSink]. */
    fun encodeFrame(packetData: ByteArray): ByteArray {
        val source = Buffer().apply { write(packetData) }
        val sink = Buffer()
        encodeFrameToSink(source, packetData.size, sink)
        return sink.readByteArray()
    }

    /** In-memory adapter over [decodeFrameToSink]. */
    fun decodeFrame(frame: ByteArray): ByteArray {
        val source = Buffer().apply { write(frame) }
        val sink = Buffer()
        decodeFrameToSink(source, sink)
        if (!source.exhausted()) {
            throw MinecraftTransportException(
                "Trailing bytes after length-prefixed frame",
            )
        }
        return sink.readByteArray()
    }

    /** In-memory adapter over [decodeFrameBodyToSink]. */
    fun decodeFrameBody(frameBody: ByteArray): ByteArray {
        val source = Buffer().apply { write(frameBody) }
        val sink = Buffer()
        decodeFrameBodyToSink(source, frameBody.size, sink)
        return sink.readByteArray()
    }

    internal fun validateFrameLength(frameLength: Int) {
        if (frameLength !in 1..minecraftTransportConfiguration.maximumFrameSize) {
            throw MinecraftTransportException(
                "Invalid frame length $frameLength; maximum is ${minecraftTransportConfiguration.maximumFrameSize}",
            )
        }
    }

    private fun validatePacketDataLength(packetDataByteCount: Int) {
        if (packetDataByteCount <= 0) {
            throw MinecraftTransportException("Packet data cannot be empty")
        }
        if (
            packetDataByteCount >
            minecraftTransportConfiguration.maximumUncompressedPacketSize
        ) {
            throw MinecraftTransportException(
                "Packet data has $packetDataByteCount bytes; maximum is ${minecraftTransportConfiguration.maximumUncompressedPacketSize}",
            )
        }
    }

    private fun encodeCompressedFrame(
        packetData: Source,
        packetDataByteCount: Int,
        sink: Sink,
    ): Long {
        val body = Buffer()
        MaximumSizeRawSink(
            body,
            minecraftTransportConfiguration.maximumFrameSize,
        ).buffered().use { limitedBody ->
            limitedBody.writeVarInt(packetDataByteCount)
            Zlib.compressToSink(packetData, limitedBody)
        }

        val bodySize = body.size.toInt()
        validateFrameLength(bodySize)
        val prefixBytes = sink.writeVarInt(bodySize)
        body.transferTo(sink)
        return prefixBytes + bodySize.toLong()
    }

    private fun decodeCompressedFrameBody(
        body: Source,
        threshold: Int,
        sink: Sink,
    ): Long {
        val decodedLength = body.readVarInt(
            rejectNonMinimal = minecraftTransportConfiguration.rejectNonMinimalVarInts,
        ).value
        if (decodedLength == 0) {
            val packetBytes = body.transferTo(sink)
            if (packetBytes == 0L) {
                throw MinecraftTransportException(
                    "Packet data cannot be empty",
                )
            }
            return packetBytes
        }
        if (decodedLength < 0) {
            throw MinecraftTransportException(
                "Negative uncompressed packet length $decodedLength",
            )
        }
        if (
            minecraftTransportConfiguration.validateCompressionThreshold &&
            decodedLength < threshold
        ) {
            throw MinecraftTransportException(
                "Compressed packet declares $decodedLength bytes, below compression threshold $threshold",
            )
        }
        if (
            decodedLength >
            minecraftTransportConfiguration.maximumUncompressedPacketSize
        ) {
            throw MinecraftTransportException(
                "Compressed packet declares $decodedLength bytes; maximum is ${minecraftTransportConfiguration.maximumUncompressedPacketSize}",
            )
        }
        return Zlib.decompressToSink(body, sink, decodedLength)
    }
}

// Bound each frame view without closing the shared connection source, and
// report truncation at the transport boundary instead of leaking EOF details.
private class ExactLengthRawSource(
    private val upstream: Source,
    byteCount: Int,
    private val kind: String,
) : RawSource {
    private var remaining = byteCount.toLong()
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "$kind source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (remaining == 0L) return -1
        val read = upstream.readAtMostTo(
            sink,
            minOf(byteCount, remaining),
        )
        if (read < 0) {
            throw MinecraftTransportException(
                "Truncated $kind; $remaining byte(s) missing",
            )
        }
        remaining -= read
        return read
    }

    override fun close() {
        closed = true
    }
}

// Compression must stage a body before its VarInt length can be written. Apply
// the configured ceiling during staging so hostile input cannot overgrow it.
private class MaximumSizeRawSink(
    private val downstream: Sink,
    maximumBytes: Int,
) : RawSink {
    private val maximumBytes = maximumBytes.toLong()
    private var written = 0L

    override fun write(source: Buffer, byteCount: Long) {
        if (byteCount < 0 || byteCount > maximumBytes - written) {
            throw MinecraftTransportException(
                "Frame body exceeds configured limit $maximumBytes",
            )
        }
        downstream.write(source, byteCount)
        written += byteCount
    }

    override fun flush() {
        downstream.flush()
    }

    override fun close() = Unit
}
