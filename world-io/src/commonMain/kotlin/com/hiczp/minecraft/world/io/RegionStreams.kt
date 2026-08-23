package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.io.Sink
import kotlinx.io.Source

/** A detached Anvil image paired with the Region position retained from its `.mca` path. */
internal class PositionedAnvilRegion(
    val position: RegionPosition,
    val anvilRegion: AnvilRegion,
) {
    val chunks: Map<LocalChunkPosition, AnvilChunkRecord>
        get() = anvilRegion.chunks

    val localChunkPositions: Set<LocalChunkPosition>
        get() = chunks.keys

    val chunkPositions: Set<ChunkPosition>
        get() = localChunkPositions.mapTo(linkedSetOf(), position::chunk)

    operator fun get(position: LocalChunkPosition): AnvilChunkRecord? = anvilRegion[position]

    operator fun get(position: ChunkPosition): AnvilChunkRecord? = get(this.position.local(position))

    fun hasChunk(position: LocalChunkPosition): Boolean = position in chunks

    fun hasChunk(position: ChunkPosition): Boolean = hasChunk(this.position.local(position))

    override fun equals(other: Any?): Boolean =
        other is PositionedAnvilRegion && position == other.position && anvilRegion == other.anvilRegion

    override fun hashCode(): Int = 31 * position.hashCode() + anvilRegion.hashCode()

    override fun toString(): String = "PositionedAnvilRegion(position=$position, chunks=$chunks)"
}

/** Logical storage metadata for one Chunk in a Region. */
class RegionChunkInfo internal constructor(
    val region: RegionPosition,
    val localPosition: LocalChunkPosition,
    val compression: Compression,
    val compressedByteCount: Long,
    internal val placement: AnvilChunkPlacement,
    /** Raw signed 32-bit seconds-since-epoch value stored for this Chunk. */
    val timestampEpochSeconds: Int,
) {
    val position: ChunkPosition
        get() = region.chunk(localPosition)

    internal fun copy(compressedByteCount: Long = this.compressedByteCount): RegionChunkInfo =
        RegionChunkInfo(
            region = region,
            localPosition = localPosition,
            compression = compression,
            compressedByteCount = compressedByteCount,
            placement = placement,
            timestampEpochSeconds = timestampEpochSeconds,
        )

    override fun equals(other: Any?): Boolean =
        other is RegionChunkInfo &&
                region == other.region &&
                localPosition == other.localPosition &&
                compression == other.compression &&
                compressedByteCount == other.compressedByteCount &&
                timestampEpochSeconds == other.timestampEpochSeconds

    override fun hashCode(): Int {
        var result = region.hashCode()
        result = 31 * result + localPosition.hashCode()
        result = 31 * result + compression.hashCode()
        result = 31 * result + compressedByteCount.hashCode()
        result = 31 * result + timestampEpochSeconds
        return result
    }

    override fun toString(): String = buildString {
        append("RegionChunkInfo(region=")
        append(region)
        append(", localPosition=")
        append(localPosition)
        append(", compression=")
        append(compression)
        append(", compressedByteCount=")
        append(compressedByteCount)
        append(", timestampEpochSeconds=")
        append(timestampEpochSeconds)
        append(')')
    }
}

/**
 * Borrowed streaming view of one consistent Region metadata snapshot.
 *
 * Every sequence and Chunk stream is valid only inside the enclosing Region read callback.
 * Metadata is visited lazily in deterministic Region-local order.
 */
class RegionReadScope private constructor(
    private val file: MutableRegionFile?,
    private val region: RegionPosition,
    private val header: RegionHeader,
) {
    internal constructor(file: MutableRegionFile, header: RegionHeader) : this(file, file.position, header)

    private var valid = true

    val position: RegionPosition
        get() = region

    val chunkInfos: Sequence<RegionChunkInfo>
        get() {
            checkValid()
            return sequence {
                for (index in 0 until REGION_CHUNK_COUNT) {
                    checkValid()
                    val local = LocalChunkPosition.fromIndex(index)
                    file?.readChunkInfo(local, header)?.let { yield(it) }
                }
            }
        }

    val localChunkPositions: Sequence<LocalChunkPosition>
        get() {
            checkValid()
            return header.localChunkPositions().onEach { checkValid() }
        }

    val chunkPositions: Sequence<ChunkPosition>
        get() = localChunkPositions.map(region::chunk)

    fun hasChunk(position: LocalChunkPosition): Boolean {
        checkValid()
        return file?.hasChunk(position, header) == true
    }

    fun hasChunk(position: ChunkPosition): Boolean = hasChunk(region.local(position))

    fun readChunkInfo(position: LocalChunkPosition): RegionChunkInfo? {
        checkValid()
        return file?.readChunkInfo(position, header)
    }

    fun readChunkInfo(position: ChunkPosition): RegionChunkInfo? = readChunkInfo(region.local(position))

    fun <R> withCompressedChunkSource(
        position: LocalChunkPosition,
        block: (RegionChunkInfo, Source) -> R,
    ): R? {
        checkValid()
        return file?.withCompressedChunkSource(position, header, block)
    }

    fun <R> withCompressedChunkSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, Source) -> R,
    ): R? = withCompressedChunkSource(region.local(position), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    fun readCompressedChunkTo(position: LocalChunkPosition, sink: Sink): RegionChunkInfo? =
        withCompressedChunkSource(position) { info, source ->
            source.transferTo(sink)
            info
        }

    fun readCompressedChunkTo(position: ChunkPosition, sink: Sink): RegionChunkInfo? =
        readCompressedChunkTo(region.local(position), sink)

    fun readCompressedChunk(position: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(position) { info, source ->
            CompressedChunk.readFromSource(source, info.compression)
        }

    fun readCompressedChunk(position: ChunkPosition): CompressedChunk? =
        readCompressedChunk(region.local(position))

    internal fun <R> use(block: RegionReadScope.() -> R): R = try {
        block()
    } finally {
        invalidate()
    }

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region read scope is no longer valid: ${file?.path ?: region}" }
    }

    internal companion object {
        fun empty(region: RegionPosition): RegionReadScope = RegionReadScope(null, region, RegionHeader())
    }
}

/**
 * Borrowed streaming builder for one complete Region replacement.
 *
 * Each position may be supplied once. Omitted positions are removed when the batch commits.
 * Anvil allocation requires the exact compressed length before opening a sink.
 */
class RegionReplacementScope internal constructor(
    val position: RegionPosition,
    private val streamChunk: (
        LocalChunkPosition,
        Compression,
        Long,
        (Sink) -> Unit,
    ) -> Unit,
) {
    private var valid = true

    fun writeCompressedChunk(position: LocalChunkPosition, chunk: CompressedChunkInput) {
        checkValid()
        writeCompressedChunk(position, chunk.compression, chunk.compressedByteCount, chunk::writeTo)
    }

    fun writeCompressedChunk(position: ChunkPosition, chunk: CompressedChunkInput) =
        writeCompressedChunk(this.position.local(position), chunk)

    fun writeCompressedChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (Sink) -> Unit,
    ) {
        checkValid()
        streamChunk(position, compression, compressedByteCount, block)
    }

    fun writeCompressedChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (Sink) -> Unit,
    ) = writeCompressedChunk(this.position.local(position), compression, compressedByteCount, block)

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region replacement scope is no longer valid: $position" }
    }
}

internal fun MutableRegionFile.hasChunk(position: ChunkPosition): Boolean =
    hasChunk(this.position.local(position))

internal fun MutableRegionFile.readChunkInfo(position: ChunkPosition): RegionChunkInfo? =
    readChunkInfo(this.position.local(position))

internal fun <R> MutableRegionFile.withCompressedChunkSource(
    position: ChunkPosition,
    block: (RegionChunkInfo, Source) -> R,
): R? = withCompressedChunkSource(this.position.local(position), block)

internal fun MutableRegionFile.readCompressedChunk(position: ChunkPosition): CompressedChunk? =
    readCompressedChunk(this.position.local(position))

internal fun MutableRegionFile.writeCompressedChunk(position: ChunkPosition, chunk: CompressedChunkInput) =
    writeCompressedChunk(this.position.local(position), chunk)

internal fun MutableRegionFile.writeCompressedChunk(
    position: ChunkPosition,
    compression: Compression,
    compressedByteCount: Long,
    block: (Sink) -> Unit,
) = writeCompressedChunk(this.position.local(position), compression, compressedByteCount, block)

internal fun MutableRegionFile.removeChunk(position: ChunkPosition): Boolean =
    removeChunk(this.position.local(position))
