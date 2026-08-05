package com.hiczp.minecraft.protocol.transport

class MinecraftFrameCodec(
    val configuration: MinecraftTransportConfiguration =
        MinecraftTransportConfiguration(),
) {
    var compressionThreshold: Int? = null
        private set

    fun configureCompression(threshold: Int?) {
        require(threshold == null || threshold >= 0) {
            "Compression threshold must be non-negative"
        }
        compressionThreshold = threshold
    }

    suspend fun encodeFrame(packetData: ByteArray): ByteArray {
        if (packetData.isEmpty()) {
            throw MinecraftTransportException("Packet data cannot be empty")
        }
        if (packetData.size > configuration.maximumUncompressedPacketSize) {
            throw MinecraftTransportException(
                "Packet data has ${packetData.size} bytes; maximum is ${configuration.maximumUncompressedPacketSize}",
            )
        }

        val body = encodeFrameBody(packetData)
        if (body.size > configuration.maximumFrameSize) {
            throw MinecraftTransportException(
                "Frame body has ${body.size} bytes; maximum is ${configuration.maximumFrameSize}",
            )
        }
        return encodeVarInt(body.size) + body
    }

    suspend fun decodeFrame(frame: ByteArray): ByteArray {
        val cursor = ByteCursor(frame)
        val frameLength = cursor.readVarInt(
            maximumBytes = 3,
            rejectNonMinimal = configuration.rejectNonMinimalVarInts,
        )
        validateFrameLength(frameLength)
        if (cursor.remaining != frameLength) {
            throw MinecraftTransportException(
                "Frame declares $frameLength bytes but contains ${cursor.remaining}",
            )
        }
        return decodeFrameBody(cursor.remainingBytes())
    }

    suspend fun decodeFrameBody(frameBody: ByteArray): ByteArray {
        validateFrameLength(frameBody.size)
        val threshold = compressionThreshold ?: return frameBody
        val cursor = ByteCursor(frameBody)
        val uncompressedLength = cursor.readVarInt(
            rejectNonMinimal = configuration.rejectNonMinimalVarInts,
        )
        if (uncompressedLength == 0) {
            val packetData = cursor.remainingBytes()
            if (packetData.isEmpty()) {
                throw MinecraftTransportException("Packet data cannot be empty")
            }
            if (
                configuration.validateCompressionThreshold &&
                packetData.size >= threshold
            ) {
                throw MinecraftTransportException(
                    "Uncompressed packet has ${packetData.size} bytes, meeting compression threshold $threshold",
                )
            }
            return packetData
        }
        if (
            configuration.validateCompressionThreshold &&
            uncompressedLength < threshold
        ) {
            throw MinecraftTransportException(
                "Compressed packet declares $uncompressedLength bytes, below compression threshold $threshold",
            )
        }
        if (uncompressedLength > configuration.maximumUncompressedPacketSize) {
            throw MinecraftTransportException(
                "Compressed packet declares $uncompressedLength bytes; maximum is ${configuration.maximumUncompressedPacketSize}",
            )
        }
        return Zlib.decompress(cursor.remainingBytes(), uncompressedLength)
    }

    internal fun validateFrameLength(frameLength: Int) {
        if (frameLength !in 1..configuration.maximumFrameSize) {
            throw MinecraftTransportException(
                "Invalid frame length $frameLength; maximum is ${configuration.maximumFrameSize}",
            )
        }
    }

    private suspend fun encodeFrameBody(packetData: ByteArray): ByteArray {
        val threshold = compressionThreshold ?: return packetData
        return if (packetData.size < threshold) {
            byteArrayOf(0) + packetData
        } else {
            encodeVarInt(packetData.size) + Zlib.compress(packetData)
        }
    }
}
