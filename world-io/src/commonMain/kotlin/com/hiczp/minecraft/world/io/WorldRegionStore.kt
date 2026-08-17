package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Configuration for one region directory. The caller—not this library—decides how many region files
 * are accessed concurrently.
 */
data class WorldRegionStoreConfiguration(
    val maximumCompressedChunkBytes: Int = 256 * 1_048_576,
    val syncWrites: Boolean = true,
    /** Default used by NBT writes that do not select compression per chunk. */
    val writeCompression: Compression = Compression.ZLIB,
) {
    init {
        require(maximumCompressedChunkBytes >= 0)
    }
}

/**
 * One mutable vanilla-style region storage directory for one dimension.
 *
 * Reads of the same `.mca` file may run concurrently. A write has exclusive access to that file and
 * waits for its admitted readers, while different files may progress independently. NBT encoding
 * and decoding happens outside exclusive file access. Region entries and handles exist only while
 * operations are in flight; the last operation for a file closes that file. These suspend functions
 * wait only for coordination; blocking filesystem I/O and compression run on the calling
 * coroutine's dispatcher and are not automatically main-safe.
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
    private val cleanupFailures = mutableListOf<Throwable>()

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
            validatedCompressedPayload(
                position = position,
                chunk = chunk,
                maximumCompressedChunkBytes = configuration.maximumCompressedChunkBytes,
            )
        }
        entry.fileAccess.write {
            openedStore(entry).write(position, chunk)
        }
    }

    suspend fun clearChunk(position: ChunkPosition) {
        writeChunk(position, null)
    }

    suspend fun readChunkNbt(position: ChunkPosition): NbtDocument? =
        withRegionEntry(position.region) { entry ->
            val chunk = withReadAccess(entry) {
                openedStore(entry).read(position)
            }
            if (chunk == null) return@withRegionEntry null
            withOkioIoExceptions("Cannot decode chunk $position") {
                chunkNbtFormat.decode(chunk)
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
        validatedCompressedPayload(
            position = position,
            chunk = chunk,
            maximumCompressedChunkBytes = configuration.maximumCompressedChunkBytes,
        )
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
            try {
                entry.fileAccess.write {
                    entry.store?.flush()
                }
            } catch (caught: Throwable) {
                entryFailure = caught
            }
            try {
                release(entry)
            } catch (releaseFailure: Throwable) {
                entryFailure = combineFailures(entryFailure, releaseFailure)
            }
            entryFailure?.let { caught ->
                failure = combineFailures(failure, caught)
            }
        }
        failure?.let { throw it }
    }

    suspend fun close() {
        val completion: CompletableDeferred<Unit>
        val concurrentWait: Boolean
        bookkeeping.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                concurrentWait = !existing.isCompleted
            } else {
                checkOpen()
                closed = true
                completion = CompletableDeferred()
                closeCompletion = completion
                concurrentWait = false
            }
        }
        if (concurrentWait) {
            completion.await()
            closeFailure?.let { throw it }
            return
        }
        if (completion.isCompleted) return
        firstClose(completion)
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
            entries.map { it.drained }.awaitAll()
            entries.map { it.closed }.awaitAll()
            val failure = bookkeeping.withLock {
                cleanupFailures.reduceOrNull { current, caught ->
                    combineFailures(current, caught)
                }
            }
            closeFailure = failure
            completion.complete(Unit)
            failure
        }
        failure?.let { throw it }
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
        var operationFailure: Throwable? = null
        try {
            return block(entry)
        } catch (caught: Throwable) {
            operationFailure = caught
            throw caught
        } finally {
            try {
                release(entry)
            } catch (releaseFailure: Throwable) {
                val current = operationFailure ?: throw releaseFailure
                if (current !== releaseFailure) {
                    current.addSuppressed(releaseFailure)
                }
            }
        }
    }

    private suspend fun release(entry: RegionEntry) {
        val failure: Throwable? = withContext(NonCancellable) {
            val shouldClose = bookkeeping.withLock {
                check(entry.users > 0) { "Region entry is not in use: ${entry.position}" }
                entry.users--
                if (entry.users > 0) return@withLock false
                entry.drained.complete(Unit)
                check(!entry.closing) { "Region entry is already closing: ${entry.position}" }
                entry.closing = true
                true
            }
            if (!shouldClose) return@withContext null
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
                closeFailure?.let { cleanupFailures += it }
            }
            closeFailure
        }
        failure?.let { throw it }
    }

    private suspend fun openedStore(entry: RegionEntry): RegionFileStore = entry.openMutex.withLock {
        entry.store?.let { return@withLock it }
        val opened = RegionFileStore.open(
            files = files,
            directory = directory,
            position = entry.position,
            maximumCompressedChunkBytes = configuration.maximumCompressedChunkBytes,
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

    private fun combineFailures(
        current: Throwable?,
        caught: Throwable,
    ): Throwable {
        if (current == null) return caught
        if (current !== caught) current.addSuppressed(caught)
        return current
    }

    private class RegionEntry(val position: RegionPosition) {
        var store: RegionFileStore? = null
        val openMutex = Mutex()
        val fileAccess = LogicalFileAccess()
        var users = 1
        var closing = false
        val drained = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
    }
}
