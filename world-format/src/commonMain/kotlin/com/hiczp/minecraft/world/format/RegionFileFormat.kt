package com.hiczp.minecraft.world.format

import kotlinx.io.*

/** A structural Anvil or compression-container error, independent of I/O. */
class RegionFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class RegionFileChunkStreamInfo(
    val position: LocalChunkPosition,
    val compression: Compression,
    val compressedLength: Int,
    val external: Boolean,
    val timestamp: Int,
)

/**
 * Structural codec for the Anvil `.mca` container.
 *
 * Chunk payloads remain compressed. [encodeToSink] and [decodeFromSource]
 * process the region sector-by-sector; array methods are adapters for callers
 * that explicitly want one in-memory file image.
 */
sealed class RegionFileFormat {
    companion object Default : RegionFileFormat()

    /** Reads one region without closing [source]. */
    fun decodeFromSource(source: Source): RegionFile {
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        readChunksFromSource(source) { info, payload ->
            val bytes = payload.readByteArray()
            val chunkPayload = if (info.external) {
                RegionChunkPayload.External()
            } else {
                RegionChunkPayload.Inline(bytes)
            }
            chunks[info.position] = RegionChunk(
                compression = info.compression,
                payload = chunkPayload,
                timestamp = info.timestamp,
            )
        }
        return RegionFile(chunks)
    }

    /** Lends each inline compressed payload in location order without retaining it. */
    fun readChunksFromSource(
        source: Source,
        block: (RegionFileChunkStreamInfo, Source) -> Unit,
    ) {
        if (source.exhausted()) return
        try {
            readNonEmptyChunks(source, block)
        } catch (failure: EOFException) {
            throw RegionFormatException("Truncated region file", failure)
        }
    }

    fun decodeFromByteArray(bytes: ByteArray): RegionFile {
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
        val header = RegionHeader()
        val externalChunks = linkedMapOf<LocalChunkPosition, ByteArray>()

        plans.forEach { plan ->
            header.set(
                position = plan.position,
                location = RegionLocation(
                    plan.sectorOffset,
                    plan.allocatedSectors,
                ),
                timestamp = plan.chunk.timestamp,
            )
        }
        sink.write(header.encode())

        plans.forEach { plan ->
            val payloadLength = if (plan.external) 0 else plan.compressedPayload.size
            sink.writeInt(payloadLength + 1)
            val version = RegionChunkRecordHeader.compressionId(plan.chunk.compression) or
                    if (plan.external) REGION_EXTERNAL_STREAM_FLAG else 0
            sink.writeByte(version.toByte())
            if (plan.external) {
                externalChunks[plan.position] = plan.compressedPayload
            } else {
                sink.write(plan.compressedPayload)
            }
            val padding = plan.allocatedSectors * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES - payloadLength
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
            if (chunk.external) put(chunk.position, chunk.compressedPayload)
        }
    }

    private fun readNonEmptyChunks(
        source: Source,
        block: (RegionFileChunkStreamInfo, Source) -> Unit,
    ) {
        val header = RegionHeader.decode(source.readByteArray(REGION_HEADER_BYTES))
        val plans = ArrayList<DecodeChunkPlan>()

        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            val location = header.location(position) ?: continue
            val sectorOffset = location.sectorOffset
            val allocatedSectors = location.sectorCount
            if (sectorOffset < 2 || allocatedSectors == 0) {
                throw RegionFormatException(
                    "Chunk $position has invalid sector location $sectorOffset+$allocatedSectors",
                )
            }
            val endSector = sectorOffset + allocatedSectors
            val overlap = plans.firstOrNull { existing ->
                sectorOffset < existing.sectorOffset + existing.allocatedSectors &&
                        existing.sectorOffset < endSector
            }
            if (overlap != null) {
                throw RegionFormatException(
                    "Chunk $position overlaps chunk ${overlap.position}",
                )
            }
            plans += DecodeChunkPlan(
                position = position,
                timestamp = header.timestamp(position),
                sectorOffset = sectorOffset,
                allocatedSectors = allocatedSectors,
            )
        }

        var currentSector = 2
        plans.sortedBy(DecodeChunkPlan::sectorOffset).forEach { plan ->
            val gapSectors = plan.sectorOffset - currentSector
            source.skip(gapSectors.toLong() * REGION_SECTOR_BYTES)

            val length = source.readInt()
            val allocatedPayloadBytes = plan.allocatedSectors * REGION_SECTOR_BYTES - Int.SIZE_BYTES
            if (length !in 1..allocatedPayloadBytes) {
                val sectors = plan.allocatedSectors
                throw RegionFormatException(
                    "Chunk ${plan.position} declares invalid record length $length for $sectors allocated sector(s)",
                )
            }
            val versionByte = source.readByte().toInt() and 0xFF
            val external = versionByte and REGION_EXTERNAL_STREAM_FLAG != 0
            val compressionId = versionByte and REGION_EXTERNAL_STREAM_FLAG.inv()
            val compression =
                RegionChunkRecordHeader.compressionFromId(compressionId)
                ?: throw RegionFormatException(
                    "Chunk ${plan.position} uses unknown compression ID $compressionId",
                )
            val compressedLength = length - 1
            val info = RegionFileChunkStreamInfo(
                position = plan.position,
                compression = compression,
                compressedLength = compressedLength,
                external = external,
                timestamp = plan.timestamp,
            )
            if (external) {
                if (compressedLength != 0) {
                    throw RegionFormatException(
                        "External chunk ${plan.position} also contains an inline payload",
                    )
                }
                block(info, Buffer())
            } else {
                val bounded = BoundedRawSource(source, compressedLength.toLong())
                val payload = bounded.buffered()
                payload.use {
                    block(info, payload)
                    if (!payload.exhausted()) {
                        throw RegionFormatException(
                            "Chunk ${plan.position} payload was not fully consumed",
                        )
                    }
                }
            }
            val padding = allocatedPayloadBytes - length
            source.skip(padding.toLong())
            currentSector = plan.sectorOffset + plan.allocatedSectors
        }

        discardTrailingSectors(source)
    }

    private fun discardTrailingSectors(source: Source) {
        val scratch = ByteArray(8_192)
        while (true) {
            val read = source.readAtMostTo(scratch)
            if (read < 0) return
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
        if (nextSector > REGION_MAX_SECTOR_OFFSET + 1) {
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
        val inlineSectors = regionSectorsForBytes(
            REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedBytes.size,
        )
        val external = chunk.payload.isExternal ||
                inlineSectors >= REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD
        return ChunkPlan(
            position = position,
            chunk = chunk,
            compressedPayload = compressedBytes,
            external = external,
            allocatedSectors = if (external) 1 else inlineSectors,
        )
    }
}

private class ChunkPlan(
    val position: LocalChunkPosition,
    val chunk: RegionChunk,
    val compressedPayload: ByteArray,
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

private class BoundedRawSource(
    private val upstream: Source,
    byteCount: Long,
) : RawSource {
    private var remaining = byteCount

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L)
        if (byteCount == 0L) return 0L
        if (remaining == 0L) return -1L
        val read = upstream.readAtMostTo(sink, minOf(byteCount, remaining))
        if (read < 0L) throw EOFException("Truncated region chunk payload")
        remaining -= read
        return read
    }

    override fun close() = Unit
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
