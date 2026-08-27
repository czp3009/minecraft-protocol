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
    val regionPosition: RegionPosition
        get() = delegate.regionPosition

    val chunkNbtFormat: CompressedNbtFormat
        get() = delegate.chunkNbtFormat

    val regionStorageConfiguration: RegionStorageConfiguration
        get() = delegate.regionStorageConfiguration

    suspend fun hasRegion(): Boolean = delegate.hasRegion()

    suspend fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(localChunkPosition)

    suspend fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(chunkPosition)

    suspend fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    suspend fun readChunkCount(): Int = delegate.readChunkCount()

    suspend fun readLocalChunkPositions(): List<LocalChunkPosition> = delegate.readLocalChunkPositions()

    suspend fun readChunkPositions(): List<ChunkPosition> = delegate.readChunkPositions()

    suspend fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = delegate.hasChunk(localChunkPosition)

    suspend fun hasChunk(chunkPosition: ChunkPosition): Boolean = delegate.hasChunk(chunkPosition)

    suspend fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(localChunkPosition, block)

    suspend fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withCompressedChunkSource(chunkPosition, block)

    suspend fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(localChunkPosition, kotlinxSink)

    suspend fun readCompressedChunkTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(chunkPosition, kotlinxSink)

    suspend fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? = delegate.readCompressedChunk(localChunkPosition)

    suspend fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? = delegate.readCompressedChunk(chunkPosition)

    suspend fun writeCompressedChunk(localChunkPosition: LocalChunkPosition, compressedChunkInput: CompressedChunkInput) =
        delegate.writeCompressedChunk(localChunkPosition, compressedChunkInput)

    suspend fun writeCompressedChunk(chunkPosition: ChunkPosition, compressedChunkInput: CompressedChunkInput) =
        delegate.writeCompressedChunk(chunkPosition, compressedChunkInput)

    suspend fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeCompressedChunk(localChunkPosition, compression, compressedByteCount, block)

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeCompressedChunk(chunkPosition, compression, compressedByteCount, block)

    suspend fun removeChunk(localChunkPosition: LocalChunkPosition): Boolean = delegate.removeChunk(localChunkPosition)

    suspend fun removeChunk(chunkPosition: ChunkPosition): Boolean = delegate.removeChunk(chunkPosition)

    suspend fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(localChunkPosition, block)

    suspend fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = delegate.withChunkNbtSource(chunkPosition, block)

    suspend fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(localChunkPosition, kotlinxSink)

    suspend fun readChunkNbtTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(chunkPosition, kotlinxSink)

    suspend fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? =
        delegate.readChunkNbtDocument(localChunkPosition)

    suspend fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? =
        delegate.readChunkNbtDocument(chunkPosition)

    suspend fun <T> readChunkNbt(
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = delegate.readChunkNbt(localChunkPosition, deserializationStrategy)

    suspend fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = delegate.readChunkNbt(chunkPosition, deserializationStrategy)

    suspend inline fun <reified T> readChunkNbt(localChunkPosition: LocalChunkPosition): T? =
        readChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend fun <E : Any> readChunk(localChunkPosition: LocalChunkPosition, entityChunkNbtCodec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        withChunkNbtSource(localChunkPosition) { _, source -> entityChunkNbtCodec.decodeFromSource(source, regionPosition.chunk(localChunkPosition)) }

    suspend fun <E : Any> readChunk(chunkPosition: ChunkPosition, entityChunkNbtCodec: EntityChunkNbtCodec<E>): EntityChunk<E>? =
        readChunk(this.regionPosition.local(chunkPosition), entityChunkNbtCodec)

    suspend fun writeChunkNbtDocument(localChunkPosition: LocalChunkPosition, nbtDocument: NbtDocument) =
        delegate.writeChunkNbtDocument(localChunkPosition, nbtDocument)

    suspend fun writeChunkNbtDocument(chunkPosition: ChunkPosition, nbtDocument: NbtDocument) =
        delegate.writeChunkNbtDocument(chunkPosition, nbtDocument)

    suspend fun writeChunkNbtDocument(
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) = delegate.writeChunkNbtDocument(localChunkPosition, nbtDocument, compression)

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) = delegate.writeChunkNbtDocument(chunkPosition, nbtDocument, compression)

    suspend fun writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeChunkNbt(localChunkPosition, compression, block)

    suspend fun writeChunkNbt(
        chunkPosition: ChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = delegate.writeChunkNbt(chunkPosition, compression, block)

    suspend fun <T> writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = delegate.writeChunkNbt(localChunkPosition, serializationStrategy, value, compression)

    suspend fun <T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = delegate.writeChunkNbt(chunkPosition, serializationStrategy, value, compression)

    suspend inline fun <reified T> writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer(), value, compression)

    suspend inline fun <reified T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer(), value, compression)

    /** Writes [chunk] at its retained position after validating Region membership. */
    suspend fun <E : Any> writeChunk(
        entityChunk: EntityChunk<E>,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) {
        val localChunkPosition = regionPosition.local(entityChunk.chunkPosition)
        if (entityChunk.isEmpty) {
            delegate.removeChunk(localChunkPosition)
        } else {
            delegate.writeChunkNbt(localChunkPosition, compression) { sink ->
                entityChunkNbtCodec.encodeToSink(entityChunk, sink)
            }
        }
    }

    suspend fun clear() = delegate.clear()

    suspend fun replaceRegion(chunks: Collection<RegionChunkInput>) = delegate.replaceRegion(chunks)

    /** Runs [block] under one shared-read admission with one consistent Entity Region header snapshot. */
    suspend fun <R> withReadScope(block: RegionReadScope.() -> R): R = delegate.withReadScope(block)

    suspend fun replaceRegion(block: RegionReplacementScope.() -> Unit) = delegate.replaceRegion(block)

    suspend fun flush() = delegate.flush()

    suspend fun close() = delegate.close()

    suspend fun <T> use(block: suspend (EntityRegionHandle) -> T): T =
        useSuspendingResource(this, EntityRegionHandle::close, block)
}
