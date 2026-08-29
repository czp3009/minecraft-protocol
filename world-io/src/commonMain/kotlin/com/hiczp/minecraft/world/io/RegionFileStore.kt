package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path

/**
 * Stateless physical and format policy for one directory of `.mca` files and their `.mcc` sidecars.
 *
 * Every method owns all resources it opens and closes them before returning. Calls are deliberately
 * uncoordinated; callers that overlap operations on one Region must provide their own exclusion.
 */
class RegionFileStore internal constructor(
    val directory: Path,
    internal val worldFileAccess: WorldFileAccess,
    val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    val regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
) {
    constructor(
        directory: Path,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory,
        WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat,
        regionStorageConfiguration,
    )

    constructor(
        minecraftWorldPaths: MinecraftWorldPaths,
        dimensionId: DimensionId = DimensionId.Overworld,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        minecraftWorldPaths.regionDirectory(RegionStorageDirectory.CHUNKS, dimensionId),
        fileSystem,
        chunkNbtFormat,
        regionStorageConfiguration,
    )

    val fileSystem: FileSystem
        get() = worldFileAccess.fileSystem

    fun listRegionPositions(): List<RegionPosition> = snapshotRegionPositions(fileSystem, directory)

    fun hasRegion(regionPosition: RegionPosition): Boolean = withReadFile(regionPosition, ReadOnlyRegionFile::hasRegion)

    fun readChunkInfo(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): RegionChunkInfo? = withReadFile(regionPosition) { readOnlyRegionFile ->
        readOnlyRegionFile.readChunkInfo(localChunkPosition)
    }

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? =
        readChunkInfo(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    fun readChunkInfos(regionPosition: RegionPosition): List<RegionChunkInfo> =
        withReadFile(regionPosition, ReadOnlyRegionFile::readChunkInfos)

    fun readChunkCount(regionPosition: RegionPosition): Int =
        withReadFile(regionPosition, ReadOnlyRegionFile::readChunkCount)

    fun readLocalChunkPositions(regionPosition: RegionPosition): List<LocalChunkPosition> =
        withReadFile(regionPosition, ReadOnlyRegionFile::readLocalChunkPositions)

    fun readChunkPositions(regionPosition: RegionPosition): List<ChunkPosition> =
        readLocalChunkPositions(regionPosition).map(regionPosition::chunk)

    fun hasChunk(regionPosition: RegionPosition, localChunkPosition: LocalChunkPosition): Boolean =
        withReadFile(regionPosition) { readOnlyRegionFile -> readOnlyRegionFile.hasChunk(localChunkPosition) }

    fun hasChunk(chunkPosition: ChunkPosition): Boolean =
        hasChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    fun <R> withCompressedChunkSource(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withReadFile(regionPosition) { readOnlyRegionFile ->
        readOnlyRegionFile.withCompressedChunkSource(localChunkPosition, block)
    }

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withCompressedChunkSource(chunkPosition.regionPosition, chunkPosition.localChunkPosition, block)

    fun readCompressedChunkTo(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        sink: BufferedSink,
    ): RegionChunkInfo? = withCompressedChunkSource(regionPosition, localChunkPosition) { regionChunkInfo, source ->
        source.readAll(sink)
        regionChunkInfo
    }

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        readCompressedChunkTo(chunkPosition.regionPosition, chunkPosition.localChunkPosition, sink)

    fun readCompressedChunk(regionPosition: RegionPosition, localChunkPosition: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(regionPosition, localChunkPosition) { regionChunkInfo, source ->
            source.readCompressedChunkFromOkio(regionChunkInfo.compression)
        }

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        readCompressedChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    fun <R> withChunkNbtSource(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withCompressedChunkSource(regionPosition, localChunkPosition) { regionChunkInfo, source ->
        withDecompressedChunkSource(chunkNbtFormat, regionChunkInfo, source, block)
    }

    fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withChunkNbtSource(chunkPosition.regionPosition, chunkPosition.localChunkPosition, block)

    fun readChunkNbtTo(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        sink: BufferedSink,
    ): RegionChunkInfo? = withChunkNbtSource(regionPosition, localChunkPosition) { regionChunkInfo, source ->
        source.readAll(sink)
        regionChunkInfo
    }

    fun readChunkNbtTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        readChunkNbtTo(chunkPosition.regionPosition, chunkPosition.localChunkPosition, sink)

    fun readChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): NbtDocument? = withChunkNbtSource(regionPosition, localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeDocumentFromOkio(source)
    }

    fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    fun <T> readChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(regionPosition, localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeFromOkio(source, deserializationStrategy)
    }

    fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readChunkNbt(chunkPosition.regionPosition, chunkPosition.localChunkPosition, deserializationStrategy)

    inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): T? = readChunkNbt(
        regionPosition,
        localChunkPosition,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
    )

    fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withChunkNbtSource(chunkPosition) { _, source ->
        chunkNbtCodec.decodeFromOkio(source, chunkPosition)
    }

    fun <B : Any, M : Any> readChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(regionPosition.chunk(localChunkPosition), chunkNbtCodec)

    fun readAnvilRegion(regionPosition: RegionPosition): AnvilRegion? =
        withReadFile(regionPosition) { readOnlyRegionFile ->
            if (!readOnlyRegionFile.hasRegion()) return@withReadFile null
            readOnlyRegionFile.withReadScope {
                val chunks = linkedMapOf<LocalChunkPosition, AnvilChunkRecord>()
                chunkInfos.forEach { listedInfo ->
                    withCompressedChunkSource(listedInfo.localChunkPosition) { regionChunkInfo, source ->
                        chunks[regionChunkInfo.localChunkPosition] = AnvilChunkRecord(
                            compression = regionChunkInfo.compression,
                            content = source.readCompressedChunkFromOkio(regionChunkInfo.compression),
                            anvilChunkPlacement = regionChunkInfo.anvilChunkPlacement,
                            timestampEpochSeconds = regionChunkInfo.timestampEpochSeconds,
                        )
                    }
                }
                AnvilRegion(chunks)
            }
        }

    fun <R> withReadScope(regionPosition: RegionPosition, block: RegionReadScope.() -> R): R =
        withReadFile(regionPosition) { readOnlyRegionFile ->
            readOnlyRegionFile.withReadScope { block(RegionReadScope(this, chunkNbtFormat)) }
        }

    fun <B : Any, M : Any, R> withReadScope(
        regionPosition: RegionPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        block: DecodedChunkRegionReadScope<B, M>.() -> R,
    ): R = withReadFile(regionPosition) { readOnlyRegionFile ->
        readOnlyRegionFile.withReadScope {
            block(DecodedChunkRegionReadScope(this, chunkNbtFormat, chunkNbtCodec))
        }
    }

    fun <R> withEntityReadScope(regionPosition: RegionPosition, block: EntityRegionReadScope.() -> R): R =
        withReadFile(regionPosition) { readOnlyRegionFile ->
            readOnlyRegionFile.withReadScope { block(EntityRegionReadScope(this, chunkNbtFormat)) }
        }

    fun <E : Any, R> withEntityReadScope(
        regionPosition: RegionPosition,
        entityChunkNbtCodec: EntityChunkNbtCodec<E>,
        block: DecodedEntityRegionReadScope<E>.() -> R,
    ): R = withReadFile(regionPosition) { readOnlyRegionFile ->
        readOnlyRegionFile.withReadScope {
            block(DecodedEntityRegionReadScope(this, chunkNbtFormat, entityChunkNbtCodec))
        }
    }

    fun <R> withPoiReadScope(regionPosition: RegionPosition, block: PoiRegionReadScope.() -> R): R =
        withReadFile(regionPosition) { readOnlyRegionFile ->
            readOnlyRegionFile.withReadScope { block(PoiRegionReadScope(this, chunkNbtFormat)) }
        }

    fun writeCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) = withWriteFile(regionPosition) { mutableRegionFile ->
        mutableRegionFile.writeCompressedChunk(localChunkPosition, compressedChunkInput)
    }

    fun writeCompressedChunk(chunkPosition: ChunkPosition, compressedChunkInput: CompressedChunkInput) =
        writeCompressedChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition, compressedChunkInput)

    fun writeCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (BufferedSink) -> Unit,
    ) = withWriteFile(regionPosition) { mutableRegionFile ->
        mutableRegionFile.writeCompressedChunk(localChunkPosition, compression, compressedByteCount, block)
    }

    fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (BufferedSink) -> Unit,
    ) = writeCompressedChunk(
        chunkPosition.regionPosition,
        chunkPosition.localChunkPosition,
        compression,
        compressedByteCount,
        block,
    )

    fun removeChunk(regionPosition: RegionPosition, localChunkPosition: LocalChunkPosition): Boolean =
        withExistingWriteFile(regionPosition) { mutableRegionFile ->
            mutableRegionFile.removeChunk(localChunkPosition)
        } ?: false

    fun removeChunk(chunkPosition: ChunkPosition): Boolean =
        removeChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeCompressedChunk(
        regionPosition,
        localChunkPosition,
        chunkNbtFormat.encodeDocumentFromOkio(nbtDocument, compression),
    )

    fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbtDocument(
        chunkPosition.regionPosition,
        chunkPosition.localChunkPosition,
        nbtDocument,
        compression,
    )

    fun writeChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (BufferedSink) -> Unit,
    ) = writeCompressedChunk(
        regionPosition,
        localChunkPosition,
        encodeCompressedChunkFromOkio(chunkNbtFormat, compression, block),
    )

    fun writeChunkNbt(
        chunkPosition: ChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (BufferedSink) -> Unit,
    ) = writeChunkNbt(
        chunkPosition.regionPosition,
        chunkPosition.localChunkPosition,
        compression,
        block,
    )

    fun <T> writeChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
        serializationStrategy: SerializationStrategy<T>,
    ) = writeCompressedChunk(
        regionPosition,
        localChunkPosition,
        chunkNbtFormat.encodeFromOkio(value, compression, serializationStrategy),
    )

    fun <T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
        serializationStrategy: SerializationStrategy<T>,
    ) = writeChunkNbt(
        chunkPosition.regionPosition,
        chunkPosition.localChunkPosition,
        value,
        compression,
        serializationStrategy,
    )

    inline fun <reified T> writeChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(
        regionPosition,
        localChunkPosition,
        value,
        compression,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
    )

    inline fun <reified T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(
        chunkPosition,
        value,
        compression,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
    )

    fun <B : Any, M : Any> writeChunk(
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeCompressedChunk(
        chunk.chunkPosition,
        chunkNbtCodec.encodeFromOkio(chunk, chunkNbtFormat, compression),
    )

    fun replaceRegion(regionPosition: RegionPosition, anvilRegion: AnvilRegion) {
        anvilRegion.chunks.forEach { (localChunkPosition, anvilChunkRecord) ->
            if (anvilChunkRecord.content == null) {
                throw AnvilFormatException(
                    "External Chunk ${regionPosition.chunk(localChunkPosition)} has not been resolved",
                )
            }
        }
        withWriteFile(regionPosition) { mutableRegionFile -> mutableRegionFile.replaceRegion(anvilRegion) }
    }

    fun replaceRegion(regionPosition: RegionPosition, block: RegionReplacementScope.() -> Unit) =
        withWriteFile(regionPosition) { mutableRegionFile -> mutableRegionFile.replaceRegion(block) }

    fun clear(regionPosition: RegionPosition) {
        withExistingWriteFile(regionPosition, MutableRegionFile::clear)
    }

    internal fun openReadOnly(regionPosition: RegionPosition): ReadOnlyRegionFile =
        ReadOnlyRegionFile.open(worldFileAccess, directory, regionPosition)

    internal fun openMutable(regionPosition: RegionPosition): MutableRegionFile = MutableRegionFile.openMutable(
        worldFileAccess,
        directory,
        regionPosition,
        regionStorageConfiguration.syncWrites,
    )

    internal fun openExistingMutable(regionPosition: RegionPosition): MutableRegionFile? =
        MutableRegionFile.openExistingMutable(
            worldFileAccess,
            directory,
            regionPosition,
            regionStorageConfiguration.syncWrites,
        )

    private fun <R> withReadFile(regionPosition: RegionPosition, block: (ReadOnlyRegionFile) -> R): R =
        useResource(openReadOnly(regionPosition), ReadOnlyRegionFile::close, block)

    private fun <R> withWriteFile(regionPosition: RegionPosition, block: (MutableRegionFile) -> R): R {
        worldFileAccess.requireWritable()
        return useResource(openMutable(regionPosition), MutableRegionFile::close, block)
    }

    private fun <R> withExistingWriteFile(
        regionPosition: RegionPosition,
        block: (MutableRegionFile) -> R,
    ): R? {
        worldFileAccess.requireWritable()
        val mutableRegionFile = openExistingMutable(regionPosition) ?: return null
        return useResource(mutableRegionFile, MutableRegionFile::close, block)
    }
}
