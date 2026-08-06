package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path

/** A system-filesystem world lease backed by the vanilla `session.lock`. */
class MinecraftWorldAccess private constructor(
    val paths: MinecraftWorldPaths,
    private val directoryLock: WorldDirectoryLock,
) {
    private val mutex = Mutex()
    private val nbtFiles = NbtFileStore(systemFileSystem)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(systemFileSystem)
    private val regionStores =
        linkedMapOf<RegionStoreKey, WorldRegionStore>()
    private var closed = false

    suspend fun readLevelData(): NbtDocument = mutex.withLock {
        checkValid()
        levelData.read()
    }

    suspend fun writeLevelData(document: NbtDocument) = mutex.withLock {
        checkValid()
        levelData.write(document)
    }

    suspend fun readPlayerData(playerUuid: String): NbtDocument? =
        mutex.withLock {
            checkValid()
            playerData.read(playerUuid)
        }

    suspend fun writePlayerData(
        playerUuid: String,
        document: NbtDocument,
    ) = mutex.withLock {
        checkValid()
        playerData.write(playerUuid, document)
    }

    suspend fun readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = mutex.withLock {
        checkValid()
        SavedDataFileStore(paths, dimension, nbtFiles).read(identifier)
    }

    suspend fun writeSavedData(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = mutex.withLock {
        checkValid()
        SavedDataFileStore(paths, dimension, nbtFiles)
            .write(identifier, document)
    }

    suspend fun readStatistics(playerUuid: String): String =
        mutex.withLock {
            checkValid()
            jsonFiles.read(paths.statistics(playerUuid))
        }

    suspend fun writeStatistics(playerUuid: String, json: String) =
        mutex.withLock {
            checkValid()
            jsonFiles.write(paths.statistics(playerUuid), json)
        }

    suspend fun readAdvancements(playerUuid: String): String =
        mutex.withLock {
            checkValid()
            jsonFiles.read(paths.advancement(playerUuid))
        }

    suspend fun writeAdvancements(playerUuid: String, json: String) =
        mutex.withLock {
            checkValid()
            jsonFiles.write(paths.advancement(playerUuid), json)
        }

    suspend fun <T> withRegionStore(
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: suspend (WorldRegionStore) -> T,
    ): T = mutex.withLock {
        checkValid()
        val key = RegionStoreKey(storage, dimension)
        val store = regionStores.getOrPut(key) {
            WorldRegionStore(paths, storage, dimension)
        }
        block(store)
    }

    suspend fun flush() = mutex.withLock {
        checkValid()
        var failure: Throwable? = null
        regionStores.values.forEach { store ->
            try {
                store.flush()
            } catch (caught: Throwable) {
                val current = failure
                if (current == null) failure = caught
                else current.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        var failure: Throwable? = null
        regionStores.values.forEach { store ->
            try {
                store.close()
            } catch (caught: Throwable) {
                val current = failure
                if (current == null) failure = caught
                else current.addSuppressed(caught)
            }
        }
        regionStores.clear()
        try {
            directoryLock.close()
        } catch (lockFailure: Throwable) {
            val current = failure
            if (current == null) failure = lockFailure
            else current.addSuppressed(lockFailure)
        }
        failure?.let { throw it }
    }

    private fun checkValid() {
        check(!closed) { "World access is closed: ${paths.root}" }
        if (!directoryLock.isValid) {
            throw WorldLockException(
                "World directory lock is no longer valid: ${paths.root}",
            )
        }
    }

    companion object {
        fun open(root: Path): MinecraftWorldAccess {
            systemFileSystem.createDirectories(root)
            val paths = MinecraftWorldPaths(root)
            val lock = acquireWorldDirectoryLock(paths.sessionLock)
            return MinecraftWorldAccess(paths, lock)
        }

        fun isLocked(root: Path): Boolean =
            isWorldDirectoryLocked(MinecraftWorldPaths(root).sessionLock)
    }
}

private data class RegionStoreKey(
    val storage: RegionStorageDirectory,
    val dimension: DimensionDirectory,
)
