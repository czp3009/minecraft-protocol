package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.Path
import okio.buffer

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
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R?
}

internal class RegionReadScopeCore private constructor(
    private val regionReadAccess: RegionReadAccess?,
    val regionPosition: RegionPosition,
    private val regionHeader: RegionHeader,
) {
    constructor(regionReadAccess: RegionReadAccess, regionHeader: RegionHeader) : this(
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

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? =
        readChunkInfo(regionPosition.local(chunkPosition))

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? {
        checkValid()
        return regionReadAccess?.withCompressedChunkSource(localChunkPosition, regionHeader, block)
    }

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withCompressedChunkSource(regionPosition.local(chunkPosition), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.readAll(sink)
            regionChunkInfo
        }

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        readCompressedChunkTo(regionPosition.local(chunkPosition), sink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.readCompressedChunkFromOkio(regionChunkInfo.compression)
        }

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        readCompressedChunk(regionPosition.local(chunkPosition))

    fun <R> use(block: RegionReadScopeCore.() -> R): R = try {
        block()
    } finally {
        invalidate()
    }

    private fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region read scope is no longer valid: ${regionReadAccess?.path ?: regionPosition}" }
    }

    companion object {
        fun empty(regionPosition: RegionPosition): RegionReadScopeCore =
            RegionReadScopeCore(null, regionPosition, RegionHeader())
    }
}

/**
 * Common callback-bound read view for one Chunk, Entity, or POI Anvil Region.
 *
 * Every sequence and Chunk stream is valid only inside the enclosing Region read callback.
 * Metadata is visited lazily in deterministic Region-local order. The owning handle defines
 * whether concurrent changes are excluded; this type alone does not make the cached Header and
 * subsequently read Chunk bytes a consistent filesystem snapshot.
 */
abstract class AnvilRegionReadScope internal constructor(
    private val regionReadScopeCore: RegionReadScopeCore,
    val chunkNbtFormat: CompressedNbtFormat,
) {
    val regionPosition: RegionPosition
        get() = regionReadScopeCore.regionPosition

    val chunkInfos: Sequence<RegionChunkInfo>
        get() = regionReadScopeCore.chunkInfos

    val localChunkPositions: Sequence<LocalChunkPosition>
        get() = regionReadScopeCore.localChunkPositions

    val chunkPositions: Sequence<ChunkPosition>
        get() = regionReadScopeCore.chunkPositions

    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = regionReadScopeCore.hasChunk(localChunkPosition)

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = regionReadScopeCore.hasChunk(chunkPosition)

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? =
        regionReadScopeCore.readChunkInfo(localChunkPosition)

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = regionReadScopeCore.readChunkInfo(chunkPosition)

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = regionReadScopeCore.withCompressedChunkSource(localChunkPosition, block)

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = regionReadScopeCore.withCompressedChunkSource(chunkPosition, block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        regionReadScopeCore.readCompressedChunkTo(localChunkPosition, sink)

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        regionReadScopeCore.readCompressedChunkTo(chunkPosition, sink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        regionReadScopeCore.readCompressedChunk(localChunkPosition)

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        regionReadScopeCore.readCompressedChunk(chunkPosition)

    fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
        withDecompressedChunkSource(chunkNbtFormat, regionChunkInfo, source, block)
    }

    fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withChunkNbtSource(regionPosition.local(chunkPosition), block)

    /** Copies one complete decompressed unnamed-root Chunk NBT stream without closing [sink]. */
    fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        withChunkNbtSource(localChunkPosition) { regionChunkInfo, source ->
            source.readAll(sink)
            regionChunkInfo
        }

    fun readChunkNbtTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        readChunkNbtTo(regionPosition.local(chunkPosition), sink)

    fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? =
        withChunkNbtSource(localChunkPosition) { _, source -> chunkNbtFormat.nbtFormat.decodeDocumentFromOkio(source) }

    fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(regionPosition.local(chunkPosition))

    fun <T> readChunkNbt(
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeFromOkio(deserializationStrategy, source)
    }

    fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readChunkNbt(regionPosition.local(chunkPosition), deserializationStrategy)

    inline fun <reified T> readChunkNbt(localChunkPosition: LocalChunkPosition): T? =
        readChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())
}

/**
 * Callback-bound semantic read view created by an ordinary Chunk Region handle.
 *
 * [readChunk] accepts [ChunkNbtCodec], while an Entity Region exposes
 * [EntityRegionReadScope] instead.
 */
class RegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun <B : Any, M : Any> readChunk(
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withChunkNbtSource(localChunkPosition) { _, source ->
        chunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
    }

    fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(regionPosition.local(chunkPosition), chunkNbtCodec)

    fun <B : Any, M : Any> readChunk(
        blockPosition: BlockPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(blockPosition.chunkPosition, chunkNbtCodec)
}

/**
 * Callback-bound semantic read view created by an Entity Region handle.
 *
 * [readChunk] accepts [EntityChunkNbtCodec], while an ordinary Chunk Region exposes
 * [RegionReadScope] instead.
 */
class EntityRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun <E : Any> readChunk(
        localChunkPosition: LocalChunkPosition,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>,
    ): EntityChunk<E>? = withChunkNbtSource(localChunkPosition) { _, source ->
        entityChunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
    }

    fun <E : Any> readChunk(
        chunkPosition: ChunkPosition,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>,
    ): EntityChunk<E>? = readChunk(regionPosition.local(chunkPosition), entityChunkNbtCodec)
}

/** Callback-bound semantic read view created by a POI Region handle. */
class PoiRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    private val poiChunkNbtCodec = PoiChunkNbtCodec(chunkNbtFormat.nbtFormat)

    fun readChunk(localChunkPosition: LocalChunkPosition): PoiChunk? =
        withChunkNbtSource(localChunkPosition) { _, source ->
            poiChunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
        }

    fun readChunk(chunkPosition: ChunkPosition): PoiChunk? = readChunk(regionPosition.local(chunkPosition))
}

internal fun <R> withDecompressedChunkSource(
    chunkNbtFormat: CompressedNbtFormat,
    regionChunkInfo: RegionChunkInfo,
    source: BufferedSource,
    block: (RegionChunkInfo, BufferedSource) -> R,
): R {
    val kotlinxSource = source.asKotlinxIoRawSource().buffered()
    val decompressedSource = withOkioIoFailures {
        chunkNbtFormat.compressionRegistry.decompressingSource(regionChunkInfo.compression, kotlinxSource)
    }.asOkioSource().buffer()
    return useResource(decompressedSource, { it.close() }) {
        val result = block(regionChunkInfo, decompressedSource)
        if (!decompressedSource.exhausted()) {
            throw WorldIOException("Chunk ${regionChunkInfo.chunkPosition} NBT source was not fully consumed")
        }
        result
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
        (BufferedSink) -> Unit,
    ) -> Unit,
) {
    private var valid = true

    fun writeCompressedChunk(localChunkPosition: LocalChunkPosition, compressedChunk: CompressedChunk) {
        checkValid()
        writeCompressedChunk(
            localChunkPosition,
            compressedChunk.compression,
            compressedChunk.compressedByteCount,
        ) { sink -> compressedChunk.writeToOkio(sink) }
    }

    fun writeCompressedChunk(chunkPosition: ChunkPosition, compressedChunk: CompressedChunk) =
        writeCompressedChunk(this.regionPosition.local(chunkPosition), compressedChunk)

    fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (BufferedSink) -> Unit,
    ) {
        checkValid()
        streamChunk(localChunkPosition, compression, compressedByteCount, block)
    }

    fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (BufferedSink) -> Unit,
    ) = writeCompressedChunk(this.regionPosition.local(chunkPosition), compression, compressedByteCount, block)

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region replacement scope is no longer valid: $regionPosition" }
    }
}
