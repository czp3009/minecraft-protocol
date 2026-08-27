package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.io.Sink
import kotlinx.io.Source
import okio.Path

/** A detached Anvil image paired with the Region position retained from its `.mca` path. */
internal class PositionedAnvilRegion(
    val regionPosition: RegionPosition,
    val anvilRegion: AnvilRegion,
) {
    val chunks: Map<LocalChunkPosition, AnvilChunkRecord>
        get() = anvilRegion.chunks

    val localChunkPositions: Set<LocalChunkPosition>
        get() = chunks.keys

    val chunkPositions: Set<ChunkPosition>
        get() = localChunkPositions.mapTo(linkedSetOf(), regionPosition::chunk)

    operator fun get(localChunkPosition: LocalChunkPosition): AnvilChunkRecord? = anvilRegion[localChunkPosition]

    operator fun get(chunkPosition: ChunkPosition): AnvilChunkRecord? = get(this.regionPosition.local(chunkPosition))

    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = localChunkPosition in chunks

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(this.regionPosition.local(chunkPosition))

    override fun equals(other: Any?): Boolean =
        other is PositionedAnvilRegion && regionPosition == other.regionPosition && anvilRegion == other.anvilRegion

    override fun hashCode(): Int = 31 * regionPosition.hashCode() + anvilRegion.hashCode()

    override fun toString(): String = "PositionedAnvilRegion(regionPosition=$regionPosition, chunks=$chunks)"
}

/** Logical storage metadata for one Chunk in a Region. */
class RegionChunkInfo internal constructor(
    val regionPosition: RegionPosition,
    val localChunkPosition: LocalChunkPosition,
    val compression: Compression,
    val compressedByteCount: Long,
    internal val anvilChunkPlacement: AnvilChunkPlacement,
    /** Raw signed 32-bit seconds-since-epoch value stored for this Chunk. */
    val timestampEpochSeconds: Int,
) {
    val chunkPosition: ChunkPosition
        get() = regionPosition.chunk(localChunkPosition)

    internal fun copy(compressedByteCount: Long = this.compressedByteCount): RegionChunkInfo =
        RegionChunkInfo(
            regionPosition = regionPosition,
            localChunkPosition = localChunkPosition,
            compression = compression,
            compressedByteCount = compressedByteCount,
            anvilChunkPlacement = anvilChunkPlacement,
            timestampEpochSeconds = timestampEpochSeconds,
        )

    override fun equals(other: Any?): Boolean =
        other is RegionChunkInfo &&
                regionPosition == other.regionPosition &&
                localChunkPosition == other.localChunkPosition &&
                compression == other.compression &&
                compressedByteCount == other.compressedByteCount &&
                timestampEpochSeconds == other.timestampEpochSeconds

    override fun hashCode(): Int {
        var result = regionPosition.hashCode()
        result = 31 * result + localChunkPosition.hashCode()
        result = 31 * result + compression.hashCode()
        result = 31 * result + compressedByteCount.hashCode()
        result = 31 * result + timestampEpochSeconds
        return result
    }

    override fun toString(): String =
        "RegionChunkInfo(regionPosition=$regionPosition, localChunkPosition=$localChunkPosition, compression=$compression, compressedByteCount=$compressedByteCount, timestampEpochSeconds=$timestampEpochSeconds)"
}

internal interface RegionReadAccess {
    val regionPosition: RegionPosition
    val path: Path

    fun hasChunk(localChunkPosition: LocalChunkPosition, regionHeader: RegionHeader): Boolean

    fun readChunkInfo(localChunkPosition: LocalChunkPosition, regionHeader: RegionHeader): RegionChunkInfo?

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        regionHeader: RegionHeader,
        block: (RegionChunkInfo, Source) -> R,
    ): R?
}

/**
 * Borrowed streaming view that reuses one Region header view.
 *
 * Every sequence and Chunk stream is valid only inside the enclosing Region read callback.
 * Metadata is visited lazily in deterministic Region-local order. The owner of the callback
 * defines whether concurrent changes are excluded; this type alone does not make the cached
 * header and subsequently read Chunk bytes a consistent filesystem snapshot.
 */
class RegionReadScope private constructor(
    private val regionReadAccess: RegionReadAccess?,
    val regionPosition: RegionPosition,
    private val regionHeader: RegionHeader,
) {
    internal constructor(regionReadAccess: RegionReadAccess, regionHeader: RegionHeader) : this(
        regionReadAccess,
        regionReadAccess.regionPosition,
        regionHeader,
    )

    private var valid = true

    val chunkInfos: Sequence<RegionChunkInfo>
        get() {
            checkValid()
            return sequence {
                for (index in 0 until REGION_CHUNK_COUNT) {
                    checkValid()
                    val localChunkPosition = LocalChunkPosition.fromIndex(index)
                    regionReadAccess?.readChunkInfo(localChunkPosition, regionHeader)?.let { yield(it) }
                }
            }
        }

    val localChunkPositions: Sequence<LocalChunkPosition>
        get() {
            checkValid()
            return regionHeader.localChunkPositions().onEach { checkValid() }
        }

    val chunkPositions: Sequence<ChunkPosition>
        get() = localChunkPositions.map(regionPosition::chunk)

    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean {
        checkValid()
        return regionReadAccess?.hasChunk(localChunkPosition, regionHeader) == true
    }

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(regionPosition.local(chunkPosition))

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? {
        checkValid()
        return regionReadAccess?.readChunkInfo(localChunkPosition, regionHeader)
    }

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = readChunkInfo(regionPosition.local(chunkPosition))

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, Source) -> R,
    ): R? {
        checkValid()
        return regionReadAccess?.withCompressedChunkSource(localChunkPosition, regionHeader, block)
    }

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, Source) -> R,
    ): R? = withCompressedChunkSource(regionPosition.local(chunkPosition), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, sink: Sink): RegionChunkInfo? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.transferTo(sink)
            regionChunkInfo
        }

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: Sink): RegionChunkInfo? =
        readCompressedChunkTo(regionPosition.local(chunkPosition), sink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            CompressedChunk.readFromSource(source, regionChunkInfo.compression)
        }

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        readCompressedChunk(regionPosition.local(chunkPosition))

    internal fun <R> use(block: RegionReadScope.() -> R): R = try {
        block()
    } finally {
        invalidate()
    }

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region read scope is no longer valid: ${regionReadAccess?.path ?: regionPosition}" }
    }

    internal companion object {
        fun empty(regionPosition: RegionPosition): RegionReadScope = RegionReadScope(null, regionPosition, RegionHeader())
    }
}

/**
 * Borrowed streaming builder for one complete Region replacement.
 *
 * Each position may be supplied once. Omitted positions are removed when the batch commits.
 * Anvil allocation requires the exact compressed length before opening a sink.
 */
class RegionReplacementScope internal constructor(
    val regionPosition: RegionPosition,
    private val streamChunk: (
        LocalChunkPosition,
        Compression,
        Long,
        (Sink) -> Unit,
    ) -> Unit,
) {
    private var valid = true

    fun writeCompressedChunk(localChunkPosition: LocalChunkPosition, compressedChunkInput: CompressedChunkInput) {
        checkValid()
        writeCompressedChunk(localChunkPosition, compressedChunkInput.compression, compressedChunkInput.compressedByteCount, compressedChunkInput::writeTo)
    }

    fun writeCompressedChunk(chunkPosition: ChunkPosition, compressedChunkInput: CompressedChunkInput) =
        writeCompressedChunk(this.regionPosition.local(chunkPosition), compressedChunkInput)

    fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (Sink) -> Unit,
    ) {
        checkValid()
        streamChunk(localChunkPosition, compression, compressedByteCount, block)
    }

    fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (Sink) -> Unit,
    ) = writeCompressedChunk(this.regionPosition.local(chunkPosition), compression, compressedByteCount, block)

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region replacement scope is no longer valid: $regionPosition" }
    }
}

internal fun MutableRegionFile.hasChunk(chunkPosition: ChunkPosition): Boolean =
    hasChunk(this.regionPosition.local(chunkPosition))

internal fun MutableRegionFile.readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? =
    readChunkInfo(this.regionPosition.local(chunkPosition))

internal fun <R> MutableRegionFile.withCompressedChunkSource(
    chunkPosition: ChunkPosition,
    block: (RegionChunkInfo, Source) -> R,
): R? = withCompressedChunkSource(this.regionPosition.local(chunkPosition), block)

internal fun MutableRegionFile.readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
    readCompressedChunk(this.regionPosition.local(chunkPosition))

internal fun MutableRegionFile.writeCompressedChunk(chunkPosition: ChunkPosition, compressedChunkInput: CompressedChunkInput) =
    writeCompressedChunk(this.regionPosition.local(chunkPosition), compressedChunkInput)

internal fun MutableRegionFile.writeCompressedChunk(
    chunkPosition: ChunkPosition,
    compression: Compression,
    compressedByteCount: Long,
    block: (Sink) -> Unit,
) = writeCompressedChunk(this.regionPosition.local(chunkPosition), compression, compressedByteCount, block)

internal fun MutableRegionFile.removeChunk(chunkPosition: ChunkPosition): Boolean =
    removeChunk(this.regionPosition.local(chunkPosition))
