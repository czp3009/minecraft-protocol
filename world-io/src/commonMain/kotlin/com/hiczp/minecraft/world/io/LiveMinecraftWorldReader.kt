package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.RegionChunk
import com.hiczp.minecraft.world.format.RegionFile
import com.hiczp.minecraft.world.format.RegionPosition
import okio.FileSystem
import okio.Path

/**
 * A non-locking reader for a world that may be modified concurrently.
 *
 * Reads may observe stale or torn state and propagate the resulting I/O,
 * format, or decompression failure. On the system filesystem, every opened
 * handle permits the read, write, delete, and replacement operations used by
 * the matching official server. The reader never repairs or mutates files.
 */
class LiveMinecraftWorldReader private constructor(
    val paths: MinecraftWorldPaths,
    private val world: OpenMinecraftWorld,
) {
    suspend fun readLevelData(): NbtDocument = world.readLevelData()

    suspend fun readPlayerData(playerUuid: String): NbtDocument? =
        world.readPlayerData(playerUuid)

    suspend fun readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readSavedData(identifier, dimension)

    suspend fun readStatistics(playerUuid: String): String =
        world.readStatistics(playerUuid)

    suspend fun readAdvancements(playerUuid: String): String =
        world.readAdvancements(playerUuid)

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

    suspend fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readChunkNbt(position, storage, dimension)

    suspend fun close() = world.close()

    companion object {
        fun open(root: Path): LiveMinecraftWorldReader =
            open(root, systemFileSystem)

        internal fun open(
            root: Path,
            fileSystem: FileSystem,
        ): LiveMinecraftWorldReader {
            val metadata = fileSystem.metadataOrNull(root)
                ?: throw WorldIOException(
                    "World directory does not exist: $root",
                )
            if (!metadata.isDirectory) {
                throw WorldIOException("World path is not a directory: $root")
            }
            val paths = MinecraftWorldPaths(root)
            val world = OpenMinecraftWorld(
                paths = paths,
                files = WorldFileAccess.liveReadOnly(fileSystem),
            )
            return LiveMinecraftWorldReader(paths, world)
        }
    }
}
