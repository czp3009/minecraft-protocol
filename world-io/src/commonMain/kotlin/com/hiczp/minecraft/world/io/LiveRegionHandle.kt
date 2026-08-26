package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import okio.*
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Stateless, non-locking read access to one logical live Region.
 *
 * Opening this handle performs no filesystem I/O and the handle itself owns no resource or close
 * lifecycle. Every method independently opens and closes the physical files it needs. Another
 * process may write, delete, or replace those files concurrently, so a call may observe stale or
 * torn input and propagate the resulting I/O or format failure.
 */
class LiveRegionHandle internal constructor(
    private val fileSystem: FileSystem,
    private val directory: Path,
    val regionPosition: RegionPosition,
    val chunkNbtFormat: CompressedNbtFormat,
) {
    fun hasRegion(): Boolean {
        val path = regionFilePath(directory, regionPosition)
        val fileMetadata = fileSystem.metadataOrNull(path) ?: return false
        if (!fileMetadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        return true
    }

    /** Whether the Region index contains [localChunkPosition], without reading Chunk record metadata. */
    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean =
        withOpenRegion { _, regionHeader -> regionHeader.hasChunk(localChunkPosition) } ?: false

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(local(chunkPosition))

    fun hasChunk(blockPosition: BlockPosition): Boolean = hasChunk(blockPosition.chunkPosition)

    /** Reads the number of occupied Region header entries without reading Chunk record metadata. */
    fun readChunkCount(): Int = withOpenRegion { _, regionHeader -> regionHeader.chunkCount } ?: 0

    /** Reads a detached list of occupied Region-local positions in header order. */
    fun readLocalChunkPositions(): List<LocalChunkPosition> =
        withOpenRegion { _, regionHeader -> regionHeader.localChunkPositions().toList() }.orEmpty()

    /** Reads occupied absolute Chunk positions in Region header order. */
    fun readChunkPositions(): List<ChunkPosition> = readLocalChunkPositions().map(regionPosition::chunk)

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? =
        withOpenRegion { fileHandle, regionHeader ->
            readRegionChunkInfo(fileSystem, directory, regionPosition, fileHandle, regionHeader, localChunkPosition)
        }

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = readChunkInfo(local(chunkPosition))

    /** Reads detached stored metadata for every resolvable Chunk record in Region-local order. */
    fun readChunkInfos(): List<RegionChunkInfo> =
        withOpenRegion { fileHandle, regionHeader ->
            buildList {
                for (index in 0 until REGION_CHUNK_COUNT) {
                    val localChunkPosition = LocalChunkPosition.fromIndex(index)
                    readRegionChunkInfo(fileSystem, directory, regionPosition, fileHandle, regionHeader, localChunkPosition)?.let(::add)
                }
            }
        }.orEmpty()

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withOpenRegion { fileHandle, regionHeader ->
        val regionChunkInfo =
            readRegionChunkInfo(fileSystem, directory, regionPosition, fileHandle, regionHeader, localChunkPosition)
            ?: return@withOpenRegion null
        if (regionChunkInfo.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL) {
            withExternalChunkSource(regionChunkInfo, block)
        } else {
            val regionLocation = regionHeader.location(localChunkPosition) ?: return@withOpenRegion null
            val bufferedSource = fileHandle.source(
                regionLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
            ).limit(regionChunkInfo.compressedByteCount).buffer()
            useResource(bufferedSource, { it.close() }) {
                readPayload(regionChunkInfo, bufferedSource, block)
            }
        }
    }

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(local(chunkPosition), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [kotlinxSink]. */
    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.transferTo(kotlinxSink)
            regionChunkInfo
        }

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        readCompressedChunkTo(local(chunkPosition), kotlinxSink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            CompressedChunk.readFromSource(source, regionChunkInfo.compression)
        }

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? = readCompressedChunk(local(chunkPosition))

    fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
        val decompressed = chunkNbtFormat.compressionRegistry
            .decompressingSource(regionChunkInfo.compression, source)
            .buffered()
        decompressed.use {
            val result = block(regionChunkInfo, decompressed)
            if (!decompressed.exhausted()) {
                throw WorldIOException("Chunk ${regionChunkInfo.chunkPosition} NBT source was not fully consumed")
            }
            result
        }
    }

    fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withChunkNbtSource(local(chunkPosition), block)

    /** Copies one complete decompressed unnamed-root Chunk NBT stream without closing [kotlinxSink]. */
    fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        withChunkNbtSource(localChunkPosition) { regionChunkInfo, source ->
            source.transferTo(kotlinxSink)
            regionChunkInfo
        }

    fun readChunkNbtTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        readChunkNbtTo(local(chunkPosition), kotlinxSink)

    fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? =
        withChunkNbtSource(localChunkPosition) { _, source -> chunkNbtFormat.nbtFormat.decodeDocumentFromSource(source) }

    fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? = readChunkNbtDocument(local(chunkPosition))

    fun <T> readChunkNbt(
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeFromSource(deserializationStrategy, source)
    }

    fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readChunkNbt(local(chunkPosition), deserializationStrategy)

    inline fun <reified T> readChunkNbt(localChunkPosition: LocalChunkPosition): T? =
        readChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    fun <B : Any, M : Any> readChunk(
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withChunkNbtSource(localChunkPosition) { _, source ->
        chunkNbtCodec.decodeFromSource(source, regionPosition.chunk(localChunkPosition))
    }

    fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(local(chunkPosition), chunkNbtCodec)

    fun <B : Any, M : Any> readChunk(
        blockPosition: BlockPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(blockPosition.chunkPosition, chunkNbtCodec)

    private fun <R> withOpenRegion(block: (FileHandle, RegionHeader) -> R): R? {
        val path = regionFilePath(directory, regionPosition)
        val fileMetadata = fileSystem.metadataOrNull(path) ?: return null
        if (!fileMetadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val fileHandle = fileSystem.openLiveReadOnly(path)
        return useResource(fileHandle, { it.close() }) {
            block(fileHandle, readUsableHeader(fileHandle))
        }
    }

    private fun <R> withExternalChunkSource(
        regionChunkInfo: RegionChunkInfo,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R {
        val path = externalChunkPath(directory, regionPosition, regionChunkInfo.localChunkPosition)
        val fileMetadata = fileSystem.metadataOrNull(path)
            ?: throw WorldIOException("External Chunk file does not exist: $path")
        if (!fileMetadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val byteCount = fileMetadata.size
            ?: throw WorldIOException("External Chunk file has no size: $path")
        if (byteCount < 0L) {
            throw WorldIOException("External Chunk file has a negative size: $path")
        }

        val fileHandle = fileSystem.openLiveReadOnly(path)
        return useResource(fileHandle, { it.close() }) {
            val bufferedSource = fileHandle.source().limit(byteCount, throwIfSourceIsLonger = true).buffer()
            useResource(bufferedSource, { it.close() }) {
                readPayload(regionChunkInfo.copy(compressedByteCount = byteCount), bufferedSource, block)
            }
        }
    }

    private fun <R> readPayload(
        regionChunkInfo: RegionChunkInfo,
        bufferedSource: BufferedSource,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R = withOkioIoExceptions("Cannot read Chunk ${regionChunkInfo.localChunkPosition} payload") {
        val converted = bufferedSource.asKotlinxIoRawSource().buffered()
        val value = block(regionChunkInfo, converted)
        if (!converted.exhausted()) {
            throw WorldIOException("Chunk ${regionChunkInfo.localChunkPosition} payload was not fully consumed")
        }
        value
    }

    private fun local(chunkPosition: ChunkPosition): LocalChunkPosition = this.regionPosition.local(chunkPosition)
}
