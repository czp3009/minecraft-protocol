package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Configuration for one region directory. The caller—not this library—decides how many region files
 * are accessed concurrently.
 */
data class WorldRegionStoreConfiguration(
    val syncWrites: Boolean = true,
    /** Default used by NBT writes that do not select compression per chunk. */
    val writeCompression: Compression = Compression.ZLIB,
)

/**
 * One mutable vanilla-style region storage directory for one dimension.
 *
 * Reads of the same `.mca` file may run concurrently. A write has exclusive access to that file and
 * waits for its admitted readers, while different files may progress independently. Admission is
 * writer-preferring but not fair or FIFO among same-kind waiters. NBT encoding happens before
 * exclusive file access; streaming reads and NBT decoding retain shared file access so a writer
 * cannot replace their sectors mid-read. Region entries and handles exist only while operations are
 * in flight; the last operation for a file closes that file. These suspend functions wait only for
 * coordination; blocking filesystem I/O and compression run on the calling coroutine's dispatcher
 * and are not automatically main-safe.
 */
class WorldRegionStore internal constructor(
    val directory: Path,
    internal val files: WorldFileAccess,
    val chunkNbtFormat: RegionChunkNbtFormat,
    val configuration: WorldRegionStoreConfiguration,
) {
    init {
        require(!files.liveReadOnly) { "WorldRegionStore requires mutable file access" }
    }

    constructor(
        directory: Path,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
        configuration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
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
        chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
        configuration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
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
        chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
        configuration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
    ) : this(
        directory = paths.regionDirectory(storage, dimension),
        files = files,
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

    private val bookkeeping = Mutex()
    private val regions = mutableMapOf<RegionPosition, RegionEntry>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    /** Reads one complete in-memory snapshot without reading unrelated files. */
    suspend fun readRegion(position: RegionPosition): RegionFile =
        withRegionEntry(position) { entry ->
            withReadAccess(entry) {
                openedStore(entry).readAll()
            }
        }

    suspend fun readChunk(position: ChunkPosition): RegionChunk? =
        withRegionEntry(position.region) { entry ->
            withReadAccess(entry) {
                openedStore(entry).read(position)
            }
        }

    suspend fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T? = withRegionEntry(position.region) { entry ->
        withReadAccess(entry) {
            openedStore(entry).read(position, block)
        }
    }

    suspend fun doesChunkExist(position: ChunkPosition): Boolean =
        withRegionEntry(position.region) { entry ->
            withReadAccess(entry) {
                openedStore(entry).exists(position)
            }
        }

    /**
     * Writes compressed chunk data with an automatic timestamp and automatic internal/external
     * selection. The input payload marker and timestamp are representation details and do not
     * control the filesystem commit.
     */
    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
    ) = withRegionEntry(position.region) { entry ->
        files.requireWritable()
        if (chunk != null) {
            validatedCompressedPayload(position, chunk)
        }
        entry.fileAccess.write {
            openedStore(entry).write(position, chunk)
        }
    }

    suspend fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: BufferedSink.() -> Unit,
    ) = withRegionEntry(position.region) { entry ->
        files.requireWritable()
        entry.fileAccess.write {
            openedStore(entry).write(position, compression, compressedLength, block)
        }
    }

    suspend fun clearChunk(position: ChunkPosition) {
        writeChunk(position, null)
    }

    suspend fun readChunkNbt(position: ChunkPosition): NbtDocument? =
        withRegionEntry(position.region) { entry ->
            withReadAccess(entry) {
                openedStore(entry).read(position) { info, source ->
                    withOkioIoExceptions("Cannot decode chunk $position") {
                        val converted = source.asKotlinxIoRawSource().buffered()
                        chunkNbtFormat.decodeFromSource(converted, info.compression)
                    }
                }
            }
        }

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbt(
        position = position,
        document = document,
        compression = configuration.writeCompression,
    )

    /** Encodes and writes one chunk with a per-write compression selection. */
    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = withRegionEntry(position.region) { entry ->
        files.requireWritable()
        val chunk = withOkioIoExceptions("Cannot encode chunk $position") {
            chunkNbtFormat.encode(document, compression)
        }
        entry.fileAccess.write {
            openedStore(entry).write(position, chunk)
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
                        entry.store?.flush()
                    }
                } catch (caught: Throwable) {
                    entryFailure = caught
                }
            }
            entryFailure = collectCleanupFailure(entryFailure) { release(entry) }
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

    private suspend fun acquire(position: RegionPosition): RegionEntry {
        while (true) {
            val closing = bookkeeping.withLock {
                checkOpen()
                val entry = regions[position]
                if (entry == null) {
                    val created = RegionEntry(position)
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

    private suspend fun <T> withRegionEntry(
        position: RegionPosition,
        block: suspend (RegionEntry) -> T,
    ): T {
        val entry = acquire(position)
        return withCleanup(
            cleanup = { release(entry) },
        ) {
            block(entry)
        }
    }

    private suspend fun release(entry: RegionEntry): Throwable? {
        val shouldClose = bookkeeping.withLock {
            check(entry.users > 0) { "Region entry is not in use: ${entry.position}" }
            check(!entry.closing) { "Region entry is already closing: ${entry.position}" }
            entry.users--
            if (entry.users > 0) return@withLock false
            entry.closing = true
            true
        }
        if (!shouldClose) return null
        val storeToClose = entry.openMutex.withLock {
            entry.store.also { entry.store = null }
        }
        var closeFailure: Throwable? = null
        if (storeToClose != null) {
            try {
                storeToClose.close()
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

    private suspend fun openedStore(entry: RegionEntry): RegionFileStore = entry.openMutex.withLock {
        entry.store?.let { return@withLock it }
        val opened = RegionFileStore.open(
            files = files,
            directory = directory,
            position = entry.position,
            syncWrites = configuration.syncWrites,
        )
        entry.store = opened
        opened
    }

    private suspend fun <T> withReadAccess(
        entry: RegionEntry,
        block: suspend () -> T,
    ): T {
        return entry.fileAccess.read(block)
    }

    private fun checkOpen() {
        check(!closed) { "Region store is closed: $directory" }
    }

    /**
     * A runtime path that needs both locks acquires [fileAccess] before [openMutex]. Final cleanup is
     * the only path that takes [openMutex] without [fileAccess]; it may do so only after bookkeeping
     * atomically moves [users] to zero and sets [closing]. Zero users excludes admitted runtime
     * paths, while closing redirects new acquisition to [closed]. Never acquire [fileAccess] while
     * holding [openMutex].
     */
    private class RegionEntry(val position: RegionPosition) {
        var store: RegionFileStore? = null
        val openMutex = Mutex()
        val fileAccess = LogicalFileAccess()
        var users = 1
        var closing = false
        val closed = CompletableDeferred<Unit>()
    }
}
