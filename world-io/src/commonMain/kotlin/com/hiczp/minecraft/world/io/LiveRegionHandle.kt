package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import okio.*

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
class LiveRegionHandle private constructor(
    val regionPosition: RegionPosition,
    val chunkNbtFormat: CompressedNbtFormat,
    private val liveRegionFile: ReadOnlyRegionFile,
) {
    internal constructor(
        fileSystem: FileSystem,
        directory: Path,
        regionPosition: RegionPosition,
        chunkNbtFormat: CompressedNbtFormat,
    ) : this(
        regionPosition,
        chunkNbtFormat,
        ReadOnlyRegionFile.open(WorldFileAccess.liveReadOnly(fileSystem), directory, regionPosition),
    )

    internal constructor(regionFileStore: RegionFileStore, regionPosition: RegionPosition) : this(
        regionPosition,
        regionFileStore.chunkNbtFormat,
        regionFileStore.openReadOnly(regionPosition),
    )

    fun hasRegion(): Boolean = liveRegionFile.hasRegion()

    /** Whether the Region index contains [localChunkPosition], without reading Chunk record metadata. */
    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = liveRegionFile.hasChunk(localChunkPosition)

    fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(local(chunkPosition))

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
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = liveRegionFile.withCompressedChunkSource(localChunkPosition, block)

    fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withCompressedChunkSource(local(chunkPosition), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.readAll(sink)
            regionChunkInfo
        }

    fun readCompressedChunkTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        readCompressedChunkTo(local(chunkPosition), sink)

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.readCompressedChunkFromOkio(regionChunkInfo.compression)
        }

    fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? = readCompressedChunk(local(chunkPosition))

    fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
        withDecompressedChunkSource(chunkNbtFormat, regionChunkInfo, source, block)
    }

    fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? = withChunkNbtSource(local(chunkPosition), block)

    /** Copies one complete decompressed unnamed-root Chunk NBT stream without closing [sink]. */
    fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        withChunkNbtSource(localChunkPosition) { regionChunkInfo, source ->
            source.readAll(sink)
            regionChunkInfo
        }

    fun readChunkNbtTo(chunkPosition: ChunkPosition, sink: BufferedSink): RegionChunkInfo? =
        readChunkNbtTo(local(chunkPosition), sink)

    fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? =
        withChunkNbtSource(localChunkPosition) { _, source -> chunkNbtFormat.nbtFormat.decodeDocumentFromOkio(source) }

    fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? = readChunkNbtDocument(local(chunkPosition))

    fun <T> readChunkNbt(
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeFromOkio(source, deserializationStrategy)
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
        chunkNbtCodec.decodeFromOkio(source, regionPosition.chunk(localChunkPosition))
    }

    fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(local(chunkPosition), chunkNbtCodec)

    /**
     * Runs [block] with one cached Region header and the handle's retained `.mca` resource.
     *
     * The header is read once for efficiency only. It may already be torn or stale, and the Chunk
     * records or external sidecars it references may change independently while [block] runs. The
     * typed scope can decode semantic Chunks without rereading that Header.
     */
    fun <R> withReadScope(block: RegionReadScope.() -> R): R = withReadScopeCore {
        block(RegionReadScope(this, chunkNbtFormat))
    }

    internal fun <R> withReadScopeCore(block: RegionReadScopeCore.() -> R): R = liveRegionFile.withReadScope(block)

    fun close() = liveRegionFile.close()

    /** Runs [block] and closes this independently owned live Region handle afterward. */
    fun <T> use(block: (LiveRegionHandle) -> T): T =
        useResource(this, LiveRegionHandle::close, block)

    private fun local(chunkPosition: ChunkPosition): LocalChunkPosition = this.regionPosition.local(chunkPosition)
}

/** One independently owned live `.mca` handle; no registry or reference count shares it. */
internal class ReadOnlyRegionFile private constructor(
    private val worldFileAccess: WorldFileAccess,
    private val directory: Path,
    override val regionPosition: RegionPosition,
    override val path: Path,
    private val fileHandle: FileHandle?,
) : RegionReadAccess {
    private val fileSystem: FileSystem
        get() = worldFileAccess.fileSystem

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
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R? {
        checkOpen()
        val regionHeader = headerForRead() ?: return null
        return withCompressedChunkSource(localChunkPosition, regionHeader, block)
    }

    override fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        regionHeader: RegionHeader,
        block: (RegionChunkInfo, BufferedSource) -> R,
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

    fun <R> withReadScope(block: RegionReadScopeCore.() -> R): R {
        checkOpen()
        val fileHandle = fileHandle ?: return RegionReadScopeCore.empty(regionPosition).use(block)
        return RegionReadScopeCore(this, readUsableHeader(fileHandle)).use(block)
    }

    fun close() {
        if (closed) return
        closed = true
        fileHandle?.close()
    }

    private fun <R> withExternalChunkSource(
        regionChunkInfo: RegionChunkInfo,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R {
        val path = externalChunkPath(directory, regionPosition, regionChunkInfo.localChunkPosition)
        return worldFileAccess.readFileAtKnownSize(path, regionChunkInfo.compressedByteCount) { bufferedSource ->
            block(regionChunkInfo, bufferedSource)
        }
    }

    private fun <R> readPayload(
        regionChunkInfo: RegionChunkInfo,
        bufferedSource: BufferedSource,
        block: (RegionChunkInfo, BufferedSource) -> R,
    ): R {
        val value = block(regionChunkInfo, bufferedSource)
        if (!bufferedSource.exhausted()) {
            throw WorldIOException("Chunk ${regionChunkInfo.localChunkPosition} payload was not fully consumed")
        }
        return value
    }

    private fun headerForRead(): RegionHeader? = fileHandle?.let(::readUsableHeader)

    private fun checkOpen() {
        check(!closed) { "Live Region handle is closed: $path" }
    }

    companion object {
        fun open(
            worldFileAccess: WorldFileAccess,
            directory: Path,
            regionPosition: RegionPosition,
        ): ReadOnlyRegionFile {
            val fileSystem = worldFileAccess.fileSystem
            val path = regionFilePath(directory, regionPosition)
            val fileMetadata = fileSystem.metadataOrNull(path)
            if (fileMetadata != null && !fileMetadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
            return ReadOnlyRegionFile(
                worldFileAccess = worldFileAccess,
                directory = directory,
                regionPosition = regionPosition,
                path = path,
                fileHandle = if (fileMetadata == null) null else worldFileAccess.openReadOnlyRegionHandle(path),
            )
        }
    }
}
