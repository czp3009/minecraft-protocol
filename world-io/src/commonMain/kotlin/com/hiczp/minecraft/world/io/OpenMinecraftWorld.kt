package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Shared resource-owning implementation behind mutable world access.
 *
 * The state mutex protects only in-memory registries and admission state. Each logical file has
 * shared readers or one writer. Filesystem work runs in the callers' coroutine contexts.
 */
internal class OpenMinecraftWorld(
    val paths: MinecraftWorldPaths,
    private val files: WorldFileAccess,
    private val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
    private val regionStoreConfiguration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
    private val directoryLock: WorldDirectoryLock? = null,
) {
    init {
        require(!files.liveReadOnly) { "OpenMinecraftWorld requires mutable file access" }
    }

    private val state = Mutex()
    private val nbtFiles = NbtFileStore(files)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)
    private val regionStores = mutableMapOf<RegionStoreKey, RegionStoreEntry>()
    private val metadataEntries = mutableMapOf<MetadataKey, MetadataEntry>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val cleanupFailures = mutableListOf<Throwable>()

    // Mutable healthy reads share access. Official fallback promotion and corrupt-player copying
    // mutate the logical file group, so a recoverable primary failure is retried exclusively.
    suspend fun readLevelData(): NbtDocument = withMetadataEntry({ MetadataKey.LevelData }) { entry ->
        val fileAccess = entry.fileAccess
        when (val read = fileAccess.read { levelData.readForSharedAccess() }) {
            is CoordinatedRead.Complete -> read.value
            CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.read() }
        }
    }

    suspend fun writeLevelData(document: NbtDocument) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelData.write(document)
        }

    suspend fun readPlayerData(playerUuid: String): NbtDocument? =
        withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { playerData.readForSharedAccess(playerUuid) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { playerData.read(playerUuid) }
            }
        }

    suspend fun writePlayerData(
        playerUuid: String,
        document: NbtDocument,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerData.write(playerUuid, document)
    }

    suspend fun readSavedData(
        identifier: String,
        dimension: DimensionDirectory,
    ): NbtDocument? = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.READ,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).read(identifier)
    }

    suspend fun writeSavedData(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory,
    ) = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).write(identifier, document)
    }

    suspend fun readStatistics(playerUuid: String): String =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
            jsonFiles.read(paths.statistics(playerUuid))
        }

    suspend fun writeStatistics(playerUuid: String, json: String) =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
            jsonFiles.write(paths.statistics(playerUuid), json)
        }

    suspend fun readAdvancements(playerUuid: String): String =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
            jsonFiles.read(paths.advancement(playerUuid))
        }

    suspend fun writeAdvancements(playerUuid: String, json: String) =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
            jsonFiles.write(paths.advancement(playerUuid), json)
        }

    suspend fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionFile = withRegionStore(storage, dimension) {
        readRegion(position)
    }

    suspend fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionChunk? = withRegionStore(storage, dimension) {
        readChunk(position)
    }

    suspend fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Boolean = withRegionStore(storage, dimension) {
        doesChunkExist(position)
    }

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStore(storage, dimension) {
        writeChunk(position, chunk)
    }

    suspend fun clearChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStore(storage, dimension) {
        clearChunk(position)
    }

    suspend fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): NbtDocument? = withRegionStore(storage, dimension) {
        readChunkNbt(position)
    }

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStore(storage, dimension) {
        writeChunkNbt(position, document)
    }

    suspend fun flush() {
        val pinned = state.withLock {
            checkValid()
            regionStores.values.filterNot { it.closing }.onEach { it.users++ }
        }
        if (pinned.isEmpty()) return

        var failure: Throwable? = null
        pinned.forEach { entry ->
            var entryFailure: Throwable? = null
            try {
                entry.store.flush()
            } catch (caught: Throwable) {
                entryFailure = caught
            }
            try {
                releaseRegionStore(entry)
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
        state.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                concurrentWait = !existing.isCompleted
            } else {
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

    internal suspend fun activeMetadataEntryCount(): Int = state.withLock {
        metadataEntries.size
    }

    internal suspend fun activeMetadataUsers(): Int = state.withLock {
        metadataEntries.values.sumOf { it.users }
    }

    internal suspend fun activeRegionStoreCount(): Int = state.withLock {
        regionStores.size
    }

    private suspend fun firstClose(completion: CompletableDeferred<Unit>) {
        val failure: Throwable? = withContext(NonCancellable) {
            val storeEntries = state.withLock {
                regionStores.values.toList()
            }
            val activeMetadataEntries = state.withLock {
                metadataEntries.values.toList()
            }
            storeEntries.map { it.drained }.awaitAll()
            activeMetadataEntries.map { it.drained }.awaitAll()
            storeEntries.map { it.closed }.awaitAll()
            activeMetadataEntries.map { it.closed }.awaitAll()

            var failure = state.withLock {
                cleanupFailures.reduceOrNull { current, caught ->
                    combineFailures(current, caught)
                }
            }
            try {
                directoryLock?.close()
            } catch (lockFailure: Throwable) {
                failure = combineFailures(failure, lockFailure)
            }
            state.withLock {
                regionStores.clear()
                metadataEntries.clear()
                cleanupFailures.clear()
            }
            closeFailure = failure
            completion.complete(Unit)
            failure
        }
        failure?.let { throw it }
    }

    private suspend fun <T> withMetadata(
        key: () -> MetadataKey,
        access: MetadataAccess,
        block: suspend () -> T,
    ): T = withMetadataEntry(key) { entry ->
        when (access) {
            MetadataAccess.READ -> entry.fileAccess.read(block)
            MetadataAccess.WRITE -> entry.fileAccess.write(block)
        }
    }

    private suspend fun <T> withMetadataEntry(
        key: () -> MetadataKey,
        block: suspend (MetadataEntry) -> T,
    ): T {
        val entry = acquireMetadata(key)
        var operationFailure: Throwable? = null
        try {
            return block(entry)
        } catch (caught: Throwable) {
            operationFailure = caught
            throw caught
        } finally {
            try {
                releaseMetadata(entry)
            } catch (releaseFailure: Throwable) {
                val current = operationFailure ?: throw releaseFailure
                if (current !== releaseFailure) {
                    current.addSuppressed(releaseFailure)
                }
            }
        }
    }

    private suspend fun <T> withRegionStore(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: suspend WorldRegionStore.() -> T,
    ): T {
        val entry = acquireRegionStore(storage, dimension)
        var operationFailure: Throwable? = null
        try {
            return entry.store.block()
        } catch (caught: Throwable) {
            operationFailure = caught
            throw caught
        } finally {
            try {
                releaseRegionStore(entry)
            } catch (releaseFailure: Throwable) {
                val current = operationFailure ?: throw releaseFailure
                if (current !== releaseFailure) {
                    current.addSuppressed(releaseFailure)
                }
            }
        }
    }

    private suspend fun acquireMetadata(key: () -> MetadataKey): MetadataEntry {
        while (true) {
            val closing = state.withLock {
                checkValid()
                val metadataKey = key()
                val entry = metadataEntries[metadataKey]
                if (entry == null) {
                    val created = MetadataEntry(metadataKey)
                    metadataEntries[metadataKey] = created
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

    private suspend fun releaseMetadata(entry: MetadataEntry) {
        withContext(NonCancellable) {
            state.withLock {
                check(entry.users > 0) { "Metadata entry is not in use: ${entry.key}" }
                entry.users--
                if (entry.users > 0) return@withLock
                entry.drained.complete(Unit)
                entry.closing = true
                if (metadataEntries[entry.key] === entry) {
                    metadataEntries.remove(entry.key)
                }
                entry.closed.complete(Unit)
            }
        }
    }

    private suspend fun acquireRegionStore(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionStoreEntry {
        while (true) {
            val closing = state.withLock {
                checkValid()
                val key = RegionStoreKey(storage, dimension)
                val entry = regionStores[key]
                if (entry == null) {
                    val created = RegionStoreEntry(
                        key = key,
                        store = WorldRegionStore(
                            paths = paths,
                            storage = storage,
                            dimension = dimension,
                            files = files,
                            chunkNbtFormat = regionChunkNbtFormat,
                            configuration = regionStoreConfiguration,
                        ),
                    )
                    regionStores[key] = created
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

    private suspend fun releaseRegionStore(entry: RegionStoreEntry) {
        val failure: Throwable? = withContext(NonCancellable) {
            val shouldClose = state.withLock {
                check(entry.users > 0) { "Region store entry is not in use: ${entry.key}" }
                entry.users--
                if (entry.users > 0) return@withLock false
                entry.drained.complete(Unit)
                check(!entry.closing) { "Region store entry is already closing: ${entry.key}" }
                entry.closing = true
                true
            }
            if (!shouldClose) return@withContext null
            var closeFailure: Throwable? = null
            try {
                entry.store.close()
            } catch (caught: Throwable) {
                closeFailure = caught
            }
            state.withLock {
                if (regionStores[entry.key] === entry) {
                    regionStores.remove(entry.key)
                }
                entry.closed.complete(Unit)
                closeFailure?.let { cleanupFailures += it }
            }
            closeFailure
        }
        failure?.let { throw it }
    }

    private fun checkValid() {
        check(!closed) { "World access is closed: ${paths.root}" }
        val lock = directoryLock ?: return
        if (!lock.isValid) {
            throw WorldLockException(
                "World directory lock is no longer valid: ${paths.root}",
            )
        }
    }

    private fun combineFailures(
        current: Throwable?,
        caught: Throwable,
    ): Throwable {
        if (current == null) return caught
        if (current !== caught) current.addSuppressed(caught)
        return current
    }

    private data class RegionStoreKey(
        val storage: RegionStorageDirectory,
        val dimension: DimensionDirectory,
    )

    private sealed interface MetadataKey {
        data object LevelData : MetadataKey

        data class PlayerData(
            val playerUuid: String,
        ) : MetadataKey

        data class SavedData(
            val path: Path,
        ) : MetadataKey

        data class Statistics(
            val playerUuid: String,
        ) : MetadataKey

        data class Advancements(
            val playerUuid: String,
        ) : MetadataKey
    }

    private enum class MetadataAccess {
        READ,
        WRITE,
    }

    private class MetadataEntry(val key: MetadataKey) {
        val fileAccess = LogicalFileAccess()
        var users = 1
        var closing = false
        val drained = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
    }

    private class RegionStoreEntry(
        val key: RegionStoreKey,
        val store: WorldRegionStore,
    ) {
        var users = 1
        var closing = false
        val drained = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
    }
}
