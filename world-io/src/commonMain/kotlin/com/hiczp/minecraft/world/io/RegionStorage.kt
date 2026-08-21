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
    internal val files: WorldFileAccess,
    val chunkNbtFormat: CompressedNbtFormat,
    val configuration: RegionStorageConfiguration,
) {
    init {
        require(!files.liveReadOnly) { "RegionStorage requires mutable file access" }
    }

    constructor(
        directory: Path,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        configuration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory = directory,
        files = WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    constructor(
        paths: MinecraftWorldPaths,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        configuration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory = paths.regionDirectory(storage, dimension),
        files = WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    internal constructor(
        paths: MinecraftWorldPaths,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        files: WorldFileAccess,
        chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
        configuration: RegionStorageConfiguration = RegionStorageConfiguration(),
    ) : this(
        directory = paths.regionDirectory(storage, dimension),
        files = files,
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

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
        return snapshotRegionPositions(files.fileSystem, directory)
    }

    /** Opens a caller-owned resource that keeps one Region entry alive between operations. */
    suspend fun openRegion(position: RegionPosition): RegionHandle = openRegion(position) { null }

    internal suspend fun openRegion(
        position: RegionPosition,
        afterRelease: suspend () -> Throwable?,
    ): RegionHandle = RegionHandle(this, acquire(position), afterRelease)

    /** Reads one complete in-memory snapshot without creating a missing Region file. */
    suspend fun readAnvilRegion(position: RegionPosition): AnvilRegion? = withRegionState(position, ::readAnvilRegion)

    suspend fun <R> withReadScope(position: RegionPosition, block: RegionReadScope.() -> R): R =
        withRegionState(position) { entry -> withReadScope(entry, block) }

    /** Replaces the complete logical Region with one batch header commit. */
    suspend fun replaceRegion(
        position: RegionPosition,
        region: AnvilRegion,
    ) = withRegionState(position) { entry ->
        replaceRegion(entry, region)
    }

    suspend fun replaceRegion(
        position: RegionPosition,
        chunks: Collection<RegionChunkInput>,
    ) = withRegionState(position) { entry ->
        replaceRegion(entry, chunks)
    }

    suspend fun replaceRegion(
        position: RegionPosition,
        block: RegionReplacementScope.() -> Unit,
    ) = withRegionState(position) { entry ->
        replaceRegion(entry, block)
    }

    suspend fun clear(position: RegionPosition) = withRegionState(position) { entry ->
        clear(entry)
    }

    suspend fun hasRegion(position: RegionPosition): Boolean = withRegionState(position) { entry ->
        hasRegion(entry)
    }

    suspend fun readChunkInfo(position: ChunkPosition): RegionChunkInfo? =
        readChunkInfo(position.region, position.local)

    suspend fun readChunkInfo(
        region: RegionPosition,
        local: LocalChunkPosition,
    ): RegionChunkInfo? = withRegionState(region) { entry -> readChunkInfo(entry, local) }

    suspend fun readChunkInfos(position: RegionPosition): List<RegionChunkInfo> =
        withRegionState(position, ::readChunkInfos)

    suspend fun readChunkCount(position: RegionPosition): Int =
        withRegionState(position, ::readChunkCount)

    suspend fun readLocalChunkPositions(position: RegionPosition): List<LocalChunkPosition> =
        withRegionState(position, ::readLocalChunkPositions)

    suspend fun hasChunk(position: ChunkPosition): Boolean = hasChunk(position.region, position.local)

    suspend fun hasChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
    ): Boolean = withRegionState(region) { entry -> hasChunk(entry, local) }

    suspend fun <R> withCompressedChunkSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(position.region, position.local, block)

    suspend fun <R> withCompressedChunkSource(
        region: RegionPosition,
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withRegionState(region) { entry -> withCompressedChunkSource(entry, local, block) }

    suspend fun readCompressedChunk(position: ChunkPosition): CompressedChunk? =
        readCompressedChunk(position.region, position.local)

    suspend fun readCompressedChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
    ): CompressedChunk? = withRegionState(region) { entry -> readCompressedChunk(entry, local) }

    /** Writes compressed content with automatic timestamp and inline/external selection. */
    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        chunk: CompressedChunkInput,
    ) = writeCompressedChunk(position.region, position.local, chunk)

    suspend fun writeCompressedChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        chunk: CompressedChunkInput,
    ) = withRegionState(region) { entry -> writeCompressedChunk(entry, local, chunk) }

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeCompressedChunk(position.region, position.local, compression, compressedByteCount, block)

    suspend fun writeCompressedChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = withRegionState(region) { entry ->
        writeCompressedChunk(entry, local, compression, compressedByteCount, block)
    }

    suspend fun removeChunk(position: ChunkPosition): Boolean = removeChunk(position.region, position.local)

    suspend fun removeChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
    ): Boolean = withRegionState(region) { entry -> removeChunk(entry, local) }

    suspend fun <R> withChunkNbtSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withChunkNbtSource(position.region, position.local, block)

    suspend fun <R> withChunkNbtSource(
        region: RegionPosition,
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withRegionState(region) { entry -> withChunkNbtSource(entry, local, block) }

    suspend fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(position.region, position.local)

    suspend fun readChunkNbtDocument(
        region: RegionPosition,
        local: LocalChunkPosition,
    ): NbtDocument? = withRegionState(region) { entry -> readChunkNbtDocument(entry, local) }

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = readChunkNbt(position.region, position.local, deserializer)

    suspend fun <T> readChunkNbt(
        region: RegionPosition,
        local: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withRegionState(region) { entry -> readChunkNbt(entry, local, deserializer) }

    suspend inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(
        region: RegionPosition,
        local: LocalChunkPosition,
    ): T? = readChunkNbt(region, local, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend fun <B : Any, M : Any> readChunk(
        position: ChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(position.region, position.local, codec)

    suspend fun <B : Any, M : Any> readChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withRegionState(region) { entry -> readChunk(entry, local, codec) }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbtDocument(
        position = position,
        document = document,
        compression = configuration.writeCompression,
    )

    suspend fun writeChunkNbtDocument(
        region: RegionPosition,
        local: LocalChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbtDocument(
        region = region,
        local = local,
        document = document,
        compression = configuration.writeCompression,
    )

    /** Encodes and writes one chunk with a per-write compression selection. */
    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = writeChunkNbtDocument(position.region, position.local, document, compression)

    suspend fun writeChunkNbtDocument(
        region: RegionPosition,
        local: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = withRegionState(region) { entry -> writeChunkNbtDocument(entry, local, document, compression) }

    suspend fun <T> writeChunkNbt(
        position: ChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(position.region, position.local, serializer, value, compression)

    suspend fun <T> writeChunkNbt(
        region: RegionPosition,
        local: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = withRegionState(region) { entry -> writeChunkNbt(entry, local, serializer, value, compression) }

    suspend inline fun <reified T> writeChunkNbt(
        position: ChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(
        position,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
    )

    suspend inline fun <reified T> writeChunkNbt(
        region: RegionPosition,
        local: LocalChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(
        region,
        local,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
    )

    suspend fun <B : Any, M : Any> writeChunk(
        position: ChunkPosition,
        chunk: Chunk<B, M>,
        codec: ChunkNbtCodec<B, M>,
        compression: Compression = configuration.writeCompression,
    ) = writeChunk(position.region, position.local, chunk, codec, compression)

    suspend fun <B : Any, M : Any> writeChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        chunk: Chunk<B, M>,
        codec: ChunkNbtCodec<B, M>,
        compression: Compression = configuration.writeCompression,
    ) = withRegionState(region) { entry -> writeChunk(entry, local, chunk, codec, compression) }

    internal suspend fun readAnvilRegion(entry: RegionState): AnvilRegion? = withReadAccess(entry) {
        openedFileForRead(entry)?.readAnvilRegion()
    }

    internal suspend fun <R> withReadScope(
        entry: RegionState,
        block: RegionReadScope.() -> R,
    ): R = withReadAccess(entry) {
        val file = openedFileForRead(entry)
        if (file == null) {
            RegionReadScope.empty(entry.position).use(block)
        } else {
            file.withReadScope(block)
        }
    }

    internal suspend fun replaceRegion(
        entry: RegionState,
        region: AnvilRegion,
    ) {
        files.requireWritable()
        region.chunks.forEach { (local, record) ->
            if (record.content == null) {
                throw AnvilFormatException("External Chunk ${entry.position.chunk(local)} has not been resolved")
            }
        }
        entry.fileAccess.write {
            openedFileForWrite(entry).replaceRegion(region)
        }
    }

    internal suspend fun replaceRegion(
        entry: RegionState,
        chunks: Collection<RegionChunkInput>,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedFileForWrite(entry).replaceRegion(chunks)
        }
    }

    internal suspend fun replaceRegion(
        entry: RegionState,
        block: RegionReplacementScope.() -> Unit,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedFileForWrite(entry).replaceRegion(block)
        }
    }

    internal suspend fun clear(entry: RegionState) {
        files.requireWritable()
        entry.fileAccess.write {
            openedFileForRead(entry)?.clear()
        }
    }

    internal suspend fun hasRegion(entry: RegionState): Boolean = withReadAccess(entry) {
        val path = regionPath(entry.position)
        val metadata = files.fileSystem.metadataOrNull(path) ?: return@withReadAccess false
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        true
    }

    internal suspend fun readChunkInfo(
        entry: RegionState,
        local: LocalChunkPosition,
    ): RegionChunkInfo? = withReadAccess(entry) {
        openedFileForRead(entry)?.readChunkInfo(local)
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
        local: LocalChunkPosition,
    ): CompressedChunk? = withReadAccess(entry) {
        openedFileForRead(entry)?.readCompressedChunk(local)
    }

    internal suspend fun <R> withCompressedChunkSource(
        entry: RegionState,
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withReadAccess(entry) {
        openedFileForRead(entry)?.withCompressedChunkSource(local, block)
    }

    internal suspend fun hasChunk(
        entry: RegionState,
        local: LocalChunkPosition,
    ): Boolean = withReadAccess(entry) {
        openedFileForRead(entry)?.hasChunk(local) == true
    }

    internal suspend fun writeCompressedChunk(
        entry: RegionState,
        local: LocalChunkPosition,
        chunk: CompressedChunkInput,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(local, chunk)
        }
    }

    internal suspend fun writeCompressedChunk(
        entry: RegionState,
        local: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(local, compression, compressedByteCount, block)
        }
    }

    internal suspend fun removeChunk(
        entry: RegionState,
        local: LocalChunkPosition,
    ): Boolean {
        files.requireWritable()
        return entry.fileAccess.write {
            openedFileForRead(entry)?.removeChunk(local) == true
        }
    }

    internal suspend fun <R> withChunkNbtSource(
        entry: RegionState,
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withReadAccess(entry) {
        openedFileForRead(entry)?.withCompressedChunkSource(local) { info, source ->
            withDecompressedChunkSource(info, source, block)
        }
    }

    internal suspend fun readChunkNbtDocument(
        entry: RegionState,
        local: LocalChunkPosition,
    ): NbtDocument? = withChunkNbtSource(entry, local) { _, source ->
        chunkNbtFormat.nbt.decodeDocumentFromSource(source)
    }

    internal suspend fun <T> readChunkNbt(
        entry: RegionState,
        local: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withChunkNbtSource(entry, local) { _, source ->
        chunkNbtFormat.nbt.decodeFromSource(deserializer, source)
    }

    internal suspend fun <B : Any, M : Any> readChunk(
        entry: RegionState,
        local: LocalChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withChunkNbtSource(entry, local) { _, source ->
        codec.decodeFromSource(source, entry.position.chunk(local))
    }

    internal suspend fun writeChunkNbtDocument(
        entry: RegionState,
        local: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) {
        files.requireWritable()
        val absolute = entry.position.chunk(local)
        val chunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            chunkNbtFormat.encodeDocument(document, compression)
        }
        entry.fileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(local, chunk)
        }
    }

    internal suspend fun writeChunkNbt(
        entry: RegionState,
        local: LocalChunkPosition,
        compression: Compression,
        block: (KotlinxSink) -> Unit,
    ) {
        files.requireWritable()
        val absolute = entry.position.chunk(local)
        val chunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            val compressed = Buffer()
            val compressing = chunkNbtFormat.compressionRegistry.compressingSink(compression, compressed).buffered()
            compressing.use(block)
            CompressedChunk.readFromSource(compressed, compression)
        }
        entry.fileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(local, chunk)
        }
    }

    internal suspend fun <T> writeChunkNbt(
        entry: RegionState,
        local: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression,
    ) {
        files.requireWritable()
        val absolute = entry.position.chunk(local)
        val chunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            chunkNbtFormat.encode(serializer, value, compression)
        }
        entry.fileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(local, chunk)
        }
    }

    internal suspend fun <B : Any, M : Any> writeChunk(
        entry: RegionState,
        local: LocalChunkPosition,
        chunk: Chunk<B, M>,
        codec: ChunkNbtCodec<B, M>,
        compression: Compression,
    ) {
        files.requireWritable()
        val absolute = entry.position.chunk(local)
        val compressed = withOkioIoExceptions("Cannot encode chunk $absolute") {
            val bytes = kotlinx.io.Buffer()
            val compressing = chunkNbtFormat.compressionRegistry.compressingSink(compression, bytes).buffered()
            compressing.use { codec.encodeToSink(chunk, absolute, compressing) }
            CompressedChunk.readFromSource(bytes, compression)
        }
        entry.fileAccess.write {
            openedFileForWrite(entry).writeCompressedChunk(local, compressed)
        }
    }

    private fun <R> withDecompressedChunkSource(
        info: RegionChunkInfo,
        source: KotlinxSource,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R {
        val decompressed = chunkNbtFormat.compressionRegistry
            .decompressingSource(info.compression, source)
            .buffered()
        return decompressed.use {
            val result = block(info, decompressed)
            if (!decompressed.exhausted()) {
                throw WorldIOException("Chunk ${info.position} NBT source was not fully consumed")
            }
            result
        }
    }

    internal suspend fun flush(entry: RegionState) {
        entry.fileAccess.write {
            entry.openMutex.withLock {
                entry.file?.flush()
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
                    entry.fileAccess.write {
                        entry.openMutex.withLock {
                            entry.file?.flush()
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

    internal suspend fun activeRegionUsers(position: RegionPosition): Int = bookkeeping.withLock {
        regions[position]?.users ?: 0
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

    private suspend fun acquire(position: RegionPosition): RegionState {
        while (true) {
            val closing = bookkeeping.withLock {
                checkOpen()
                val entry = regions[position]
                if (entry == null) {
                    val created = RegionState(position)
                    regions[position] = created
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
        position: RegionPosition,
        block: suspend (RegionState) -> T,
    ): T {
        val entry = acquire(position)
        return withCleanup(
            cleanup = { releaseRegion(entry) },
        ) {
            block(entry)
        }
    }

    internal suspend fun releaseRegion(entry: RegionState): Throwable? {
        val shouldClose = bookkeeping.withLock {
            check(entry.users > 0) { "Region entry is not in use: ${entry.position}" }
            check(!entry.closing) { "Region entry is already closing: ${entry.position}" }
            entry.users--
            if (entry.users > 0) return@withLock false
            entry.closing = true
            true
        }
        if (!shouldClose) return null
        val fileToClose = entry.openMutex.withLock {
            entry.file.also { entry.file = null }
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
            if (regions[entry.position] === entry) {
                regions.remove(entry.position)
            }
            entry.closed.complete(Unit)
            closeFailure?.let {
                if (closed) closeBarrierFailures += it
            }
        }
        return closeFailure
    }

    private suspend fun openedFileForRead(entry: RegionState): MutableRegionFile? = entry.openMutex.withLock {
        entry.file?.let { return@withLock it }
        val opened = MutableRegionFile.openExistingMutable(
            files = files,
            directory = directory,
            position = entry.position,
            syncWrites = configuration.syncWrites,
        ) ?: return@withLock null
        entry.file = opened
        opened
    }

    private suspend fun openedFileForWrite(entry: RegionState): MutableRegionFile = entry.openMutex.withLock {
        entry.file?.let { return@withLock it }
        val opened = MutableRegionFile.openMutable(
            files = files,
            directory = directory,
            position = entry.position,
            syncWrites = configuration.syncWrites,
        )
        entry.file = opened
        opened
    }

    private suspend fun <T> withReadAccess(
        entry: RegionState,
        block: suspend () -> T,
    ): T {
        return entry.fileAccess.read(block)
    }

    private fun checkOpen() {
        check(!closed) { "Region storage is closed: $directory" }
    }

    private fun regionPath(position: RegionPosition): Path =
        directory / "r.${position.x}.${position.z}.mca"

}

/**
 * A runtime path that needs both locks acquires [fileAccess] before [openMutex]. Final cleanup is
 * the only path that takes [openMutex] without [fileAccess]; it may do so only after bookkeeping
 * atomically moves [users] to zero and sets [closing]. Zero users excludes admitted runtime paths,
 * while closing redirects new acquisition to [closed]. Never acquire [fileAccess] while holding
 * [openMutex].
 */
internal class RegionState(val position: RegionPosition) {
    var file: MutableRegionFile? = null
    val openMutex = Mutex()
    val fileAccess = LogicalFileAccess()
    var users = 1
    var closing = false
    val closed = CompletableDeferred<Unit>()
}
