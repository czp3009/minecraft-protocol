package com.hiczp.minecraft.world.format

import kotlinx.io.*

/** A structural Anvil or compression-container error, independent of I/O. */
class AnvilFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class AnvilChunkRecordInfo(
    val localChunkPosition: LocalChunkPosition,
    val compression: Compression,
    val compressedByteCount: Long,
    val anvilChunkPlacement: AnvilChunkPlacement,
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
        decodeRecordsFromSource(source) { anvilChunkRecordInfo, payload ->
            val content = if (anvilChunkRecordInfo.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL) {
                null
            } else {
                CompressedChunk.takeOwnership(anvilChunkRecordInfo.compression, payload.readByteArray())
            }
            chunks[anvilChunkRecordInfo.localChunkPosition] = if (content == null) {
                AnvilChunkRecord.unresolvedExternal(
                    compression = anvilChunkRecordInfo.compression,
                    timestampEpochSeconds = anvilChunkRecordInfo.timestampEpochSeconds,
                )
            } else {
                AnvilChunkRecord(
                    content = content,
                    anvilChunkPlacement = anvilChunkRecordInfo.anvilChunkPlacement,
                    timestampEpochSeconds = anvilChunkRecordInfo.timestampEpochSeconds,
                )
            }
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
        } catch (recordCallbackFailure: RecordCallbackFailure) {
            throw recordCallbackFailure.original
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
     * The returned external sidecar values reuse [anvilRegion]'s immutable compressed content without copying it.
     */
    fun encodeRecordsToSink(
        anvilRegion: AnvilRegion,
        sink: Sink,
    ): Map<LocalChunkPosition, CompressedChunk> {
        val plans = plan(anvilRegion)
        val regionHeader = RegionHeader()
        val externalChunks = linkedMapOf<LocalChunkPosition, CompressedChunk>()

        plans.forEach { chunkPlan ->
            regionHeader.set(
                localChunkPosition = chunkPlan.localChunkPosition,
                regionLocation = RegionLocation(
                    chunkPlan.sectorOffset,
                    chunkPlan.allocatedSectors,
                ),
                timestamp = chunkPlan.anvilChunkRecord.timestampEpochSeconds,
            )
        }
        sink.write(regionHeader.encode())

        plans.forEach { chunkPlan ->
            val payloadLength = if (chunkPlan.external) 0 else chunkPlan.content.compressedByteCount.toInt()
            sink.writeInt(payloadLength + 1)
            val version = RegionChunkRecordHeader.compressionId(chunkPlan.anvilChunkRecord.compression) or
                    if (chunkPlan.external) REGION_EXTERNAL_STREAM_FLAG else 0
            sink.writeByte(version.toByte())
            if (chunkPlan.external) {
                externalChunks[chunkPlan.localChunkPosition] = chunkPlan.content
            } else {
                chunkPlan.content.writeTo(sink)
            }
            val padding = chunkPlan.allocatedSectors * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES - payloadLength
            writeZeroes(sink, padding)
        }
        return externalChunks
    }

    fun encodeToByteArray(anvilRegion: AnvilRegion): EncodedAnvilRegion {
        val buffer = Buffer()
        val externalChunks = encodeRecordsToSink(anvilRegion, buffer)
        return EncodedAnvilRegion.takeOwnership(
            bytes = buffer.readByteArray(),
            externalChunks = externalChunks.mapValues { (_, content) -> content.toByteArray() },
        )
    }

    private fun decodeNonEmptyRecords(
        source: Source,
        block: (AnvilChunkRecordInfo, Source) -> Unit,
    ) {
        val regionHeader = RegionHeader.decode(source.readByteArray(REGION_HEADER_BYTES))
        val plans = ArrayList<DecodeChunkPlan>()

        for (index in 0 until REGION_CHUNK_COUNT) {
            val localChunkPosition = LocalChunkPosition.fromIndex(index)
            val regionLocation = regionHeader.location(localChunkPosition) ?: continue
            val sectorOffset = regionLocation.sectorOffset
            val allocatedSectors = regionLocation.sectorCount
            if (sectorOffset < 2 || allocatedSectors == 0) {
                throw AnvilFormatException(
                    "Chunk $localChunkPosition has invalid sector location $sectorOffset+$allocatedSectors",
                )
            }
            val endSector = sectorOffset + allocatedSectors
            val overlap = plans.firstOrNull { existing ->
                sectorOffset < existing.sectorOffset + existing.allocatedSectors &&
                        existing.sectorOffset < endSector
            }
            if (overlap != null) {
                throw AnvilFormatException(
                    "Chunk $localChunkPosition overlaps chunk ${overlap.localChunkPosition}",
                )
            }
            plans += DecodeChunkPlan(
                localChunkPosition = localChunkPosition,
                timestamp = regionHeader.timestamp(localChunkPosition),
                sectorOffset = sectorOffset,
                allocatedSectors = allocatedSectors,
            )
        }

        var currentSector = 2
        plans.sortedBy(DecodeChunkPlan::sectorOffset).forEach { decodeChunkPlan ->
            val gapSectors = decodeChunkPlan.sectorOffset - currentSector
            source.skip(gapSectors.toLong() * REGION_SECTOR_BYTES)

            val length = source.readInt()
            val allocatedPayloadBytes = decodeChunkPlan.allocatedSectors * REGION_SECTOR_BYTES - Int.SIZE_BYTES
            if (length !in 1..allocatedPayloadBytes) {
                val sectors = decodeChunkPlan.allocatedSectors
                throw AnvilFormatException(
                    "Chunk ${decodeChunkPlan.localChunkPosition} declares invalid record length $length for $sectors allocated sector(s)",
                )
            }
            val versionByte = source.readByte().toInt() and 0xFF
            val external = versionByte and REGION_EXTERNAL_STREAM_FLAG != 0
            val compressionId = versionByte and REGION_EXTERNAL_STREAM_FLAG.inv()
            val compression =
                RegionChunkRecordHeader.compressionFromId(compressionId)
                    ?: throw AnvilFormatException(
                        "Chunk ${decodeChunkPlan.localChunkPosition} uses unknown compression ID $compressionId",
                    )
            val compressedLength = length - 1
            val anvilChunkRecordInfo = AnvilChunkRecordInfo(
                localChunkPosition = decodeChunkPlan.localChunkPosition,
                compression = compression,
                compressedByteCount = compressedLength.toLong(),
                anvilChunkPlacement = if (external) AnvilChunkPlacement.EXTERNAL else AnvilChunkPlacement.INLINE,
                timestampEpochSeconds = decodeChunkPlan.timestamp,
            )
            if (external) {
                if (compressedLength != 0) {
                    throw AnvilFormatException(
                        "External chunk ${decodeChunkPlan.localChunkPosition} also contains an inline payload",
                    )
                }
                invokeRecordCallback(anvilChunkRecordInfo, Buffer(), block)
            } else {
                val boundedRawSource = BoundedRawSource(source, compressedLength.toLong())
                val payload = boundedRawSource.buffered()
                payload.use {
                    invokeRecordCallback(anvilChunkRecordInfo, payload, block)
                    payload.transferTo(discardingSink())
                }
            }
            val padding = allocatedPayloadBytes - length
            source.skip(padding.toLong())
            currentSector = decodeChunkPlan.sectorOffset + decodeChunkPlan.allocatedSectors
        }

        source.transferTo(discardingSink())
    }

    private fun plan(anvilRegion: AnvilRegion): List<ChunkPlan> {
        val plans = anvilRegion.chunks.entries
            .sortedBy { it.key.index }
            .map { (localChunkPosition, anvilChunkRecord) -> plan(localChunkPosition, anvilChunkRecord) }
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
        localChunkPosition: LocalChunkPosition,
        anvilChunkRecord: AnvilChunkRecord,
    ): ChunkPlan {
        val content = anvilChunkRecord.content
            ?: throw AnvilFormatException(
                "External chunk $localChunkPosition has not been resolved",
            )
        val compressedByteCount = content.compressedByteCount
        if (compressedByteCount > Int.MAX_VALUE) {
            throw AnvilFormatException("Chunk $localChunkPosition is too large for an Anvil record")
        }
        val inlineSectors = regionSectorsForBytes(
            REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedByteCount,
        )
        val external = anvilChunkRecord.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL ||
                inlineSectors >= REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD
        return ChunkPlan(
            localChunkPosition = localChunkPosition,
            anvilChunkRecord = anvilChunkRecord,
            content = content,
            external = external,
            allocatedSectors = if (external) 1 else inlineSectors,
        )
    }
}

private fun invokeRecordCallback(
    anvilChunkRecordInfo: AnvilChunkRecordInfo,
    source: Source,
    block: (AnvilChunkRecordInfo, Source) -> Unit,
) {
    try {
        block(anvilChunkRecordInfo, source)
    } catch (failure: Throwable) {
        throw RecordCallbackFailure(failure)
    }
}

private class RecordCallbackFailure(
    val original: Throwable,
) : RuntimeException(original)

private class ChunkPlan(
    val localChunkPosition: LocalChunkPosition,
    val anvilChunkRecord: AnvilChunkRecord,
    val content: CompressedChunk,
    val external: Boolean,
    val allocatedSectors: Int,
    var sectorOffset: Int = 0,
)

private data class DecodeChunkPlan(
    val localChunkPosition: LocalChunkPosition,
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
