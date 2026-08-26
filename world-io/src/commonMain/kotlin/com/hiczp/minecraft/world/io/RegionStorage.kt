package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Configuration for one Region directory. The caller—not this library—decides how many Regions
 * are accessed concurrently.
 */
data class RegionStorageConfiguration(
    val syncWrites: Boolean = true,
    /** Default used by NBT writes that do not select compression per chunk. */
    val writeCompression: Compression = Compression.ZLIB,
)

/**
 * One mutable vanilla-style region storage directory for one dimension.
 *
 * Reads of the same logical Region may run concurrently. A write has exclusive access to that Region
 * and waits for its admitted readers, while different Regions may progress independently. Admission is
 * writer-preferring but not fair or FIFO among same-kind waiters. NBT encoding happens before
 * exclusive file access; streaming reads and NBT decoding retain shared file access so a writer
 * cannot replace their content mid-read. Region states and handles exist only while internal one-shot
 * operations or caller-owned [RegionHandle] resources retain them; the last user closes the physical resources.
 * These suspend functions wait only for coordination; blocking filesystem I/O and compression run
 * on the calling coroutine's dispatcher and are not automatically main-safe.
 */
internal class RegionStorage internal constructor(
    val directory: Path,
    internal val worldFileAccess: WorldFileAccess,
    val chunkNbtFormat: CompressedNbtFormat,
    val regionStorageConfiguration: RegionStorageConfiguration,
) {
    init {
        require(!worldFileAccess.liveReadOnly) { "RegionStorage requires mutable file access" }
    }

    constructor(
        directory: Path,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory = directory,
        worldFileAccess = WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat = chunkNbtFormat,
        regionStorageConfiguration = regionStorageConfiguration,
    )

    constructor(
        minecraftWorldPaths: MinecraftWorldPaths,
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory = minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionDirectory),
        worldFileAccess = WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat = chunkNbtFormat,
        regionStorageConfiguration = regionStorageConfiguration,
    )

    internal constructor(
        minecraftWorldPaths: MinecraftWorldPaths,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        worldFileAccess: WorldFileAccess,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory = minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionDirectory),
        worldFileAccess = worldFileAccess,
        chunkNbtFormat = chunkNbtFormat,
        regionStorageConfiguration = regionStorageConfiguration,
    )

    val fileSystem: FileSystem
        get() = worldFileAccess.fileSystem

    private val bookkeeping = Mutex()
    private val regions = mutableMapOf<RegionPosition, RegionState>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    suspend fun listRegionPositions(): List<RegionPosition> {
        bookkeeping.withLock {
            check(!closed) { "Region storage is closed: $directory" }
        }
        return snapshotRegionPositions(worldFileAccess.fileSystem, directory)
    }

    /** Opens a caller-owned resource that keeps one Region entry alive between operations. */
    suspend fun openRegion(regionPosition: RegionPosition): RegionHandle = openRegion(regionPosition) { null }

    internal suspend fun openRegion(
        regionPosition: RegionPosition,
        afterRelease: suspend () -> Throwable?,
    ): RegionHandle = RegionHandle(this, acquire(regionPosition), afterRelease)

    /** Reads one complete in-memory snapshot without creating a missing Region file. */
    suspend fun readAnvilRegion(regionPosition: RegionPosition): PositionedAnvilRegion? =
        withRegionState(regionPosition, ::readAnvilRegion)

    suspend fun <R> withReadScope(regionPosition: RegionPosition, block: RegionReadScope.() -> R): R =
        withRegionState(regionPosition) { entry -> withReadScope(entry, block) }

    /** Replaces the complete logical Region with one batch header commit. */
    suspend fun replaceRegion(
        regionPosition: RegionPosition,
        anvilRegion: AnvilRegion,
    ) = withRegionState(regionPosition) { entry ->
        replaceRegion(entry, anvilRegion)
    }

    suspend fun replaceRegion(
        regionPosition: RegionPosition,
        chunks: Collection<RegionChunkInput>,
    ) = withRegionState(regionPosition) { entry ->
        replaceRegion(entry, chunks)
    }

    suspend fun replaceRegion(
        regionPosition: RegionPosition,
        block: RegionReplacementScope.() -> Unit,
    ) = withRegionState(regionPosition) { entry ->
        replaceRegion(entry, block)
    }

    suspend fun clear(regionPosition: RegionPosition) = withRegionState(regionPosition) { entry ->
        clear(entry)
    }

    suspend fun hasRegion(regionPosition: RegionPosition): Boolean = withRegionState(regionPosition) { entry ->
        hasRegion(entry)
    }

    suspend fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? =
        readChunkInfo(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    suspend fun readChunkInfo(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): RegionChunkInfo? = withRegionState(regionPosition) { entry -> readChunkInfo(entry, localChunkPosition) }

    suspend fun readChunkInfos(regionPosition: RegionPosition): List<RegionChunkInfo> =
        withRegionState(regionPosition, ::readChunkInfos)

    suspend fun readChunkCount(regionPosition: RegionPosition): Int =
        withRegionState(regionPosition, ::readChunkCount)

    suspend fun readLocalChunkPositions(regionPosition: RegionPosition): List<LocalChunkPosition> =
        withRegionState(regionPosition, ::readLocalChunkPositions)

    suspend fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    suspend fun hasChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): Boolean = withRegionState(regionPosition) { entry -> hasChunk(entry, localChunkPosition) }

    suspend fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(chunkPosition.regionPosition, chunkPosition.localChunkPosition, block)

    suspend fun <R> withCompressedChunkSource(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withRegionState(regionPosition) { entry -> withCompressedChunkSource(entry, localChunkPosition, block) }

    suspend fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? =
        readCompressedChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    suspend fun readCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): CompressedChunk? = withRegionState(regionPosition) { entry -> readCompressedChunk(entry, localChunkPosition) }

    /** Writes compressed content with automatic timestamp and inline/external selection. */
    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) = writeCompressedChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition, compressedChunkInput)

    suspend fun writeCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) = withRegionState(regionPosition) { entry -> writeCompressedChunk(entry, localChunkPosition, compressedChunkInput) }

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeCompressedChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition, compression, compressedByteCount, block)

    suspend fun writeCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = withRegionState(regionPosition) { entry ->
        writeCompressedChunk(entry, localChunkPosition, compression, compressedByteCount, block)
    }

    suspend fun removeChunk(chunkPosition: ChunkPosition): Boolean = removeChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    suspend fun removeChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): Boolean = withRegionState(regionPosition) { entry -> removeChunk(entry, localChunkPosition) }

    suspend fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withChunkNbtSource(chunkPosition.regionPosition, chunkPosition.localChunkPosition, block)

    suspend fun <R> withChunkNbtSource(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withRegionState(regionPosition) { entry -> withChunkNbtSource(entry, localChunkPosition, block) }

    suspend fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(chunkPosition.regionPosition, chunkPosition.localChunkPosition)

    suspend fun readChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): NbtDocument? = withRegionState(regionPosition) { entry -> readChunkNbtDocument(entry, localChunkPosition) }

    suspend fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readChunkNbt(chunkPosition.regionPosition, chunkPosition.localChunkPosition, deserializationStrategy)

    suspend fun <T> readChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withRegionState(regionPosition) { entry -> readChunkNbt(entry, localChunkPosition, deserializationStrategy) }

    suspend inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
    ): T? = readChunkNbt(regionPosition, localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition, chunkNbtCodec)

    suspend fun <B : Any, M : Any> readChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withRegionState(regionPosition) { entry -> readChunk(entry, localChunkPosition, chunkNbtCodec) }

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
    ) = writeChunkNbtDocument(
        chunkPosition = chunkPosition,
        nbtDocument = nbtDocument,
        compression = regionStorageConfiguration.writeCompression,
    )

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
    ) = writeChunkNbtDocument(
        regionPosition = regionPosition,
        localChunkPosition = localChunkPosition,
        nbtDocument = nbtDocument,
        compression = regionStorageConfiguration.writeCompression,
    )

    /** Encodes and writes one chunk with a per-write compression selection. */
    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) = writeChunkNbtDocument(chunkPosition.regionPosition, chunkPosition.localChunkPosition, nbtDocument, compression)

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) = withRegionState(regionPosition) { entry -> writeChunkNbtDocument(entry, localChunkPosition, nbtDocument, compression) }

    suspend fun <T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(chunkPosition.regionPosition, chunkPosition.localChunkPosition, serializationStrategy, value, compression)

    suspend fun <T> writeChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = withRegionState(regionPosition) { entry -> writeChunkNbt(entry, localChunkPosition, serializationStrategy, value, compression) }

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

    suspend inline fun <reified T> writeChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(
        regionPosition,
        localChunkPosition,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
        value,
        compression,
    )

    suspend fun <B : Any, M : Any> writeChunk(
        chunkPosition: ChunkPosition,
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunk(chunkPosition.regionPosition, chunkPosition.localChunkPosition, chunk, chunkNbtCodec, compression)

    suspend fun <B : Any, M : Any> writeChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = withRegionState(regionPosition) { entry -> writeChunk(entry, localChunkPosition, chunk, chunkNbtCodec, compression) }

    internal suspend fun readAnvilRegion(entry: RegionState): PositionedAnvilRegion? = withReadAccess(entry) {
        openedFileForRead(entry)?.readAnvilRegion()?.let { anvilRegion -> PositionedAnvilRegion(entry.regionPosition, anvilRegion) }
    }

    internal suspend fun <R> withReadScope(
        entry: RegionState,
        block: RegionReadScope.() -> R,
    ): R = withReadAccess(entry) {
        val mutableRegionFile = openedFileForRead(entry)
        if (mutableRegionFile == null) {
            RegionReadScope.empty(entry.regionPosition).use(block)
        } else {
            mutableRegionFile.withReadScope(block)
        }
    }

    internal suspend fun replaceRegion(
        entry: RegionState,
        anvilRegion: AnvilRegion,
    ) {
        worldFileAccess.requireWritable()
        anvilRegion.chunks.forEach { (localChunkPosition, anvilChunkRecord) ->
            if (anvilChunkRecord.content == null) {
                throw AnvilFormatException("External Chunk ${entry.regionPosition.chunk(localChunkPosition)} has not been resolved")
            }
        }
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).replaceRegion(anvilRegion)
        }
    }

    internal suspend fun replaceRegion(
        entry: RegionState,
        chunks: Collection<RegionChunkInput>,
    ) {
        worldFileAccess.requireWritable()
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).replaceRegion(chunks)
        }
    }

    internal suspend fun replaceRegion(
        entry: RegionState,
        block: RegionReplacementScope.() -> Unit,
    ) {
        worldFileAccess.requireWritable()
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).replaceRegion(block)
        }
    }

    internal suspend fun clear(entry: RegionState) {
        worldFileAccess.requireWritable()
        entry.logicalFileAccess.write {
            openedFileForRead(entry)?.clear()
        }
    }

    internal suspend fun hasRegion(entry: RegionState): Boolean = withReadAccess(entry) {
        val path = regionPath(entry.regionPosition)
        val fileMetadata = worldFileAccess.fileSystem.metadataOrNull(path) ?: return@withReadAccess false
        if (!fileMetadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        true
    }

    internal suspend fun readChunkInfo(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
    ): RegionChunkInfo? = withReadAccess(entry) {
        openedFileForRead(entry)?.readChunkInfo(localChunkPosition)
    }

    internal suspend fun readChunkInfos(entry: RegionState): List<RegionChunkInfo> = withReadAccess(entry) {
        openedFileForRead(entry)?.readChunkInfos().orEmpty()
    }

    internal suspend fun readChunkCount(entry: RegionState): Int = withReadAccess(entry) {
        openedFileForRead(entry)?.readChunkCount() ?: 0
    }

    internal suspend fun readLocalChunkPositions(entry: RegionState): List<LocalChunkPosition> = withReadAccess(entry) {
        openedFileForRead(entry)?.readLocalChunkPositions().orEmpty()
    }

    internal suspend fun readCompressedChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
    ): CompressedChunk? = withReadAccess(entry) {
        openedFileForRead(entry)?.readCompressedChunk(localChunkPosition)
    }

    internal suspend fun <R> withCompressedChunkSource(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withReadAccess(entry) {
        openedFileForRead(entry)?.withCompressedChunkSource(localChunkPosition, block)
    }

    internal suspend fun hasChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
    ): Boolean = withReadAccess(entry) {
        openedFileForRead(entry)?.hasChunk(localChunkPosition) == true
    }

    internal suspend fun writeCompressedChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) {
        worldFileAccess.requireWritable()
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(localChunkPosition, compressedChunkInput)
        }
    }

    internal suspend fun writeCompressedChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        worldFileAccess.requireWritable()
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(localChunkPosition, compression, compressedByteCount, block)
        }
    }

    internal suspend fun removeChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
    ): Boolean {
        worldFileAccess.requireWritable()
        return entry.logicalFileAccess.write {
            openedFileForRead(entry)?.removeChunk(localChunkPosition) == true
        }
    }

    internal suspend fun <R> withChunkNbtSource(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withReadAccess(entry) {
        openedFileForRead(entry)?.withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            withDecompressedChunkSource(regionChunkInfo, source, block)
        }
    }

    internal suspend fun readChunkNbtDocument(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
    ): NbtDocument? = withChunkNbtSource(entry, localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeDocumentFromSource(source)
    }

    internal suspend fun <T> readChunkNbt(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(entry, localChunkPosition) { _, source ->
        chunkNbtFormat.nbtFormat.decodeFromSource(deserializationStrategy, source)
    }

    internal suspend fun <B : Any, M : Any> readChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withChunkNbtSource(entry, localChunkPosition) { _, source ->
        chunkNbtCodec.decodeFromSource(source, entry.regionPosition.chunk(localChunkPosition))
    }

    internal suspend fun writeChunkNbtDocument(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) {
        worldFileAccess.requireWritable()
        val absolute = entry.regionPosition.chunk(localChunkPosition)
        val compressedChunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            chunkNbtFormat.encodeDocument(nbtDocument, compression)
        }
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(localChunkPosition, compressedChunk)
        }
    }

    internal suspend fun writeChunkNbt(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        block: (KotlinxSink) -> Unit,
    ) {
        worldFileAccess.requireWritable()
        val absolute = entry.regionPosition.chunk(localChunkPosition)
        val compressedChunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            val compressed = Buffer()
            val compressing = chunkNbtFormat.compressionRegistry.compressingSink(compression, compressed).buffered()
            compressing.use(block)
            CompressedChunk.readFromSource(compressed, compression)
        }
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(localChunkPosition, compressedChunk)
        }
    }

    internal suspend fun <T> writeChunkNbt(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression,
    ) {
        worldFileAccess.requireWritable()
        val absolute = entry.regionPosition.chunk(localChunkPosition)
        val compressedChunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            chunkNbtFormat.encode(serializationStrategy, value, compression)
        }
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(localChunkPosition, compressedChunk)
        }
    }

    internal suspend fun <B : Any, M : Any> writeChunk(
        entry: RegionState,
        localChunkPosition: LocalChunkPosition,
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression,
    ) {
        worldFileAccess.requireWritable()
        val absolute = entry.regionPosition.chunk(localChunkPosition)
        require(chunk.chunkPosition == absolute) {
            "Chunk position ${chunk.chunkPosition} does not match Region entry $absolute"
        }
        val compressedChunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            val bytes = kotlinx.io.Buffer()
            val compressing = chunkNbtFormat.compressionRegistry.compressingSink(compression, bytes).buffered()
            compressing.use { chunkNbtCodec.encodeToSink(chunk, compressing) }
            CompressedChunk.readFromSource(bytes, compression)
        }
        entry.logicalFileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(localChunkPosition, compressedChunk)
        }
    }

    private fun <R> withDecompressedChunkSource(
        regionChunkInfo: RegionChunkInfo,
        kotlinxSource: KotlinxSource,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R {
        val decompressed = chunkNbtFormat.compressionRegistry
            .decompressingSource(regionChunkInfo.compression, kotlinxSource)
            .buffered()
        return decompressed.use {
            val result = block(regionChunkInfo, decompressed)
            if (!decompressed.exhausted()) {
                throw WorldIOException("Chunk ${regionChunkInfo.chunkPosition} NBT source was not fully consumed")
            }
            result
        }
    }

    internal suspend fun flush(entry: RegionState) {
        entry.logicalFileAccess.write {
            entry.openMutex.withLock {
                entry.mutableRegionFile?.flush()
            }
        }
    }

    suspend fun flush() {
        val pinned = bookkeeping.withLock {
            checkOpen()
            regions.values.filterNot { it.closing }.onEach { it.users++ }
        }
        if (pinned.isEmpty()) return

        var failure: Throwable? = null
        pinned.forEach { entry ->
            var entryFailure: Throwable? = null
            // Every entry was pinned as one snapshot. After cancellation, skip further flush work
            // but still release every pin through non-cancellable cleanup.
            if (failure !is CancellationException) {
                try {
                    entry.logicalFileAccess.write {
                        entry.openMutex.withLock {
                            entry.mutableRegionFile?.flush()
                        }
                    }
                } catch (caught: Throwable) {
                    entryFailure = caught
                }
            }
            entryFailure = collectCleanupFailure(entryFailure) { releaseRegion(entry) }
            entryFailure?.let { caught ->
                failure = combineFailures(failure, caught)
            }
        }
        throwFailureOrCancellation(failure)
    }

    /**
     * Seals new admission and waits for every admitted operation and final entry cleanup.
     *
     * Final-entry cleanup runs synchronously before its last operation returns, so that operation
     * observes any cleanup failure. A failure finalized before this close begins is not replayed.
     * If this close has already sealed admission and is waiting for that cleanup, every current or
     * later caller of the same close barrier observes its final failure as well.
     */
    suspend fun close() {
        val completion: CompletableDeferred<Unit>
        val owner: Boolean
        bookkeeping.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                owner = false
            } else {
                checkOpen()
                closed = true
                completion = CompletableDeferred()
                closeCompletion = completion
                owner = true
            }
        }
        if (owner) {
            firstClose(completion)
        } else {
            if (!completion.isCompleted) completion.await()
            closeFailure?.let { throw it }
        }
    }

    internal suspend fun activeRegionCount(): Int = bookkeeping.withLock {
        regions.size
    }

    internal suspend fun activeRegionUsers(regionPosition: RegionPosition): Int = bookkeeping.withLock {
        regions[regionPosition]?.users ?: 0
    }

    private suspend fun firstClose(completion: CompletableDeferred<Unit>) {
        val failure: Throwable? = withContext(NonCancellable) {
            val entries = bookkeeping.withLock {
                regions.values.toList()
            }
            entries.map { it.closed }.awaitAll()
            val failure = bookkeeping.withLock {
                val result = closeBarrierFailures.reduceOrNull { current, caught ->
                    combineFailures(current, caught)
                }
                closeFailure = result
                result
            }
            completion.complete(Unit)
            failure
        }
        // The cleanup itself must finish even if its owner is cancelled. Observe that cancellation
        // only after the shared close result has been finalized for every current and later caller.
        throwFailureOrCancellation(failure)
    }

    private suspend fun acquire(regionPosition: RegionPosition): RegionState {
        while (true) {
            val closing = bookkeeping.withLock {
                checkOpen()
                val entry = regions[regionPosition]
                if (entry == null) {
                    val created = RegionState(regionPosition)
                    regions[regionPosition] = created
                    return created
                }
                if (!entry.closing) {
                    entry.users++
                    return entry
                }
                entry.closed
            }
            closing.await()
        }
    }

    private suspend fun <T> withRegionState(
        regionPosition: RegionPosition,
        block: suspend (RegionState) -> T,
    ): T {
        val entry = acquire(regionPosition)
        return withCleanup(
            cleanup = { releaseRegion(entry) },
        ) {
            block(entry)
        }
    }

    internal suspend fun releaseRegion(entry: RegionState): Throwable? {
        val shouldClose = bookkeeping.withLock {
            check(entry.users > 0) { "Region entry is not in use: ${entry.regionPosition}" }
            check(!entry.closing) { "Region entry is already closing: ${entry.regionPosition}" }
            entry.users--
            if (entry.users > 0) return@withLock false
            entry.closing = true
            true
        }
        if (!shouldClose) return null
        val fileToClose = entry.openMutex.withLock {
            entry.mutableRegionFile.also { entry.mutableRegionFile = null }
        }
        var closeFailure: Throwable? = null
        if (fileToClose != null) {
            try {
                fileToClose.close()
            } catch (caught: Throwable) {
                closeFailure = caught
            }
        }
        bookkeeping.withLock {
            if (regions[entry.regionPosition] === entry) {
                regions.remove(entry.regionPosition)
            }
            entry.closed.complete(Unit)
            closeFailure?.let {
                if (closed) closeBarrierFailures += it
            }
        }
        return closeFailure
    }

    private suspend fun openedFileForRead(entry: RegionState): MutableRegionFile? = entry.openMutex.withLock {
        entry.mutableRegionFile?.let { return@withLock it }
        val opened = MutableRegionFile.openExistingMutable(
            worldFileAccess = worldFileAccess,
            directory = directory,
            regionPosition = entry.regionPosition,
            syncWrites = regionStorageConfiguration.syncWrites,
        ) ?: return@withLock null
        entry.mutableRegionFile = opened
        opened
    }

    private suspend fun openedFileForWrite(entry: RegionState): MutableRegionFile = entry.openMutex.withLock {
        entry.mutableRegionFile?.let { return@withLock it }
        val opened = MutableRegionFile.openMutable(
            worldFileAccess = worldFileAccess,
            directory = directory,
            regionPosition = entry.regionPosition,
            syncWrites = regionStorageConfiguration.syncWrites,
        )
        entry.mutableRegionFile = opened
        opened
    }

    private suspend fun <T> withReadAccess(
        entry: RegionState,
        block: suspend () -> T,
    ): T {
        return entry.logicalFileAccess.read(block)
    }

    private fun checkOpen() {
        check(!closed) { "Region storage is closed: $directory" }
    }

    private fun regionPath(regionPosition: RegionPosition): Path =
        directory / "r.${regionPosition.x}.${regionPosition.z}.mca"

}

/**
 * A runtime path that needs both locks acquires [logicalFileAccess] before [openMutex]. Final cleanup is
 * the only path that takes [openMutex] without [logicalFileAccess]; it may do so only after bookkeeping
 * atomically moves [users] to zero and sets [closing]. Zero users excludes admitted runtime paths,
 * while closing redirects new acquisition to [closed]. Never acquire [logicalFileAccess] while holding
 * [openMutex].
 */
internal class RegionState(val regionPosition: RegionPosition) {
    var mutableRegionFile: MutableRegionFile? = null
    val openMutex = Mutex()
    val logicalFileAccess = LogicalFileAccess()
    var users = 1
    var closing = false
    val closed = CompletableDeferred<Unit>()
}
