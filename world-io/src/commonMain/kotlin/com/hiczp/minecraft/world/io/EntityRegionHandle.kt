package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Caller-owned coordinated access to one Entity Region.
 *
 * This is the entity-storage counterpart of [RegionHandle]. Reads share the logical Region lock and writes hold it
 * exclusively.
 */
class EntityRegionHandle internal constructor(
    private val delegate: RegionHandle,
) {
    val position: RegionPosition
        get() = delegate.position

    val chunkNbtFormat: CompressedNbtFormat
        get() = delegate.chunkNbtFormat

    val configuration: RegionStorageConfiguration
        get() = delegate.configuration

    suspend fun hasRegion(): Boolean = delegate.hasRegion()

    suspend fun readChunkInfo(local: LocalChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(local)

    suspend fun readChunkInfo(position: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(position)

    suspend fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    suspend fun readChunkCount(): Int = delegate.readChunkCount()

    suspend fun readLocalChunkPositions(): List<LocalChunkPosition> = delegate.readLocalChunkPositions()

    suspend fun readChunkPositions(): List<ChunkPosition> = delegate.readChunkPositions()

    suspend fun hasChunk(local: LocalChunkPosition): Boolean = delegate.hasChunk(local)

    suspend fun hasChunk(position: ChunkPosition): Boolean = delegate.hasChunk(position)

    suspend fun <R> withCompressedChunkSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(local, block)

    suspend fun <R> withCompressedChunkSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(position, block)

    suspend fun readCompressedChunkTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(local, sink)

    suspend fun readCompressedChunkTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(position, sink)

    suspend fun readCompressedChunk(local: LocalChunkPosition): CompressedChunk? = delegate.readCompressedChunk(local)

    suspend fun readCompressedChunk(position: ChunkPosition): CompressedChunk? = delegate.readCompressedChunk(position)

    suspend fun writeCompressedChunk(local: LocalChunkPosition, chunk: CompressedChunkInput) =
        delegate.writeCompressedChunk(local, chunk)

    suspend fun writeCompressedChunk(position: ChunkPosition, chunk: CompressedChunkInput) =
        delegate.writeCompressedChunk(position, chunk)

    suspend fun writeCompressedChunk(
        local: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeCompressedChunk(local, compression, compressedByteCount, block)

    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeCompressedChunk(position, compression, compressedByteCount, block)

    suspend fun removeChunk(local: LocalChunkPosition): Boolean = delegate.removeChunk(local)

    suspend fun removeChunk(position: ChunkPosition): Boolean = delegate.removeChunk(position)

    suspend fun <R> withChunkNbtSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(local, block)

    suspend fun <R> withChunkNbtSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(position, block)

    suspend fun readChunkNbtTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(local, sink)

    suspend fun readChunkNbtTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(position, sink)

    suspend fun readChunkNbtDocument(local: LocalChunkPosition): NbtDocument? =
        delegate.readChunkNbtDocument(local)

    suspend fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? =
        delegate.readChunkNbtDocument(position)

    suspend fun <T> readChunkNbt(
        local: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = delegate.readChunkNbt(local, deserializer)

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = delegate.readChunkNbt(position, deserializer)

    suspend inline fun <reified T> readChunkNbt(position: LocalChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend fun <E : Any> readChunk(local: LocalChunkPosition, codec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        withChunkNbtSource(local) { _, source -> codec.decodeFromSource(source, position.chunk(local)) }

    suspend fun <E : Any> readChunk(position: ChunkPosition, codec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        readChunk(this.position.local(position), codec)

    suspend fun writeChunkNbtDocument(local: LocalChunkPosition, document: NbtDocument) =
        delegate.writeChunkNbtDocument(local, document)

    suspend fun writeChunkNbtDocument(position: ChunkPosition, document: NbtDocument) =
        delegate.writeChunkNbtDocument(position, document)

    suspend fun writeChunkNbtDocument(
        local: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = delegate.writeChunkNbtDocument(local, document, compression)

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = delegate.writeChunkNbtDocument(position, document, compression)

    suspend fun writeChunkNbt(
        local: LocalChunkPosition,
        compression: Compression = configuration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeChunkNbt(local, compression, block)

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        compression: Compression = configuration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeChunkNbt(position, compression, block)

    suspend fun <T> writeChunkNbt(
        local: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = delegate.writeChunkNbt(local, serializer, value, compression)

    suspend fun <T> writeChunkNbt(
        position: ChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = delegate.writeChunkNbt(position, serializer, value, compression)

    suspend inline fun <reified T> writeChunkNbt(
        position: LocalChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer(), value, compression)

    suspend inline fun <reified T> writeChunkNbt(
        position: ChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer(), value, compression)

    /** Writes [chunk] at its retained position after validating Region membership. */
    suspend fun <E : Any> writeChunk(
        chunk: EntityChunk<E>,
        codec: EntityChunkNbtCodec<E>,
        compression: Compression = configuration.writeCompression,
    ) {
        val local = position.local(chunk.position)
        if (chunk.isEmpty) {
            delegate.removeChunk(local)
        } else {
            delegate.writeChunkNbt(local, compression) { sink ->
                codec.encodeToSink(chunk, sink)
            }
        }
    }

    suspend fun clear() = delegate.clear()

    suspend fun replaceRegion(chunks: Collection<RegionChunkInput>) = delegate.replaceRegion(chunks)

    suspend fun <R> withReadScope(block: RegionReadScope.() -> R): R = delegate.withReadScope(block)

    suspend fun replaceRegion(block: RegionReplacementScope.() -> Unit) = delegate.replaceRegion(block)

    suspend fun flush() = delegate.flush()

    suspend fun close() = delegate.close()

    suspend fun <T> use(block: suspend (EntityRegionHandle) -> T): T =
        useSuspendingResource(this, EntityRegionHandle::close, block)
}
