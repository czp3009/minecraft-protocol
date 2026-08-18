package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

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
 * cannot replace their sectors mid-read. Region entries and handles exist only while one-shot
 * operations or caller-owned [WorldRegion] resources retain them; the last user closes the file.
 * These suspend functions wait only for coordination; blocking filesystem I/O and compression run
 * on the calling coroutine's dispatcher and are not automatically main-safe.
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
    private val regions = mutableMapOf<RegionPosition, WorldRegionEntry>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    /** Opens a caller-owned resource that keeps one Region entry alive between operations. */
    suspend fun openRegion(position: RegionPosition): WorldRegion = openRegion(position) { null }

    internal suspend fun openRegion(
        position: RegionPosition,
        afterRelease: suspend () -> Throwable?,
    ): WorldRegion = WorldRegion(this, acquire(position), afterRelease)

    /** Opens one Region for [block] and always closes it afterward. */
    suspend fun <T> withRegion(
        position: RegionPosition,
        block: suspend WorldRegion.() -> T,
    ): T {
        val region = openRegion(position)
        return withCleanup(
            cleanup = {
                try {
                    region.close()
                    null
                } catch (caught: Throwable) {
                    caught
                }
            },
        ) {
            region.block()
        }
    }

    /** Reads one complete in-memory snapshot without creating a missing Region file. */
    suspend fun readRegion(position: RegionPosition): RegionFile? = withRegionEntry(position) { entry ->
        readRegion(entry)
    }

    suspend fun <T> readRegion(
        position: RegionPosition,
        block: RegionReadScope.() -> T,
    ): T? = withRegionEntry(position) { entry ->
        readRegion(entry, block)
    }

    /** Replaces the complete logical Region with one batch header commit. */
    suspend fun writeRegion(
        position: RegionPosition,
        region: RegionFile,
    ) = withRegionEntry(position) { entry ->
        writeRegion(entry, region)
    }

    suspend fun writeRegion(
        position: RegionPosition,
        block: RegionWriteScope.() -> Unit,
    ) = withRegionEntry(position) { entry ->
        writeRegion(entry, block)
    }

    suspend fun clearRegion(position: RegionPosition) = withRegionEntry(position) { entry ->
        clearRegion(entry)
    }

    suspend fun doesRegionExist(position: RegionPosition): Boolean = withRegionEntry(position) { entry ->
        doesRegionExist(entry)
    }

    suspend fun readChunk(position: ChunkPosition): RegionChunk? = readChunk(position.region, position.local)

    suspend fun readChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
    ): RegionChunk? = withRegionEntry(regionPosition) { entry ->
        readChunk(entry, position)
    }

    suspend fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = readChunk(position.region, position.local, block)

    suspend fun <T> readChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = withRegionEntry(regionPosition) { entry ->
        readChunk(entry, position, block)
    }

    suspend fun doesChunkExist(position: ChunkPosition): Boolean = doesChunkExist(position.region, position.local)

    suspend fun doesChunkExist(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
    ): Boolean = withRegionEntry(regionPosition) { entry ->
        doesChunkExist(entry, position)
    }

    /**
     * Writes compressed chunk data with an automatic timestamp and automatic internal/external
     * selection. The input payload marker and timestamp are representation details and do not
     * control the filesystem commit.
     */
    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk,
    ) = writeChunk(position.region, position.local, chunk)

    suspend fun writeChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        chunk: RegionChunk,
    ) = withRegionEntry(regionPosition) { entry ->
        writeChunk(entry, position, chunk)
    }

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeChunk(position.region, position.local, compression, compressedLength, block)

    suspend fun writeChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) = withRegionEntry(regionPosition) { entry ->
        writeChunk(entry, position, compression, compressedLength, block)
    }

    suspend fun clearChunk(position: ChunkPosition) = clearChunk(position.region, position.local)

    suspend fun clearChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
    ) = withRegionEntry(regionPosition) { entry ->
        clearChunk(entry, position)
    }

    suspend fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(position.region, position.local)

    suspend fun readChunkNbtDocument(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
    ): NbtDocument? = withRegionEntry(regionPosition) { entry ->
        readChunkNbtDocument(entry, position)
    }

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = readChunkNbt(position.region, position.local, deserializer)

    suspend fun <T> readChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withRegionEntry(regionPosition) { entry ->
        readChunkNbt(entry, position, deserializer)
    }

    suspend inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
    ): T? = readChunkNbt(regionPosition, position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbtDocument(
        position = position,
        document = document,
        compression = configuration.writeCompression,
    )

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbtDocument(
        regionPosition = regionPosition,
        position = position,
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
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = withRegionEntry(regionPosition) { entry ->
        writeChunkNbtDocument(entry, position, document, compression)
    }

    suspend fun <T> writeChunkNbt(
        position: ChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(position.region, position.local, serializer, value, compression)

    suspend fun <T> writeChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = withRegionEntry(regionPosition) { entry ->
        writeChunkNbt(entry, position, serializer, value, compression)
    }

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
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(
        regionPosition,
        position,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
    )

    internal suspend fun readRegion(entry: WorldRegionEntry): RegionFile? = withReadAccess(entry) {
        openedStoreForRead(entry)?.readRegion()
    }

    internal suspend fun <T> readRegion(
        entry: WorldRegionEntry,
        block: RegionReadScope.() -> T,
    ): T? = withReadAccess(entry) {
        openedStoreForRead(entry)?.readRegion(block)
    }

    internal suspend fun writeRegion(
        entry: WorldRegionEntry,
        region: RegionFile,
    ) {
        files.requireWritable()
        validateRegionPayloads(entry.position, region)
        entry.fileAccess.write {
            openedStoreForWrite(entry).writeRegion(region)
        }
    }

    internal suspend fun writeRegion(
        entry: WorldRegionEntry,
        block: RegionWriteScope.() -> Unit,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedStoreForWrite(entry).writeRegion(block)
        }
    }

    internal suspend fun clearRegion(entry: WorldRegionEntry) {
        files.requireWritable()
        entry.fileAccess.write {
            openedStoreForRead(entry)?.clearRegion()
        }
    }

    internal suspend fun doesRegionExist(entry: WorldRegionEntry): Boolean = withReadAccess(entry) {
        val path = regionPath(entry.position)
        val metadata = files.fileSystem.metadataOrNull(path) ?: return@withReadAccess false
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        true
    }

    internal suspend fun readChunk(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
    ): RegionChunk? = withReadAccess(entry) {
        openedStoreForRead(entry)?.readChunk(position)
    }

    internal suspend fun <T> readChunk(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = withReadAccess(entry) {
        openedStoreForRead(entry)?.readChunk(position, block)
    }

    internal suspend fun doesChunkExist(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
    ): Boolean = withReadAccess(entry) {
        openedStoreForRead(entry)?.doesChunkExist(position) == true
    }

    internal suspend fun writeChunk(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
        chunk: RegionChunk,
    ) {
        files.requireWritable()
        validatedCompressedPayload(entry.position.chunk(position), chunk)
        entry.fileAccess.write {
            openedStoreForWrite(entry).writeChunk(position, chunk)
        }
    }

    internal suspend fun writeChunk(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedStoreForWrite(entry).writeChunk(position, compression, compressedLength, block)
        }
    }

    internal suspend fun clearChunk(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
    ) {
        files.requireWritable()
        entry.fileAccess.write {
            openedStoreForRead(entry)?.clearChunk(position)
        }
    }

    internal suspend fun readChunkNbtDocument(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
    ): NbtDocument? = withReadAccess(entry) {
        openedStoreForRead(entry)?.readChunk(position) { info, source ->
            val absolute = entry.position.chunk(position)
            withOkioIoExceptions("Cannot decode chunk $absolute") {
                chunkNbtFormat.decodeFromSource(source, info.compression)
            }
        }
    }

    internal suspend fun <T> readChunkNbt(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withReadAccess(entry) {
        openedStoreForRead(entry)?.readChunk(position) { info, source ->
            val absolute = entry.position.chunk(position)
            withOkioIoExceptions("Cannot decode chunk $absolute") {
                chunkNbtFormat.decodeFromSource(deserializer, source, info.compression)
            }
        }
    }

    internal suspend fun writeChunkNbtDocument(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) {
        files.requireWritable()
        val absolute = entry.position.chunk(position)
        val chunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            chunkNbtFormat.encode(document, compression)
        }
        entry.fileAccess.write {
            openedStoreForWrite(entry).writeChunk(position, chunk)
        }
    }

    internal suspend fun <T> writeChunkNbt(
        entry: WorldRegionEntry,
        position: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression,
    ) {
        files.requireWritable()
        val absolute = entry.position.chunk(position)
        val chunk = withOkioIoExceptions("Cannot encode chunk $absolute") {
            chunkNbtFormat.encode(serializer, value, compression)
        }
        entry.fileAccess.write {
            openedStoreForWrite(entry).writeChunk(position, chunk)
        }
    }

    internal suspend fun flush(entry: WorldRegionEntry) {
        entry.fileAccess.write {
            entry.openMutex.withLock {
                entry.store?.flush()
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
                            entry.store?.flush()
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

    private suspend fun acquire(position: RegionPosition): WorldRegionEntry {
        while (true) {
            val closing = bookkeeping.withLock {
                checkOpen()
                val entry = regions[position]
                if (entry == null) {
                    val created = WorldRegionEntry(position)
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
        block: suspend (WorldRegionEntry) -> T,
    ): T {
        val entry = acquire(position)
        return withCleanup(
            cleanup = { releaseRegion(entry) },
        ) {
            block(entry)
        }
    }

    internal suspend fun releaseRegion(entry: WorldRegionEntry): Throwable? {
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

    private suspend fun openedStoreForRead(entry: WorldRegionEntry): RegionFileStore? = entry.openMutex.withLock {
        entry.store?.let { return@withLock it }
        val opened = RegionFileStore.openExistingMutable(
            files = files,
            directory = directory,
            position = entry.position,
            syncWrites = configuration.syncWrites,
        ) ?: return@withLock null
        entry.store = opened
        opened
    }

    private suspend fun openedStoreForWrite(entry: WorldRegionEntry): RegionFileStore = entry.openMutex.withLock {
        entry.store?.let { return@withLock it }
        val opened = RegionFileStore.openMutable(
            files = files,
            directory = directory,
            position = entry.position,
            syncWrites = configuration.syncWrites,
        )
        entry.store = opened
        opened
    }

    private suspend fun <T> withReadAccess(
        entry: WorldRegionEntry,
        block: suspend () -> T,
    ): T {
        return entry.fileAccess.read(block)
    }

    private fun checkOpen() {
        check(!closed) { "Region store is closed: $directory" }
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
internal class WorldRegionEntry(val position: RegionPosition) {
    var store: RegionFileStore? = null
    val openMutex = Mutex()
    val fileAccess = LogicalFileAccess()
    var users = 1
    var closing = false
    val closed = CompletableDeferred<Unit>()
}
