package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/** Stateless, non-locking read access to one logical live Entity Region. */
class LiveEntityRegionHandle internal constructor(
    private val delegate: LiveRegionHandle,
) {
    val position: RegionPosition
        get() = delegate.position

    val chunkNbtFormat: CompressedNbtFormat
        get() = delegate.chunkNbtFormat

    fun hasRegion(): Boolean = delegate.hasRegion()

    fun hasChunk(local: LocalChunkPosition): Boolean = delegate.hasChunk(local)

    fun hasChunk(position: ChunkPosition): Boolean = delegate.hasChunk(position)

    fun readChunkCount(): Int = delegate.readChunkCount()

    fun readLocalChunkPositions(): List<LocalChunkPosition> = delegate.readLocalChunkPositions()

    fun readChunkPositions(): List<ChunkPosition> = delegate.readChunkPositions()

    fun readChunkInfo(local: LocalChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(local)

    fun readChunkInfo(position: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(position)

    fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    fun <R> withCompressedChunkSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(local, block)

    fun <R> withCompressedChunkSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(position, block)

    fun readCompressedChunkTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(local, sink)

    fun readCompressedChunkTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(position, sink)

    fun readCompressedChunk(local: LocalChunkPosition): CompressedChunk? = delegate.readCompressedChunk(local)

    fun readCompressedChunk(position: ChunkPosition): CompressedChunk? = delegate.readCompressedChunk(position)

    fun <R> withChunkNbtSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(local, block)

    fun <R> withChunkNbtSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(position, block)

    fun readChunkNbtTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(local, sink)

    fun readChunkNbtTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(position, sink)

    fun readChunkNbtDocument(local: LocalChunkPosition): NbtDocument? = delegate.readChunkNbtDocument(local)

    fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? = delegate.readChunkNbtDocument(position)

    fun <T> readChunkNbt(local: LocalChunkPosition, deserializer: DeserializationStrategy<T>): T? =
        delegate.readChunkNbt(local, deserializer)

    fun <T> readChunkNbt(position: ChunkPosition, deserializer: DeserializationStrategy<T>): T? =
        delegate.readChunkNbt(position, deserializer)

    inline fun <reified T> readChunkNbt(local: LocalChunkPosition): T? =
        readChunkNbt(local, chunkNbtFormat.nbt.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    fun <E : Any> readChunk(local: LocalChunkPosition, codec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        withChunkNbtSource(local) { _, source -> codec.decodeFromSource(source, position.chunk(local)) }

    fun <E : Any> readChunk(position: ChunkPosition, codec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        readChunk(this.position.local(position), codec)
}
