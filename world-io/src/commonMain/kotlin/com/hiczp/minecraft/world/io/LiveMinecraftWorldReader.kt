package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import kotlinx.io.Source as KotlinxSource

/**
 * A non-locking reader for a world that may be modified concurrently.
 *
 * This class takes neither `session.lock` nor per-file operating-system or in-process exclusion.
 * Reads may observe stale or torn state and propagate the resulting I/O, format, or decompression
 * failure. On the system filesystem, every opened handle permits the read, write, delete, and
 * replacement operations used by the matching official server. The reader never repairs or
 * mutates files and does not delay the server's writes.
 *
 * Public operations may be called concurrently, including reads of the same logical metadata file
 * or `.mca` file. This class does not create a thread pool or select a dispatcher; blocking
 * filesystem I/O, NBT work, and compression run synchronously on the calling thread and are not
 * automatically main-safe. Callers may invoke them from many coroutines on their own dispatcher.
 * The reader has no mutable lifecycle or retained file resource and does not need to be closed.
 */
class LiveMinecraftWorldReader private constructor(
    val paths: MinecraftWorldPaths,
    private val files: WorldFileAccess,
    private val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
) {
    private val nbtFiles = NbtFileStore(files)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)

    fun readLevelData(): NbtDocument = levelData.read()

    fun <T> readLevelData(block: (KotlinxSource) -> T): T = levelData.read(block)

    fun readPlayerData(playerUuid: String): NbtDocument? =
        playerData.read(playerUuid)

    fun <T> readPlayerData(playerUuid: String, block: (KotlinxSource) -> T): T? =
        playerData.read(playerUuid, block)

    fun readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = SavedDataFileStore(paths, dimension, nbtFiles).read(identifier)

    fun <T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSource) -> T,
    ): T? = SavedDataFileStore(paths, dimension, nbtFiles).read(identifier, block)

    fun readStatistics(playerUuid: String): String = jsonFiles.read(paths.statistics(playerUuid))

    fun <T> readStatistics(playerUuid: String, block: BufferedSource.() -> T): T =
        jsonFiles.read(paths.statistics(playerUuid), block)

    fun readAdvancements(playerUuid: String): String = jsonFiles.read(paths.advancement(playerUuid))

    fun <T> readAdvancements(playerUuid: String, block: BufferedSource.() -> T): T =
        jsonFiles.read(paths.advancement(playerUuid), block)

    fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionFile = withRegionFile(position, storage, dimension) { store ->
        store?.readAll() ?: RegionFile()
    }

    fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = withRegionFile(position.region, storage, dimension) { store ->
        store?.read(position)
    }

    fun <T> readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T? = withRegionFile(position.region, storage, dimension) { store ->
        store?.read(position, block)
    }

    fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = withRegionFile(position.region, storage, dimension) { store ->
        store?.exists(position) == true
    }

    fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = withRegionFile(position.region, storage, dimension) { store ->
        store?.read(position) { info, source ->
            withOkioIoExceptions("Cannot decode chunk $position") {
                val converted = source.asKotlinxIoRawSource().buffered()
                regionChunkNbtFormat.decodeFromSource(converted, info.compression)
            }
        }
    }

    private fun <T> withRegionFile(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (RegionFileStore?) -> T,
    ): T {
        val directory = paths.regionDirectory(storage, dimension)
        val path = directory / "r.${position.x}.${position.z}.mca"
        val metadata = files.fileSystem.metadataOrNull(path)
            ?: return block(null)
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val store = RegionFileStore.open(
            files = files,
            directory = directory,
            position = position,
        )
        return useResource(store, { it.close() }, block)
    }

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
            return LiveMinecraftWorldReader(
                paths = paths,
                files = WorldFileAccess.liveReadOnly(fileSystem),
            )
        }
    }
}
