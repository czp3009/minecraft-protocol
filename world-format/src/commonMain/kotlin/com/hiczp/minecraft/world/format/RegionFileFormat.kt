package com.hiczp.minecraft.world.format

import kotlinx.io.*

data class RegionFileFormatConfiguration(
    val maximumRegionBytes: Int = 512 * 1_048_576,
    val maximumCompressedChunkBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumRegionBytes >= REGION_HEADER_BYTES)
        require(maximumCompressedChunkBytes >= 0)
    }
}

/** Malformed region/container data or a world-format resource-limit failure. */
class RegionFormatException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Structural codec for the Anvil `.mca` container.
 *
 * Chunk payloads remain compressed. [encodeToSink] and [decodeFromSource]
 * process the region sector-by-sector; array methods are adapters for callers
 * that explicitly want one in-memory file image.
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

    /** Reads one region without closing [source]. */
    fun decodeFromSource(source: Source): RegionFile {
        if (source.exhausted()) return RegionFile()
        return try {
            decodeNonEmpty(source)
        } catch (failure: EOFException) {
            throw RegionFormatException("Truncated region file", failure)
        }
    }

    fun decodeFromByteArray(bytes: ByteArray): RegionFile {
        if (bytes.size > configuration.maximumRegionBytes) {
            throw RegionFormatException(
                "Region file size ${bytes.size} exceeds configured limit ${configuration.maximumRegionBytes}",
            )
        }
        val buffer = Buffer()
        buffer.write(bytes)
        return decodeFromSource(buffer)
    }

    /** Writes one region without closing or flushing [sink]. */
    fun encodeToSink(
        region: RegionFile,
        sink: Sink,
    ): Map<LocalChunkPosition, ByteArray> {
        val plans = plan(region)
        val header = ByteArray(REGION_HEADER_BYTES)
        val externalChunks = linkedMapOf<LocalChunkPosition, ByteArray>()

        plans.forEach { plan ->
            val location =
                (plan.sectorOffset shl 8) or plan.allocatedSectors
            writeInt(header, plan.position.index * Int.SIZE_BYTES, location)
            writeInt(
                header,
                REGION_SECTOR_BYTES +
                        plan.position.index * Int.SIZE_BYTES,
                plan.chunk.timestamp,
            )
        }
        sink.write(header)

        plans.forEach { plan ->
            val recordLength: Int
            if (plan.external) {
                recordLength = 1
                sink.writeInt(recordLength)
                sink.writeByte(
                    (plan.chunk.compression.id or EXTERNAL_STREAM_FLAG)
                        .toByte(),
                )
                externalChunks[plan.position] = plan.compressedBytes
            } else {
                recordLength = plan.compressedBytes.size + 1
                sink.writeInt(recordLength)
                sink.writeByte(plan.chunk.compression.id.toByte())
                sink.write(plan.compressedBytes)
            }
            val padding =
                plan.allocatedSectors * REGION_SECTOR_BYTES -
                        Int.SIZE_BYTES - recordLength
            writeZeroes(sink, padding)
        }
        return externalChunks
    }

    fun encodeToByteArray(region: RegionFile): EncodedRegionFile {
        val buffer = Buffer()
        val externalChunks = encodeToSink(region, buffer)
        return EncodedRegionFile(buffer.readByteArray(), externalChunks)
    }

    /** Resolves the sidecar payloads selected by the same plan as [encodeToSink]. */
    fun externalPayloads(
        region: RegionFile,
    ): Map<LocalChunkPosition, ByteArray> = buildMap {
        plan(region).forEach { chunk ->
            if (chunk.external) put(chunk.position, chunk.compressedBytes)
        }
    }

    private fun decodeNonEmpty(source: Source): RegionFile {
        val header = source.readByteArray(REGION_HEADER_BYTES)
        val maximumSectors =
            configuration.maximumRegionBytes / REGION_SECTOR_BYTES
        val usedSectors = BooleanArray(maximumSectors)
        usedSectors[0] = true
        usedSectors[1] = true
        val plans = ArrayList<DecodeChunkPlan>()

        for (index in 0 until REGION_CHUNK_COUNT) {
            val location = readInt(header, index * Int.SIZE_BYTES)
            if (location == 0) continue
            val sectorOffset = location ushr 8
            val allocatedSectors = location and 0xFF
            val position = LocalChunkPosition.fromIndex(index)
            if (sectorOffset < 2 || allocatedSectors == 0) {
                throw RegionFormatException(
                    "Chunk $position has invalid sector location $sectorOffset+$allocatedSectors",
                )
            }
            if (sectorOffset > maximumSectors - allocatedSectors) {
                throw RegionFormatException(
                    "Chunk $position allocation exceeds configured region limit",
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
            plans += DecodeChunkPlan(
                position = position,
                timestamp = readInt(
                    header,
                    REGION_SECTOR_BYTES + index * Int.SIZE_BYTES,
                ),
                sectorOffset = sectorOffset,
                allocatedSectors = allocatedSectors,
            )
        }

        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        var currentSector = 2
        plans.sortedBy(DecodeChunkPlan::sectorOffset).forEach { plan ->
            val gapSectors = plan.sectorOffset - currentSector
            source.skip(gapSectors.toLong() * REGION_SECTOR_BYTES)

            val length = source.readInt()
            val allocatedPayloadBytes =
                plan.allocatedSectors * REGION_SECTOR_BYTES - Int.SIZE_BYTES
            if (length !in 1..allocatedPayloadBytes) {
                throw RegionFormatException(
                    "Chunk ${plan.position} declares invalid record length $length for ${plan.allocatedSectors} allocated sector(s)",
                )
            }
            val versionByte = source.readByte().toInt() and 0xFF
            val external = versionByte and EXTERNAL_STREAM_FLAG != 0
            val compressionId = versionByte and EXTERNAL_STREAM_FLAG.inv()
            val compression = RegionCompression.fromId(compressionId)
                ?: throw RegionFormatException(
                    "Chunk ${plan.position} uses unknown compression ID $compressionId",
                )
            val compressedLength = length - 1
            if (compressedLength > configuration.maximumCompressedChunkBytes) {
                throw RegionFormatException(
                    "Chunk ${plan.position} compressed size $compressedLength exceeds configured limit ${configuration.maximumCompressedChunkBytes}",
                )
            }
            val payload = if (external) {
                if (compressedLength != 0) {
                    throw RegionFormatException(
                        "External chunk ${plan.position} also contains an inline payload",
                    )
                }
                RegionChunkPayload.External()
            } else {
                RegionChunkPayload.Inline(
                    source.readByteArray(compressedLength),
                )
            }
            val padding = allocatedPayloadBytes - length
            source.skip(padding.toLong())
            chunks[plan.position] = RegionChunk(
                compression = compression,
                payload = payload,
                timestamp = plan.timestamp,
            )
            currentSector = plan.sectorOffset + plan.allocatedSectors
        }

        discardTrailingSectors(source, currentSector.toLong() * REGION_SECTOR_BYTES)
        return RegionFile(chunks)
    }

    private fun discardTrailingSectors(source: Source, consumedBytes: Long) {
        var totalBytes = consumedBytes
        val scratch = ByteArray(8_192)
        while (true) {
            val read = source.readAtMostTo(scratch)
            if (read < 0) return
            if (read == 0) continue
            if (totalBytes > configuration.maximumRegionBytes.toLong() - read) {
                throw RegionFormatException(
                    "Region stream exceeds configured limit ${configuration.maximumRegionBytes}",
                )
            }
            totalBytes += read
        }
    }

    private fun plan(region: RegionFile): List<ChunkPlan> {
        val plans = region.chunks.entries
            .sortedBy { it.key.index }
            .map { (position, chunk) -> plan(position, chunk) }
        var nextSector = 2
        plans.forEach {
            it.sectorOffset = nextSector
            nextSector += it.allocatedSectors
        }
        val totalBytes = nextSector.toLong() * REGION_SECTOR_BYTES
        if (totalBytes > configuration.maximumRegionBytes) {
            throw RegionFormatException(
                "Encoded region size $totalBytes exceeds configured limit ${configuration.maximumRegionBytes}",
            )
        }
        if (nextSector > MAX_SECTOR_OFFSET + 1) {
            throw RegionFormatException(
                "Encoded region exceeds location-table range",
            )
        }
        return plans
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
                "Chunk $position compressed size ${compressedBytes.size} exceeds configured limit ${configuration.maximumCompressedChunkBytes}",
            )
        }
        val inlineBytes = Int.SIZE_BYTES + 1L + compressedBytes.size
        val inlineSectors =
            ((inlineBytes + REGION_SECTOR_BYTES - 1) / REGION_SECTOR_BYTES)
                .toInt()
        val external =
            chunk.payload.isExternal ||
                    inlineSectors >= EXTERNAL_CHUNK_THRESHOLD
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

private data class DecodeChunkPlan(
    val position: LocalChunkPosition,
    val timestamp: Int,
    val sectorOffset: Int,
    val allocatedSectors: Int,
)

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

private fun writeZeroes(sink: Sink, byteCount: Int) {
    var remaining = byteCount
    while (remaining > 0) {
        val count = minOf(remaining, ZEROES.size)
        sink.write(ZEROES, endIndex = count)
        remaining -= count
    }
}

private val ZEROES = ByteArray(8_192)
private const val EXTERNAL_STREAM_FLAG = 0x80
private const val EXTERNAL_CHUNK_THRESHOLD = 256
private const val MAX_SECTOR_OFFSET = 0xFF_FFFF
