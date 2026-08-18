package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Shared resource-owning implementation behind mutable world access.
 *
 * The state mutex protects only in-memory registries and admission state. Each logical file has
 * shared readers or one writer. Filesystem work runs in the callers' coroutine contexts.
 */
internal class OpenMinecraftWorld(
    val paths: MinecraftWorldPaths,
    private val files: WorldFileAccess,
    private val nbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    private val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
    private val regionStoreConfiguration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
    private val directoryLock: WorldDirectoryLock? = null,
) {
    init {
        require(!files.liveReadOnly) { "OpenMinecraftWorld requires mutable file access" }
    }

    private val state = Mutex()
    private val nbtFiles = NbtFileStore(files, nbtFormat)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)
    private val regionStores = mutableMapOf<RegionStoreKey, RegionStoreEntry>()
    private val metadataEntries = mutableMapOf<MetadataKey, MetadataEntry>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    // Mutable healthy reads share access. Official fallback promotion and corrupt-player copying
    // mutate the logical file group, so a recoverable primary failure is retried exclusively.
    suspend fun readLevelDataDocument(): NbtDocument = withMetadataEntry({ MetadataKey.LevelData }) { entry ->
        val fileAccess = entry.fileAccess
        when (val read = fileAccess.read { levelData.readForSharedAccess() }) {
            is CoordinatedRead.Complete -> read.value
            CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.read() }
        }
    }

    suspend fun <T> readLevelData(deserializer: DeserializationStrategy<T>): T =
        withMetadataEntry({ MetadataKey.LevelData }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { levelData.readForSharedAccess(deserializer) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.read(deserializer) }
            }
        }

    suspend fun <T> readLevelData(block: (KotlinxSource) -> T): T =
        withMetadataEntry({ MetadataKey.LevelData }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { levelData.readForSharedAccess(block) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.read(block) }
            }
        }

    suspend fun writeLevelDataDocument(document: NbtDocument) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelData.write(document)
        }

    suspend fun <T> writeLevelData(
        serializer: SerializationStrategy<T>,
        value: T,
    ) = withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
        levelData.write(serializer, value)
    }

    suspend fun writeLevelData(block: (KotlinxSink) -> Unit) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelData.write(block)
        }

    suspend fun readPlayerData(playerUuid: String): NbtDocument? =
        withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { playerData.readForSharedAccess(playerUuid) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { playerData.read(playerUuid) }
            }
        }

    suspend fun <T> readPlayerData(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T? = withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { entry ->
        val fileAccess = entry.fileAccess
        when (val read = fileAccess.read { playerData.readForSharedAccess(playerUuid, block) }) {
            is CoordinatedRead.Complete -> read.value
            CoordinatedRead.RequiresExclusive -> fileAccess.write { playerData.read(playerUuid, block) }
        }
    }

    suspend fun writePlayerData(
        playerUuid: String,
        document: NbtDocument,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerData.write(playerUuid, document)
    }

    suspend fun writePlayerData(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerData.write(playerUuid, block)
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

    suspend fun <T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory,
        block: (KotlinxSource) -> T,
    ): T? = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.READ,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).read(identifier, block)
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

    suspend fun writeSavedData(
        identifier: String,
        dimension: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).write(identifier, block)
    }

    suspend fun readStatisticsText(playerUuid: String): String =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
            jsonFiles.read(paths.statistics(playerUuid))
        }

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.readJson(paths.statistics(playerUuid), deserializer, json)
    }

    suspend fun <T> readStatistics(
        playerUuid: String,
        block: BufferedSource.() -> T,
    ): T = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.read(paths.statistics(playerUuid), block)
    }

    suspend fun writeStatisticsText(playerUuid: String, text: String) =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
            jsonFiles.write(paths.statistics(playerUuid), text)
        }

    suspend fun <T> writeStatistics(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.writeJson(paths.statistics(playerUuid), serializer, value, json)
    }

    suspend fun writeStatistics(
        playerUuid: String,
        block: BufferedSink.() -> Unit,
    ) = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.write(paths.statistics(playerUuid), block)
    }

    suspend fun readAdvancementsText(playerUuid: String): String =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
            jsonFiles.read(paths.advancement(playerUuid))
        }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.readJson(paths.advancement(playerUuid), deserializer, json)
    }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        block: BufferedSource.() -> T,
    ): T = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.read(paths.advancement(playerUuid), block)
    }

    suspend fun writeAdvancementsText(playerUuid: String, text: String) =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
            jsonFiles.write(paths.advancement(playerUuid), text)
        }

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.writeJson(paths.advancement(playerUuid), serializer, value, json)
    }

    suspend fun writeAdvancements(
        playerUuid: String,
        block: BufferedSink.() -> Unit,
    ) = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.write(paths.advancement(playerUuid), block)
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

    suspend fun <T> readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T? = withRegionStore(storage, dimension) {
        readChunk(position, block)
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

    suspend fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: BufferedSink.() -> Unit,
    ) = withRegionStore(storage, dimension) {
        writeChunk(position, compression, compressedLength, block)
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
            // Every entry was pinned as one snapshot. After cancellation, skip further flush work
            // but still release every pin through non-cancellable cleanup.
            if (failure !is CancellationException) {
                try {
                    entry.store.flush()
                } catch (caught: Throwable) {
                    entryFailure = caught
                }
            }
            entryFailure = collectCleanupFailure(entryFailure) { releaseRegionStore(entry) }
            entryFailure?.let { caught ->
                failure = combineFailures(failure, caught)
            }
        }
        throwFailureOrCancellation(failure)
    }

    suspend fun close() {
        val completion: CompletableDeferred<Unit>
        val owner: Boolean
        state.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                owner = false
            } else {
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

    internal suspend fun activeMetadataEntryCount(): Int = state.withLock {
        metadataEntries.size
    }

    internal suspend fun activeMetadataUsers(): Int = state.withLock {
        metadataEntries.values.sumOf { it.users }
    }

    internal suspend fun activeRegionStoreCount(): Int = state.withLock {
        regionStores.size
    }

    internal suspend fun activeRegionStoreUsers(): Int = state.withLock {
        regionStores.values.sumOf { it.users }
    }

    private suspend fun firstClose(completion: CompletableDeferred<Unit>) {
        val failure: Throwable? = withContext(NonCancellable) {
            val storeEntries = state.withLock {
                regionStores.values.toList()
            }
            val activeMetadataEntries = state.withLock {
                metadataEntries.values.toList()
            }
            storeEntries.map { it.closed }.awaitAll()
            activeMetadataEntries.map { it.closed }.awaitAll()

            var failure = state.withLock {
                closeBarrierFailures.reduceOrNull { current, caught ->
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
                closeBarrierFailures.clear()
                closeFailure = failure
            }
            completion.complete(Unit)
            failure
        }
        // The cleanup itself must finish even if its owner is cancelled. Observe that cancellation
        // only after the shared close result has been finalized for every current and later caller.
        throwFailureOrCancellation(failure)
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
        return withCleanup(
            cleanup = { releaseMetadata(entry) },
        ) {
            block(entry)
        }
    }

    private suspend fun <T> withRegionStore(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: suspend WorldRegionStore.() -> T,
    ): T {
        val entry = acquireRegionStore(storage, dimension)
        return withCleanup(
            cleanup = { releaseRegionStore(entry) },
        ) {
            entry.store.block()
        }
    }

    private suspend fun acquireMetadata(key: () -> MetadataKey): MetadataEntry = state.withLock {
        checkValid()
        val metadataKey = key()
        metadataEntries.getOrPut(metadataKey) {
            MetadataEntry(metadataKey)
        }.also { entry ->
            entry.users++
        }
    }

    private suspend fun releaseMetadata(entry: MetadataEntry): Throwable? {
        state.withLock {
            check(entry.users > 0) { "Metadata entry is not in use: ${entry.key}" }
            entry.users--
            if (entry.users > 0) return@withLock
            if (metadataEntries[entry.key] === entry) {
                metadataEntries.remove(entry.key)
            }
            entry.closed.complete(Unit)
        }
        return null
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

    private suspend fun releaseRegionStore(entry: RegionStoreEntry): Throwable? {
        val shouldClose = state.withLock {
            check(entry.users > 0) { "Region store entry is not in use: ${entry.key}" }
            check(!entry.closing) { "Region store entry is already closing: ${entry.key}" }
            entry.users--
            if (entry.users > 0) return@withLock false
            entry.closing = true
            true
        }
        if (!shouldClose) return null
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
            closeFailure?.let {
                if (closed) closeBarrierFailures += it
            }
        }
        return closeFailure
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
        var users = 0
        val closed = CompletableDeferred<Unit>()
    }

    private class RegionStoreEntry(
        val key: RegionStoreKey,
        val store: WorldRegionStore,
    ) {
        var users = 1
        var closing = false
        val closed = CompletableDeferred<Unit>()
    }
}
