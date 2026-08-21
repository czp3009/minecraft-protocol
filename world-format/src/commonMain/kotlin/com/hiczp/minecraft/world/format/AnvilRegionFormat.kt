package com.hiczp.minecraft.world.format

import kotlinx.io.*

/** A structural Anvil or compression-container error, independent of I/O. */
class AnvilFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class AnvilChunkRecordInfo(
    val position: LocalChunkPosition,
    val compression: Compression,
    val compressedByteCount: Long,
    val placement: AnvilChunkPlacement,
    val timestampEpochSeconds: Int,
)

/**
 * Structural codec for the Anvil `.mca` container.
 *
 * Chunk payloads remain compressed. [encodeRecordsToSink] and [decodeRecordsFromSource]
 * process the region sector-by-sector; array methods are adapters for callers
 * that explicitly want one in-memory file image.
 */
sealed class AnvilRegionFormat {
    companion object Default : AnvilRegionFormat()

    /** Reads one region without closing [source]. */
    fun decodeFromSource(source: Source): AnvilRegion {
        val chunks = linkedMapOf<LocalChunkPosition, AnvilChunkRecord>()
        decodeRecordsFromSource(source) { info, payload ->
            val content = if (info.placement == AnvilChunkPlacement.EXTERNAL) {
                null
            } else {
                CompressedChunk.takeOwnership(info.compression, payload.readByteArray())
            }
            chunks[info.position] = AnvilChunkRecord(
                compression = info.compression,
                content = content,
                placement = info.placement,
                timestampEpochSeconds = info.timestampEpochSeconds,
            )
        }
        return AnvilRegion(chunks)
    }

    /** Lends each inline compressed payload in location order without retaining it. */
    fun decodeRecordsFromSource(
        source: Source,
        block: (AnvilChunkRecordInfo, Source) -> Unit,
    ) {
        if (source.exhausted()) return
        try {
            decodeNonEmptyRecords(source, block)
        } catch (failure: RecordCallbackFailure) {
            throw failure.original
        } catch (failure: EOFException) {
            throw AnvilFormatException("Truncated region file", failure)
        }
    }

    fun decodeFromByteArray(bytes: ByteArray): AnvilRegion {
        val buffer = Buffer()
        buffer.write(bytes)
        return decodeFromSource(buffer)
    }

    /**
     * Writes one region without closing or flushing [sink].
     *
     * The returned external sidecar values reuse [region]'s immutable compressed content without copying it.
     */
    fun encodeRecordsToSink(
        region: AnvilRegion,
        sink: Sink,
    ): Map<LocalChunkPosition, CompressedChunk> {
        val plans = plan(region)
        val header = RegionHeader()
        val externalChunks = linkedMapOf<LocalChunkPosition, CompressedChunk>()

        plans.forEach { plan ->
            header.set(
                position = plan.position,
                location = RegionLocation(
                    plan.sectorOffset,
                    plan.allocatedSectors,
                ),
                timestamp = plan.record.timestampEpochSeconds,
            )
        }
        sink.write(header.encode())

        plans.forEach { plan ->
            val payloadLength = if (plan.external) 0 else plan.content.compressedByteCount.toInt()
            sink.writeInt(payloadLength + 1)
            val version = RegionChunkRecordHeader.compressionId(plan.record.compression) or
                    if (plan.external) REGION_EXTERNAL_STREAM_FLAG else 0
            sink.writeByte(version.toByte())
            if (plan.external) {
                externalChunks[plan.position] = plan.content
            } else {
                plan.content.writeTo(sink)
            }
            val padding = plan.allocatedSectors * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES - payloadLength
            writeZeroes(sink, padding)
        }
        return externalChunks
    }

    fun encodeToByteArray(region: AnvilRegion): EncodedAnvilRegion {
        val buffer = Buffer()
        val externalChunks = encodeRecordsToSink(region, buffer)
        return EncodedAnvilRegion.takeOwnership(
            bytes = buffer.readByteArray(),
            externalChunks = externalChunks.mapValues { (_, content) -> content.toByteArray() },
        )
    }

    private fun decodeNonEmptyRecords(
        source: Source,
        block: (AnvilChunkRecordInfo, Source) -> Unit,
    ) {
        val header = RegionHeader.decode(source.readByteArray(REGION_HEADER_BYTES))
        val plans = ArrayList<DecodeChunkPlan>()

        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            val location = header.location(position) ?: continue
            val sectorOffset = location.sectorOffset
            val allocatedSectors = location.sectorCount
            if (sectorOffset < 2 || allocatedSectors == 0) {
                throw AnvilFormatException(
                    "Chunk $position has invalid sector location $sectorOffset+$allocatedSectors",
                )
            }
            val endSector = sectorOffset + allocatedSectors
            val overlap = plans.firstOrNull { existing ->
                sectorOffset < existing.sectorOffset + existing.allocatedSectors &&
                        existing.sectorOffset < endSector
            }
            if (overlap != null) {
                throw AnvilFormatException(
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
                throw AnvilFormatException(
                    "Chunk ${plan.position} declares invalid record length $length for $sectors allocated sector(s)",
                )
            }
            val versionByte = source.readByte().toInt() and 0xFF
            val external = versionByte and REGION_EXTERNAL_STREAM_FLAG != 0
            val compressionId = versionByte and REGION_EXTERNAL_STREAM_FLAG.inv()
            val compression =
                RegionChunkRecordHeader.compressionFromId(compressionId)
                    ?: throw AnvilFormatException(
                    "Chunk ${plan.position} uses unknown compression ID $compressionId",
                )
            val compressedLength = length - 1
            val info = AnvilChunkRecordInfo(
                position = plan.position,
                compression = compression,
                compressedByteCount = compressedLength.toLong(),
                placement = if (external) AnvilChunkPlacement.EXTERNAL else AnvilChunkPlacement.INLINE,
                timestampEpochSeconds = plan.timestamp,
            )
            if (external) {
                if (compressedLength != 0) {
                    throw AnvilFormatException(
                        "External chunk ${plan.position} also contains an inline payload",
                    )
                }
                invokeRecordCallback(info, Buffer(), block)
            } else {
                val bounded = BoundedRawSource(source, compressedLength.toLong())
                val payload = bounded.buffered()
                payload.use {
                    invokeRecordCallback(info, payload, block)
                    if (!payload.exhausted()) {
                        throw AnvilFormatException(
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

    private fun plan(region: AnvilRegion): List<ChunkPlan> {
        val plans = region.chunks.entries
            .sortedBy { it.key.index }
            .map { (position, chunk) -> plan(position, chunk) }
        var nextSector = 2
        plans.forEach {
            it.sectorOffset = nextSector
            nextSector += it.allocatedSectors
        }
        if (nextSector > REGION_MAX_SECTOR_OFFSET + 1) {
            throw AnvilFormatException(
                "Encoded region exceeds location-table range",
            )
        }
        return plans
    }

    private fun plan(
        position: LocalChunkPosition,
        record: AnvilChunkRecord,
    ): ChunkPlan {
        val content = record.content
            ?: throw AnvilFormatException(
                "External chunk $position has not been resolved",
            )
        val compressedByteCount = content.compressedByteCount
        if (compressedByteCount > Int.MAX_VALUE) {
            throw AnvilFormatException("Chunk $position is too large for an Anvil record")
        }
        val inlineSectors = regionSectorsForBytes(
            REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedByteCount,
        )
        val external = record.placement == AnvilChunkPlacement.EXTERNAL ||
                inlineSectors >= REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD
        return ChunkPlan(
            position = position,
            record = record,
            content = content,
            external = external,
            allocatedSectors = if (external) 1 else inlineSectors,
        )
    }
}

private fun invokeRecordCallback(
    info: AnvilChunkRecordInfo,
    source: Source,
    block: (AnvilChunkRecordInfo, Source) -> Unit,
) {
    try {
        block(info, source)
    } catch (failure: Throwable) {
        throw RecordCallbackFailure(failure)
    }
}

private class RecordCallbackFailure(
    val original: Throwable,
) : RuntimeException(original)

private class ChunkPlan(
    val position: LocalChunkPosition,
    val record: AnvilChunkRecord,
    val content: CompressedChunk,
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
        if (read < 0L) throw AnvilFormatException("Truncated region chunk payload")
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
