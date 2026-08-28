package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

/** Caller-owned coordinated access to one POI Region. */
class PoiRegionHandle internal constructor(
    private val delegate: RegionHandle,
) {
    private val poiChunkNbtCodec = PoiChunkNbtCodec(delegate.chunkNbtFormat.nbtFormat)

    val regionPosition: RegionPosition
        get() = delegate.regionPosition

    val chunkNbtFormat: CompressedNbtFormat
        get() = delegate.chunkNbtFormat

    val regionStorageConfiguration: RegionStorageConfiguration
        get() = delegate.regionStorageConfiguration

    suspend fun hasRegion(): Boolean = delegate.hasRegion()

    suspend fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = delegate.hasChunk(localChunkPosition)

    suspend fun hasChunk(chunkPosition: ChunkPosition): Boolean = delegate.hasChunk(chunkPosition)

    suspend fun readChunkCount(): Int = delegate.readChunkCount()

    suspend fun readLocalChunkPositions(): List<LocalChunkPosition> = delegate.readLocalChunkPositions()

    suspend fun readChunkPositions(): List<ChunkPosition> = delegate.readChunkPositions()

    suspend fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? =
        delegate.readChunkInfo(localChunkPosition)

    suspend fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(chunkPosition)

    suspend fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    suspend fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withCompressedChunkSource(localChunkPosition, block)

    suspend fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withCompressedChunkSource(chunkPosition, block)

    suspend fun readCompressedChunkTo(
        localChunkPosition: LocalChunkPosition,
        sink: BufferedSink,
    ): RegionChunkInfo? = delegate.readCompressedChunkTo(localChunkPosition, sink)

    suspend fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(chunkPosition, sink)

    suspend fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        delegate.readCompressedChunk(localChunkPosition)

    suspend fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        delegate.readCompressedChunk(chunkPosition)

    suspend fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compressedChunk: CompressedChunk,
    ) = delegate.writeCompressedChunk(localChunkPosition, compressedChunk)

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compressedChunk: CompressedChunk,
    ) = delegate.writeCompressedChunk(chunkPosition, compressedChunk)

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

    suspend fun readChunkNbtTo(
        localChunkPosition: LocalChunkPosition,
        sink: BufferedSink,
    ): RegionChunkInfo? = delegate.readChunkNbtTo(localChunkPosition, sink)

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

    suspend fun readChunk(localChunkPosition: LocalChunkPosition): PoiChunk? =
        withChunkNbtSource(localChunkPosition) { _, source ->
            poiChunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
        }

    suspend fun readChunk(chunkPosition: ChunkPosition): PoiChunk? =
        readChunk(regionPosition.local(chunkPosition))

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
        block: (BufferedSink) -> Unit,
    ) = delegate.writeChunkNbt(localChunkPosition, compression, block)

    suspend fun writeChunkNbt(
        chunkPosition: ChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (BufferedSink) -> Unit,
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
    ) = writeChunkNbt(
        localChunkPosition,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
        value,
        compression,
    )

    /** Writes [poiChunk] at its retained position after validating Region membership. */
    suspend fun writeChunk(
        poiChunk: PoiChunk,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) {
        val localChunkPosition = regionPosition.local(poiChunk.chunkPosition)
        delegate.writePreparedChunk(localChunkPosition) {
            poiChunkNbtCodec.encodeFromOkio(poiChunk, chunkNbtFormat, compression)
        }
    }

    suspend inline fun <reified T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(
        chunkPosition,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
        value,
        compression,
    )

    suspend fun <R> withReadScope(block: PoiRegionReadScope.() -> R): R = delegate.withReadScopeCore {
        block(PoiRegionReadScope(this, chunkNbtFormat))
    }

    suspend fun clear() = delegate.clear()

    suspend fun replaceRegion(block: RegionReplacementScope.() -> Unit) = delegate.replaceRegion(block)

    suspend fun flush() = delegate.flush()

    suspend fun close() = delegate.close()

    suspend fun <T> use(block: suspend (PoiRegionHandle) -> T): T =
        useSuspendingResource(this, PoiRegionHandle::close, block)
}

/** Caller-owned live POI Region resource. */
class LivePoiRegionHandle internal constructor(
    private val delegate: LiveRegionHandle,
) {
    private val poiChunkNbtCodec = PoiChunkNbtCodec(delegate.chunkNbtFormat.nbtFormat)

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

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? =
        delegate.readChunkInfo(localChunkPosition)

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = delegate.readChunkInfo(chunkPosition)

    fun readChunkInfos(): List<RegionChunkInfo> = delegate.readChunkInfos()

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withCompressedChunkSource(localChunkPosition, block)

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withCompressedChunkSource(chunkPosition, block)

    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(localChunkPosition, sink)

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readCompressedChunkTo(chunkPosition, sink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        delegate.readCompressedChunk(localChunkPosition)

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        delegate.readCompressedChunk(chunkPosition)

    fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withChunkNbtSource(localChunkPosition, block)

    fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = delegate.withChunkNbtSource(chunkPosition, block)

    fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(localChunkPosition, sink)

    fun readChunkNbtTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        delegate.readChunkNbtTo(chunkPosition, sink)

    fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? =
        delegate.readChunkNbtDocument(localChunkPosition)

    fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? = delegate.readChunkNbtDocument(chunkPosition)

    fun <T> readChunkNbt(
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = delegate.readChunkNbt(localChunkPosition, deserializationStrategy)

    fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = delegate.readChunkNbt(chunkPosition, deserializationStrategy)

    inline fun <reified T> readChunkNbt(localChunkPosition: LocalChunkPosition): T? =
        readChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    fun readChunk(localChunkPosition: LocalChunkPosition): PoiChunk? =
        withChunkNbtSource(localChunkPosition) { _, source ->
            poiChunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
        }

    fun readChunk(chunkPosition: ChunkPosition): PoiChunk? = readChunk(regionPosition.local(chunkPosition))

    fun <R> withReadScope(block: PoiRegionReadScope.() -> R): R = delegate.withReadScopeCore {
        block(PoiRegionReadScope(this, chunkNbtFormat))
    }

    fun close() = delegate.close()

    fun <T> use(block: (LivePoiRegionHandle) -> T): T =
        useResource(this, LivePoiRegionHandle::close, block)
}
