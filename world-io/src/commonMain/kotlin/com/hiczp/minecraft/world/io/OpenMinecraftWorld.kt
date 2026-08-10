package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared resource-owning implementation behind both public world entries. */
internal class OpenMinecraftWorld(
    val paths: MinecraftWorldPaths,
    private val files: WorldFileAccess,
    private val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
    private val regionStoreConfiguration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
    private val directoryLock: WorldDirectoryLock? = null,
) {
    private val mutex = Mutex()
    private val nbtFiles = NbtFileStore(files)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)
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
        dimension: DimensionDirectory,
    ): NbtDocument? = mutex.withLock {
        checkValid()
        SavedDataFileStore(paths, dimension, nbtFiles).read(identifier)
    }

    suspend fun writeSavedData(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory,
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

    suspend fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionFile = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).readRegion(position)
    }

    suspend fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionChunk? = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).readChunk(position)
    }

    suspend fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Boolean = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).doesChunkExist(position)
    }

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).writeChunk(position, chunk)
    }

    suspend fun clearChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).clearChunk(position)
    }

    suspend fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): NbtDocument? = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).readChunkNbt(position)
    }

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = mutex.withLock {
        checkValid()
        regionStore(storage, dimension).writeChunkNbt(position, document)
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
            directoryLock?.close()
        } catch (lockFailure: Throwable) {
            val current = failure
            if (current == null) failure = lockFailure
            else current.addSuppressed(lockFailure)
        }
        failure?.let { throw it }
    }

    private fun checkValid() {
        val owner = if (files.liveReadOnly) {
            "Live world reader"
        } else {
            "World access"
        }
        check(!closed) { "$owner is closed: ${paths.root}" }
        val lock = directoryLock ?: return
        if (!lock.isValid) {
            throw WorldLockException(
                "World directory lock is no longer valid: ${paths.root}",
            )
        }
    }

    private fun regionStore(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): WorldRegionStore {
        val key = RegionStoreKey(storage, dimension)
        return regionStores.getOrPut(key) {
            WorldRegionStore(
                paths = paths,
                storage = storage,
                dimension = dimension,
                files = files,
                chunkNbtFormat = regionChunkNbtFormat,
                configuration = regionStoreConfiguration,
            )
        }
    }
}

private data class RegionStoreKey(
    val storage: RegionStorageDirectory,
    val dimension: DimensionDirectory,
)
