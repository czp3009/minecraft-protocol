package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import okio.Path

/**
 * Region-storage policy shared by every dimension opened under one world
 * lease. It affects chunks newly encoded through NBT convenience writes;
 * existing and caller-supplied compressed chunks remain unchanged.
 * Standalone world files retain their own official storage policies.
 */
data class MinecraftWorldAccessConfiguration(
    val regionStoreConfiguration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
    val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
)

/** A system-filesystem world lease backed by the vanilla `session.lock`. */
class MinecraftWorldAccess private constructor(
    val paths: MinecraftWorldPaths,
    val configuration: MinecraftWorldAccessConfiguration,
    private val world: OpenMinecraftWorld,
) {
    suspend fun readLevelData(): NbtDocument = world.readLevelData()

    suspend fun writeLevelData(document: NbtDocument) =
        world.writeLevelData(document)

    suspend fun readPlayerData(playerUuid: String): NbtDocument? =
        world.readPlayerData(playerUuid)

    suspend fun writePlayerData(
        playerUuid: String,
        document: NbtDocument,
    ) = world.writePlayerData(playerUuid, document)

    suspend fun readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readSavedData(identifier, dimension)

    suspend fun writeSavedData(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeSavedData(identifier, document, dimension)

    suspend fun readStatistics(playerUuid: String): String =
        world.readStatistics(playerUuid)

    suspend fun writeStatistics(playerUuid: String, json: String) =
        world.writeStatistics(playerUuid, json)

    suspend fun readAdvancements(playerUuid: String): String =
        world.readAdvancements(playerUuid)

    suspend fun writeAdvancements(playerUuid: String, json: String) =
        world.writeAdvancements(playerUuid, json)

    suspend fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionFile = world.readRegion(position, storage, dimension)

    suspend fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = world.readChunk(position, storage, dimension)

    suspend fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = world.doesChunkExist(position, storage, dimension)

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunk(position, chunk, storage, dimension)

    suspend fun clearChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.clearChunk(position, storage, dimension)

    suspend fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readChunkNbt(position, storage, dimension)

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbt(position, document, storage, dimension)

    suspend fun flush() = world.flush()

    suspend fun close() = world.close()

    companion object {
        fun open(root: Path): MinecraftWorldAccess =
            open(root, MinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            configuration: MinecraftWorldAccessConfiguration,
        ): MinecraftWorldAccess {
            systemFileSystem.createDirectories(root)
            val paths = MinecraftWorldPaths(root)
            val lock = acquireWorldDirectoryLock(paths.sessionLock)
            val world = OpenMinecraftWorld(
                paths = paths,
                files = WorldFileAccess.mutable(systemFileSystem),
                regionChunkNbtFormat = configuration.regionChunkNbtFormat,
                regionStoreConfiguration = configuration.regionStoreConfiguration,
                directoryLock = lock,
            )
            return MinecraftWorldAccess(paths, configuration, world)
        }

        fun isLocked(root: Path): Boolean =
            isWorldDirectoryLocked(MinecraftWorldPaths(root).sessionLock)
    }
}
