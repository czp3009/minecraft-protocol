package com.hiczp.minecraft.world.format

import kotlinx.io.Sink
import kotlinx.io.Source

data class RegionFileFormatConfiguration(
    val maximumRegionBytes: Int = 512 * 1_048_576,
    val maximumCompressedChunkBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumRegionBytes >= REGION_HEADER_BYTES)
        require(maximumCompressedChunkBytes >= 0)
    }
}

class RegionFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Structural codec for the Anvil `.mca` container.
 *
 * Chunk payloads remain compressed. Compression and NBT are deliberately
 * separate layers so callers can inspect or copy a region without inflating
 * every chunk.
 */
sealed class RegionFileFormat(
    val configuration: RegionFileFormatConfiguration,
) {
    companion object Default :
        RegionFileFormat(RegionFileFormatConfiguration()) {
        operator fun invoke(
            configuration: RegionFileFormatConfiguration =
                RegionFileFormatConfiguration(),
        ): RegionFileFormat = ConfiguredRegionFileFormat(configuration)
    }

    fun decode(source: Source): RegionFile =
        decodeFromByteArray(readBounded(source, configuration.maximumRegionBytes))

    fun decodeFromByteArray(bytes: ByteArray): RegionFile {
        if (bytes.isEmpty()) {
            return RegionFile()
        }
        if (bytes.size < REGION_HEADER_BYTES) {
            throw RegionFormatException(
                "Region file is ${bytes.size} bytes; header requires " +
                        "$REGION_HEADER_BYTES bytes",
            )
        }
        if (bytes.size > configuration.maximumRegionBytes) {
            throw RegionFormatException(
                "Region file size ${bytes.size} exceeds configured limit " +
                        configuration.maximumRegionBytes,
            )
        }

        val sectorCount =
            (bytes.size + REGION_SECTOR_BYTES - 1) / REGION_SECTOR_BYTES
        val usedSectors = BooleanArray(sectorCount)
        usedSectors[0] = true
        usedSectors[1] = true
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()

        for (index in 0 until REGION_CHUNK_COUNT) {
            val location = readInt(bytes, index * Int.SIZE_BYTES)
            val timestamp = readInt(
                bytes,
                REGION_SECTOR_BYTES + index * Int.SIZE_BYTES,
            )
            if (location == 0) continue

            val sectorOffset = location ushr 8
            val allocatedSectors = location and 0xFF
            val position = LocalChunkPosition.fromIndex(index)
            if (sectorOffset < 2 || allocatedSectors == 0) {
                throw RegionFormatException(
                    "Chunk $position has invalid sector location " +
                            "$sectorOffset+$allocatedSectors",
                )
            }
            if (sectorOffset > sectorCount - allocatedSectors) {
                throw RegionFormatException(
                    "Chunk $position allocation exceeds region file",
                )
            }
            for (sector in sectorOffset until sectorOffset + allocatedSectors) {
                if (usedSectors[sector]) {
                    throw RegionFormatException(
                        "Chunk $position overlaps sector $sector",
                    )
                }
                usedSectors[sector] = true
            }

            val recordOffset = sectorOffset * REGION_SECTOR_BYTES
            val length = readInt(bytes, recordOffset)
            val allocatedPayloadBytes =
                allocatedSectors * REGION_SECTOR_BYTES - Int.SIZE_BYTES
            if (length !in 1..allocatedPayloadBytes) {
                throw RegionFormatException(
                    "Chunk $position declares invalid record length $length " +
                            "for $allocatedSectors allocated sector(s)",
                )
            }
            if (recordOffset + Int.SIZE_BYTES + length > bytes.size) {
                throw RegionFormatException("Chunk $position record is truncated")
            }

            val versionByte = bytes[recordOffset + Int.SIZE_BYTES].toInt() and 0xFF
            val external = versionByte and EXTERNAL_STREAM_FLAG != 0
            val compressionId = versionByte and EXTERNAL_STREAM_FLAG.inv()
            val compression = RegionCompression.fromId(compressionId)
                ?: throw RegionFormatException(
                    "Chunk $position uses unknown compression ID $compressionId",
                )
            val compressedLength = length - 1
            if (compressedLength > configuration.maximumCompressedChunkBytes) {
                throw RegionFormatException(
                    "Chunk $position compressed size $compressedLength exceeds " +
                            "configured limit " +
                            configuration.maximumCompressedChunkBytes,
                )
            }

            val payload = if (external) {
                if (compressedLength != 0) {
                    throw RegionFormatException(
                        "External chunk $position also contains an inline payload",
                    )
                }
                RegionChunkPayload.External()
            } else {
                RegionChunkPayload.Inline(
                    bytes.copyOfRange(
                        recordOffset + Int.SIZE_BYTES + 1,
                        recordOffset + Int.SIZE_BYTES + length,
                    ),
                )
            }
            chunks[position] = RegionChunk(
                compression = compression,
                payload = payload,
                timestamp = timestamp,
            )
        }
        return RegionFile(chunks)
    }

    fun encode(sink: Sink, region: RegionFile): Map<LocalChunkPosition, ByteArray> {
        val encoded = encodeToByteArray(region)
        sink.write(encoded.bytes)
        return encoded.externalChunks
    }

    fun encodeToByteArray(region: RegionFile): EncodedRegionFile {
        val plans = region.chunks.entries
            .sortedBy { it.key.index }
            .map { (position, chunk) -> plan(position, chunk) }

        var nextSector = 2
        plans.forEach {
            it.sectorOffset = nextSector
            nextSector += it.allocatedSectors
        }
        val totalBytesLong = nextSector.toLong() * REGION_SECTOR_BYTES
        if (totalBytesLong > configuration.maximumRegionBytes) {
            throw RegionFormatException(
                "Encoded region size $totalBytesLong exceeds configured limit " +
                        configuration.maximumRegionBytes,
            )
        }
        if (nextSector > MAX_SECTOR_OFFSET + 1) {
            throw RegionFormatException("Encoded region exceeds location-table range")
        }

        val output = ByteArray(totalBytesLong.toInt())
        val externalChunks = linkedMapOf<LocalChunkPosition, ByteArray>()
        plans.forEach { plan ->
            val position = plan.position
            val chunk = plan.chunk
            val location =
                (plan.sectorOffset shl 8) or plan.allocatedSectors
            writeInt(output, position.index * Int.SIZE_BYTES, location)
            writeInt(
                output,
                REGION_SECTOR_BYTES + position.index * Int.SIZE_BYTES,
                chunk.timestamp,
            )

            val recordOffset = plan.sectorOffset * REGION_SECTOR_BYTES
            if (plan.external) {
                writeInt(output, recordOffset, 1)
                output[recordOffset + Int.SIZE_BYTES] =
                    (chunk.compression.id or EXTERNAL_STREAM_FLAG).toByte()
                externalChunks[position] = plan.compressedBytes
            } else {
                writeInt(output, recordOffset, plan.compressedBytes.size + 1)
                output[recordOffset + Int.SIZE_BYTES] =
                    chunk.compression.id.toByte()
                plan.compressedBytes.copyInto(
                    output,
                    destinationOffset = recordOffset + Int.SIZE_BYTES + 1,
                )
            }
        }
        return EncodedRegionFile(output, externalChunks)
    }

    private fun plan(
        position: LocalChunkPosition,
        chunk: RegionChunk,
    ): ChunkPlan {
        val compressedBytes = chunk.payload.compressedBytes
            ?: throw RegionFormatException(
                "External chunk $position has not been resolved",
            )
        if (compressedBytes.size > configuration.maximumCompressedChunkBytes) {
            throw RegionFormatException(
                "Chunk $position compressed size ${compressedBytes.size} exceeds " +
                        "configured limit " +
                        configuration.maximumCompressedChunkBytes,
            )
        }
        val inlineBytes = Int.SIZE_BYTES + 1L + compressedBytes.size
        val inlineSectors =
            ((inlineBytes + REGION_SECTOR_BYTES - 1) / REGION_SECTOR_BYTES)
                .toInt()
        val external =
            chunk.payload.isExternal || inlineSectors >= EXTERNAL_CHUNK_THRESHOLD
        return ChunkPlan(
            position = position,
            chunk = chunk,
            compressedBytes = compressedBytes,
            external = external,
            allocatedSectors = if (external) 1 else inlineSectors,
        )
    }
}

private class ConfiguredRegionFileFormat(
    configuration: RegionFileFormatConfiguration,
) : RegionFileFormat(configuration)

private class ChunkPlan(
    val position: LocalChunkPosition,
    val chunk: RegionChunk,
    val compressedBytes: ByteArray,
    val external: Boolean,
    val allocatedSectors: Int,
    var sectorOffset: Int = 0,
)

private fun readBounded(source: Source, maximumBytes: Int): ByteArray {
    val output = RegionByteAccumulator()
    val chunk = ByteArray(8_192)
    while (true) {
        val read = source.readAtMostTo(chunk)
        if (read < 0) break
        if (read == 0) continue
        if (output.size > maximumBytes - read) {
            throw RegionFormatException(
                "Region stream exceeds configured limit $maximumBytes",
            )
        }
        output.append(chunk, read)
    }
    return output.toByteArray()
}

private class RegionByteAccumulator {
    private var bytes = ByteArray(8_192)
    var size: Int = 0
        private set

    fun append(source: ByteArray, length: Int) {
        ensureCapacity(size + length)
        source.copyInto(bytes, destinationOffset = size, endIndex = length)
        size += length
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return
        var capacity = bytes.size
        while (capacity < required) {
            capacity = (capacity * 2).coerceAtLeast(required)
        }
        bytes = bytes.copyOf(capacity)
    }
}

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private const val EXTERNAL_STREAM_FLAG = 0x80
private const val EXTERNAL_CHUNK_THRESHOLD = 256
private const val MAX_SECTOR_OFFSET = 0xFF_FFFF
