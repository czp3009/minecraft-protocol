package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

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

    suspend fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? =
        delegate.readChunkInfo(localChunkPosition)

    suspend fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(chunkPosition)

    suspend fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    suspend fun readChunkCount(): Int = delegate.readChunkCount()

    suspend fun readLocalChunkPositions(): List<LocalChunkPosition> = delegate.readLocalChunkPositions()

    suspend fun readChunkPositions(): List<ChunkPosition> = delegate.readChunkPositions()

    suspend fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = delegate.hasChunk(localChunkPosition)

    suspend fun hasChunk(chunkPosition: ChunkPosition): Boolean = delegate.hasChunk(chunkPosition)

    suspend fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withCompressedChunkSource(localChunkPosition, block)

    suspend fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withCompressedChunkSource(chunkPosition, block)

    suspend fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(localChunkPosition, sink)

    suspend fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(chunkPosition, sink)

    suspend fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        delegate.readCompressedChunk(localChunkPosition)

    suspend fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        delegate.readCompressedChunk(chunkPosition)

    suspend fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) = delegate.writeCompressedChunk(localChunkPosition, compressedChunkInput)

    suspend fun writeCompressedChunk(chunkPosition: ChunkPosition, compressedChunkInput: CompressedChunkInput) =
        delegate.writeCompressedChunk(chunkPosition, compressedChunkInput)

    suspend fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (BufferedSink) -> Unit,
    ) = delegate.writeCompressedChunk(localChunkPosition, compression, compressedByteCount, block)

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (BufferedSink) -> Unit,
    ) = delegate.writeCompressedChunk(chunkPosition, compression, compressedByteCount, block)

    suspend fun removeChunk(localChunkPosition: LocalChunkPosition): Boolean = delegate.removeChunk(localChunkPosition)

    suspend fun removeChunk(chunkPosition: ChunkPosition): Boolean = delegate.removeChunk(chunkPosition)

    suspend fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withChunkNbtSource(localChunkPosition, block)

    suspend fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withChunkNbtSource(chunkPosition, block)

    suspend fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(localChunkPosition, sink)

    suspend fun readChunkNbtTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(chunkPosition, sink)

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

    suspend fun <E : Any> readChunk(
        localChunkPosition: LocalChunkPosition,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>
    ): EntityChunk<E>? =
        withChunkNbtSource(localChunkPosition) { _, source ->
            entityChunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
        }

    suspend fun <E : Any> readChunk(
        chunkPosition: ChunkPosition,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>
    ): EntityChunk<E>? =
        readChunk(this.regionPosition.local(chunkPosition), entityChunkNbtCodec)

    suspend fun writeChunkNbtDocument(
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = delegate.writeChunkNbtDocument(localChunkPosition, nbtDocument, compression)

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = delegate.writeChunkNbtDocument(chunkPosition, nbtDocument, compression)

    suspend fun writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (BufferedSink) -> Unit,
    ) = delegate.writeChunkNbt(localChunkPosition, compression, block)

    suspend fun writeChunkNbt(
        chunkPosition: ChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (BufferedSink) -> Unit,
    ) = delegate.writeChunkNbt(chunkPosition, compression, block)

    suspend fun <T> writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
        serializationStrategy: SerializationStrategy<T>,
    ) = delegate.writeChunkNbt(localChunkPosition, value, compression, serializationStrategy)

    suspend fun <T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
        serializationStrategy: SerializationStrategy<T>,
    ) = delegate.writeChunkNbt(chunkPosition, value, compression, serializationStrategy)

    suspend inline fun <reified T> writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(localChunkPosition, value, compression, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend inline fun <reified T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(chunkPosition, value, compression, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    /** Writes [chunk] at its retained position after validating Region membership. */
    suspend fun <E : Any> writeChunk(
        entityChunk: EntityChunk<E>,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) {
        val localChunkPosition = regionPosition.local(entityChunk.chunkPosition)
        delegate.writePreparedChunk(localChunkPosition) {
            if (entityChunk.isEmpty) null
            else entityChunkNbtCodec.encodeFromOkio(entityChunk, chunkNbtFormat, compression)
        }
    }

    suspend fun clear() = delegate.clear()

    /**
     * Runs [block] under one shared-read admission with one consistent Entity Region header
     * snapshot. The typed scope accepts only [EntityChunkNbtCodec] for semantic Chunk reads.
     */
    suspend fun <R> withReadScope(block: EntityRegionReadScope.() -> R): R = delegate.withReadScopeCore {
        block(EntityRegionReadScope(this, chunkNbtFormat))
    }

    suspend fun replaceRegion(block: RegionReplacementScope.() -> Unit) = delegate.replaceRegion(block)

    suspend fun flush() = delegate.flush()

    suspend fun close() = delegate.close()

    suspend fun <T> use(block: suspend (EntityRegionHandle) -> T): T =
        useSuspendingResource(this, EntityRegionHandle::close, block)
}
