package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtBinaryFormat
import com.hiczp.minecraft.nbt.NbtDocument

data class RegionChunkNbtFormatConfiguration(
    val maximumDecompressedChunkBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumDecompressedChunkBytes >= 0)
    }
}

/**
 * Composes region compression with named-root NBT while keeping both
 * independently reusable.
 */
class RegionChunkNbtFormat(
    val nbt: NbtBinaryFormat = NbtBinaryFormat,
    val compressionCodecs: RegionCompressionCodecs =
        RegionCompressionCodecs,
    val configuration: RegionChunkNbtFormatConfiguration =
        RegionChunkNbtFormatConfiguration(),
) {
    suspend fun decode(chunk: RegionChunk): NbtDocument {
        val compressed = chunk.payload.compressedBytes
            ?: throw RegionFormatException(
                "External region chunk payload has not been resolved",
            )
        val bytes = compressionCodecs.decompress(
            chunk.compression,
            compressed,
            configuration.maximumDecompressedChunkBytes,
        )
        return nbt.decodeDocumentFromByteArray(bytes)
    }

    suspend fun encode(
        document: NbtDocument,
        compression: RegionCompression = RegionCompression.ZLIB,
        timestamp: Int = 0,
        external: Boolean = false,
    ): RegionChunk {
        val bytes = nbt.encodeDocumentToByteArray(document)
        if (bytes.size > configuration.maximumDecompressedChunkBytes) {
            throw RegionFormatException(
                "NBT chunk size ${bytes.size} exceeds configured limit " +
                        configuration.maximumDecompressedChunkBytes,
            )
        }
        val compressed = compressionCodecs.compress(compression, bytes)
        val payload = if (external) {
            RegionChunkPayload.External(compressed)
        } else {
            RegionChunkPayload.Inline(compressed)
        }
        return RegionChunk(
            compression = compression,
            payload = payload,
            timestamp = timestamp,
        )
    }
}
