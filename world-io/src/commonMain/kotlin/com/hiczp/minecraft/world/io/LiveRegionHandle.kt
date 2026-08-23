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
    val position: RegionPosition,
    val chunkNbtFormat: CompressedNbtFormat,
) {
    fun hasRegion(): Boolean {
        val path = regionFilePath(directory, position)
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        return true
    }

    /** Whether the Region index contains [local], without reading Chunk record metadata. */
    fun hasChunk(local: LocalChunkPosition): Boolean =
        withOpenRegion { _, header -> header.hasChunk(local) } ?: false

    fun hasChunk(position: ChunkPosition): Boolean = hasChunk(local(position))

    fun hasChunk(position: BlockPosition): Boolean = hasChunk(position.chunk)

    /** Reads the number of occupied Region header entries without reading Chunk record metadata. */
    fun readChunkCount(): Int = withOpenRegion { _, header -> header.chunkCount } ?: 0

    /** Reads a detached list of occupied Region-local positions in header order. */
    fun readLocalChunkPositions(): List<LocalChunkPosition> =
        withOpenRegion { _, header -> header.localChunkPositions().toList() }.orEmpty()

    /** Reads occupied absolute Chunk positions in Region header order. */
    fun readChunkPositions(): List<ChunkPosition> = readLocalChunkPositions().map(position::chunk)

    fun readChunkInfo(local: LocalChunkPosition): RegionChunkInfo? =
        withOpenRegion { handle, header ->
            readRegionChunkInfo(fileSystem, directory, position, handle, header, local)
        }

    fun readChunkInfo(position: ChunkPosition): RegionChunkInfo? = readChunkInfo(local(position))

    /** Reads detached stored metadata for every resolvable Chunk record in Region-local order. */
    fun readChunkInfos(): List<RegionChunkInfo> =
        withOpenRegion { handle, header ->
            buildList {
                for (index in 0 until REGION_CHUNK_COUNT) {
                    val local = LocalChunkPosition.fromIndex(index)
                    readRegionChunkInfo(fileSystem, directory, position, handle, header, local)?.let(::add)
                }
            }
        }.orEmpty()

    fun <R> withCompressedChunkSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withOpenRegion { handle, header ->
        val info = readRegionChunkInfo(fileSystem, directory, position, handle, header, local)
            ?: return@withOpenRegion null
        if (info.placement == AnvilChunkPlacement.EXTERNAL) {
            withExternalChunkSource(info, block)
        } else {
            val location = header.location(local) ?: return@withOpenRegion null
            val source = handle.source(
                location.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
            ).limit(info.compressedByteCount).buffer()
            useResource(source, { it.close() }) {
                readPayload(info, source, block)
            }
        }
    }

    fun <R> withCompressedChunkSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(local(position), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    fun readCompressedChunkTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        withCompressedChunkSource(local) { info, source ->
            source.transferTo(sink)
            info
        }

    fun readCompressedChunkTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        readCompressedChunkTo(local(position), sink)

    fun readCompressedChunk(local: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(local) { info, source ->
            CompressedChunk.readFromSource(source, info.compression)
        }

    fun readCompressedChunk(position: ChunkPosition): CompressedChunk? = readCompressedChunk(local(position))

    fun <R> withChunkNbtSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(local) { info, source ->
        val decompressed = chunkNbtFormat.compressionRegistry
            .decompressingSource(info.compression, source)
            .buffered()
        decompressed.use {
            val result = block(info, decompressed)
            if (!decompressed.exhausted()) {
                throw WorldIOException("Chunk ${info.position} NBT source was not fully consumed")
            }
            result
        }
    }

    fun <R> withChunkNbtSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withChunkNbtSource(local(position), block)

    /** Copies one complete decompressed unnamed-root Chunk NBT stream without closing [sink]. */
    fun readChunkNbtTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        withChunkNbtSource(local) { info, source ->
            source.transferTo(sink)
            info
        }

    fun readChunkNbtTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        readChunkNbtTo(local(position), sink)

    fun readChunkNbtDocument(local: LocalChunkPosition): NbtDocument? =
        withChunkNbtSource(local) { _, source -> chunkNbtFormat.nbt.decodeDocumentFromSource(source) }

    fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? = readChunkNbtDocument(local(position))

    fun <T> readChunkNbt(
        local: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(local) { _, source ->
        chunkNbtFormat.nbt.decodeFromSource(deserializer, source)
    }

    fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = readChunkNbt(local(position), deserializer)

    inline fun <reified T> readChunkNbt(local: LocalChunkPosition): T? =
        readChunkNbt(local, chunkNbtFormat.nbt.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    fun <B : Any, M : Any> readChunk(
        local: LocalChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withChunkNbtSource(local) { _, source ->
        codec.decodeFromSource(source, position.chunk(local))
    }

    fun <B : Any, M : Any> readChunk(
        position: ChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(local(position), codec)

    fun <B : Any, M : Any> readChunk(
        position: BlockPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(position.chunk, codec)

    private fun <R> withOpenRegion(block: (FileHandle, RegionHeader) -> R): R? {
        val path = regionFilePath(directory, position)
        val metadata = fileSystem.metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val handle = fileSystem.openLiveReadOnly(path)
        return useResource(handle, { it.close() }) {
            block(handle, readUsableHeader(handle))
        }
    }

    private fun <R> withExternalChunkSource(
        info: RegionChunkInfo,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R {
        val path = externalChunkPath(directory, position, info.localPosition)
        val metadata = fileSystem.metadataOrNull(path)
            ?: throw WorldIOException("External Chunk file does not exist: $path")
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val byteCount = metadata.size
            ?: throw WorldIOException("External Chunk file has no size: $path")
        if (byteCount < 0L) {
            throw WorldIOException("External Chunk file has a negative size: $path")
        }

        val handle = fileSystem.openLiveReadOnly(path)
        return useResource(handle, { it.close() }) {
            val source = handle.source().limit(byteCount, throwIfSourceIsLonger = true).buffer()
            useResource(source, { it.close() }) {
                readPayload(info.copy(compressedByteCount = byteCount), source, block)
            }
        }
    }

    private fun <R> readPayload(
        info: RegionChunkInfo,
        source: BufferedSource,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R = withOkioIoExceptions("Cannot read Chunk ${info.localPosition} payload") {
        val converted = source.asKotlinxIoRawSource().buffered()
        val value = block(info, converted)
        if (!converted.exhausted()) {
            throw WorldIOException("Chunk ${info.localPosition} payload was not fully consumed")
        }
        value
    }

    private fun local(position: ChunkPosition): LocalChunkPosition = this.position.local(position)
}
