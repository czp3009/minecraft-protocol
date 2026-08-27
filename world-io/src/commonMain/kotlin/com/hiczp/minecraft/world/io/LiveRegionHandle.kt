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
 * Caller-owned, non-locking read access to one live Region.
 *
 * The handle independently opens and retains the `.mca` file found when it is created; it shares
 * neither that resource nor lifecycle state with other handles. Ordinary operations reread the
 * Region header, while [withReadScope] reuses one header read for its callback. External `.mcc`
 * sidecars are still opened only for the Chunk operation that needs them.
 *
 * None of this provides a filesystem snapshot. Another process may change, delete, or replace a
 * path or overwrite sectors at any time, so operations may observe stale or torn combinations and
 * propagate the resulting I/O, Anvil, compression, or NBT failure. Methods may run concurrently,
 * but [close] must be called only after their callbacks and concurrent calls have returned. Use
 * [use] or call [close] explicitly to release the handle.
 */
class LiveRegionHandle internal constructor(
    fileSystem: FileSystem,
    directory: Path,
    val regionPosition: RegionPosition,
    val chunkNbtFormat: CompressedNbtFormat,
) {
    private val liveRegionFile = LiveRegionFile.open(fileSystem, directory, regionPosition)

    fun hasRegion(): Boolean = liveRegionFile.hasRegion()

    /** Whether the Region index contains [localChunkPosition], without reading Chunk record metadata. */
    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = liveRegionFile.hasChunk(localChunkPosition)

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(local(chunkPosition))

    fun hasChunk(blockPosition: BlockPosition): Boolean = hasChunk(blockPosition.chunkPosition)

    /** Reads the number of occupied Region header entries without reading Chunk record metadata. */
    fun readChunkCount(): Int = liveRegionFile.readChunkCount()

    /** Reads a detached list of occupied Region-local positions in header order. */
    fun readLocalChunkPositions(): List<LocalChunkPosition> = liveRegionFile.readLocalChunkPositions()

    /** Reads occupied absolute Chunk positions in Region header order. */
    fun readChunkPositions(): List<ChunkPosition> = readLocalChunkPositions().map(regionPosition::chunk)

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? =
        liveRegionFile.readChunkInfo(localChunkPosition)

    fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = readChunkInfo(local(chunkPosition))

    /** Reads detached stored metadata for every resolvable Chunk record in Region-local order. */
    fun readChunkInfos(): List<RegionChunkInfo> = liveRegionFile.readChunkInfos()

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = liveRegionFile.withCompressedChunkSource(localChunkPosition, block)

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

    /**
     * Runs [block] with one cached Region header and the handle's retained `.mca` resource.
     *
     * The header is read once for efficiency only. It may already be torn or stale, and the Chunk
     * records or external sidecars it references may change independently while [block] runs.
     */
    fun <R> withReadScope(block: RegionReadScope.() -> R): R = liveRegionFile.withReadScope(block)

    fun close() = liveRegionFile.close()

    /** Runs [block] and closes this independently owned live Region handle afterward. */
    fun <T> use(block: (LiveRegionHandle) -> T): T =
        useResource(this, LiveRegionHandle::close, block)

    private fun local(chunkPosition: ChunkPosition): LocalChunkPosition = this.regionPosition.local(chunkPosition)
}

/** One independently owned live `.mca` handle; no registry or reference count shares it. */
private class LiveRegionFile private constructor(
    private val fileSystem: FileSystem,
    private val directory: Path,
    override val regionPosition: RegionPosition,
    override val path: Path,
    private val fileHandle: FileHandle?,
) : RegionReadAccess {
    private var closed = false

    fun hasRegion(): Boolean {
        checkOpen()
        return fileHandle != null
    }

    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean {
        checkOpen()
        val regionHeader = headerForRead() ?: return false
        return hasChunk(localChunkPosition, regionHeader)
    }

    override fun hasChunk(localChunkPosition: LocalChunkPosition, regionHeader: RegionHeader): Boolean {
        checkOpen()
        return fileHandle != null && regionHeader.hasChunk(localChunkPosition)
    }

    fun readChunkCount(): Int {
        checkOpen()
        return headerForRead()?.chunkCount ?: 0
    }

    fun readLocalChunkPositions(): List<LocalChunkPosition> {
        checkOpen()
        return headerForRead()?.localChunkPositions()?.toList().orEmpty()
    }

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? {
        checkOpen()
        val regionHeader = headerForRead() ?: return null
        return readChunkInfo(localChunkPosition, regionHeader)
    }

    fun readChunkInfos(): List<RegionChunkInfo> = withReadScope { chunkInfos.toList() }

    override fun readChunkInfo(
        localChunkPosition: LocalChunkPosition,
        regionHeader: RegionHeader,
    ): RegionChunkInfo? {
        checkOpen()
        val fileHandle = fileHandle ?: return null
        return readRegionChunkInfo(
            fileSystem,
            directory,
            regionPosition,
            fileHandle,
            regionHeader,
            localChunkPosition,
        )
    }

    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        checkOpen()
        val regionHeader = headerForRead() ?: return null
        return withCompressedChunkSource(localChunkPosition, regionHeader, block)
    }

    override fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        regionHeader: RegionHeader,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        checkOpen()
        val fileHandle = fileHandle ?: return null
        val regionChunkInfo = readChunkInfo(localChunkPosition, regionHeader) ?: return null
        return if (regionChunkInfo.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL) {
            withExternalChunkSource(regionChunkInfo, block)
        } else {
            val regionLocation = regionHeader.location(localChunkPosition) ?: return null
            val bufferedSource = fileHandle.source(
                regionLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
            ).limit(regionChunkInfo.compressedByteCount).buffer()
            useResource(bufferedSource, { it.close() }) {
                readPayload(regionChunkInfo, bufferedSource, block)
            }
        }
    }

    fun <R> withReadScope(block: RegionReadScope.() -> R): R {
        checkOpen()
        val fileHandle = fileHandle ?: return RegionReadScope.empty(regionPosition).use(block)
        return RegionReadScope(this, readUsableHeader(fileHandle)).use(block)
    }

    fun close() {
        if (closed) return
        closed = true
        fileHandle?.close()
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

    private fun headerForRead(): RegionHeader? = fileHandle?.let(::readUsableHeader)

    private fun checkOpen() {
        check(!closed) { "Live Region handle is closed: $path" }
    }

    companion object {
        fun open(
            fileSystem: FileSystem,
            directory: Path,
            regionPosition: RegionPosition,
        ): LiveRegionFile {
            val path = regionFilePath(directory, regionPosition)
            val fileMetadata = fileSystem.metadataOrNull(path)
            if (fileMetadata != null && !fileMetadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
            return LiveRegionFile(
                fileSystem = fileSystem,
                directory = directory,
                regionPosition = regionPosition,
                path = path,
                fileHandle = if (fileMetadata == null) null else fileSystem.openLiveReadOnly(path),
            )
        }
    }
}
