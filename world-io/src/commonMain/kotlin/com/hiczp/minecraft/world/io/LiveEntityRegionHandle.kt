package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/** Caller-owned live Entity Region resource with the same read and scope semantics as [LiveRegionHandle]. */
class LiveEntityRegionHandle internal constructor(
    private val delegate: LiveRegionHandle,
) {
    val regionPosition: RegionPosition
        get() = delegate.regionPosition

    val chunkNbtFormat: CompressedNbtFormat
        get() = delegate.chunkNbtFormat

    fun hasRegion(): Boolean = delegate.hasRegion()

    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = delegate.hasChunk(localChunkPosition)

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = delegate.hasChunk(chunkPosition)

    fun readChunkCount(): Int = delegate.readChunkCount()

    fun readLocalChunkPositions(): List<LocalChunkPosition> = delegate.readLocalChunkPositions()

    fun readChunkPositions(): List<ChunkPosition> = delegate.readChunkPositions()

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(localChunkPosition)

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(chunkPosition)

    fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(localChunkPosition, block)

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(chunkPosition, block)

    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(localChunkPosition, kotlinxSink)

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(chunkPosition, kotlinxSink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? = delegate.readCompressedChunk(localChunkPosition)

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? = delegate.readCompressedChunk(chunkPosition)

    fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(localChunkPosition, block)

    fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(chunkPosition, block)

    fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(localChunkPosition, kotlinxSink)

    fun readChunkNbtTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(chunkPosition, kotlinxSink)

    fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? = delegate.readChunkNbtDocument(localChunkPosition)

    fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? = delegate.readChunkNbtDocument(chunkPosition)

    fun <T> readChunkNbt(localChunkPosition: LocalChunkPosition, deserializationStrategy: DeserializationStrategy<T>): T? =
        delegate.readChunkNbt(localChunkPosition, deserializationStrategy)

    fun <T> readChunkNbt(chunkPosition: ChunkPosition, deserializationStrategy: DeserializationStrategy<T>): T? =
        delegate.readChunkNbt(chunkPosition, deserializationStrategy)

    inline fun <reified T> readChunkNbt(localChunkPosition: LocalChunkPosition): T? =
        readChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    fun <E : Any> readChunk(localChunkPosition: LocalChunkPosition, entityChunkNbtCodec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        withChunkNbtSource(localChunkPosition) { _, source -> entityChunkNbtCodec.decodeFromSource(source, regionPosition.chunk(localChunkPosition)) }

    fun <E : Any> readChunk(chunkPosition: ChunkPosition, entityChunkNbtCodec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        readChunk(this.regionPosition.local(chunkPosition), entityChunkNbtCodec)

    /**
     * Reuses one Entity Region header read for typed Entity Chunk decoding without promising a
     * consistent live snapshot.
     */
    fun <R> withReadScope(block: EntityRegionReadScope.() -> R): R = delegate.withReadScopeCore {
        block(EntityRegionReadScope(this, chunkNbtFormat))
    }

    fun close() = delegate.close()

    /** Runs [block] and closes this independently owned live Entity Region handle afterward. */
    fun <T> use(block: (LiveEntityRegionHandle) -> T): T =
        useResource(this, LiveEntityRegionHandle::close, block)
}
