package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.FileSystem
import okio.Path
import kotlinx.io.Source as KotlinxSource

/** Read formats shared by every operation and explicit Region opened through one live reader. */
data class LiveMinecraftWorldReaderConfiguration(
    val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
    val standaloneNbtFormat: NbtFormat = minecraftWorldNbtFormat(),
) {
    init {
        standaloneNbtFormat.requireStandaloneWorldRoot()
    }
}

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
 * The reader itself has no mutable lifecycle and does not need to be closed. A Region returned by
 * [openRegion] owns one retained handle and must be closed by its caller.
 */
class LiveMinecraftWorldReader private constructor(
    val paths: MinecraftWorldPaths,
    val configuration: LiveMinecraftWorldReaderConfiguration,
    private val files: WorldFileAccess,
) {
    val chunkNbtFormat: RegionChunkNbtFormat
        get() = configuration.regionChunkNbtFormat

    private val nbtFiles = NbtFileStore(files, configuration.standaloneNbtFormat)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)

    fun readLevelDataDocument(): NbtDocument = levelData.readDocument()

    fun <T> readLevelData(deserializer: DeserializationStrategy<T>): T =
        levelData.read(deserializer)

    inline fun <reified T> readLevelData(): T =
        readLevelData(configuration.standaloneNbtFormat.serializersModule.serializer())

    fun <T> readLevelData(block: (KotlinxSource) -> T): T = levelData.read(block)

    fun readPlayerDataDocument(playerUuid: String): NbtDocument? =
        playerData.readDocument(playerUuid)

    fun <T> readPlayerData(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = playerData.read(playerUuid, deserializer)

    inline fun <reified T> readPlayerData(playerUuid: String): T? =
        readPlayerData(playerUuid, configuration.standaloneNbtFormat.serializersModule.serializer())

    fun <T> readPlayerData(playerUuid: String, block: (KotlinxSource) -> T): T? =
        playerData.read(playerUuid, block)

    fun readSavedDataDocument(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = SavedDataFileStore(paths, dimension, nbtFiles).readDocument(identifier)

    fun <T> readSavedData(
        identifier: String,
        deserializer: DeserializationStrategy<T>,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = SavedDataFileStore(paths, dimension, nbtFiles).read(identifier, deserializer)

    inline fun <reified T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readSavedData(
        identifier,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        dimension,
    )

    fun <T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSource) -> T,
    ): T? = SavedDataFileStore(paths, dimension, nbtFiles).read(identifier, block)

    fun readStatisticsText(playerUuid: String): String = jsonFiles.readText(paths.statistics(playerUuid))

    fun <T> readStatistics(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = jsonFiles.readJson(paths.statistics(playerUuid), deserializer, json)

    inline fun <reified T> readStatistics(
        playerUuid: String,
        json: Json = Json,
    ): T = readStatistics(playerUuid, json.serializersModule.serializer(), json)

    fun <T> readStatistics(playerUuid: String, block: (KotlinxSource) -> T): T =
        jsonFiles.read(paths.statistics(playerUuid), block)

    fun readAdvancementsText(playerUuid: String): String = jsonFiles.readText(paths.advancement(playerUuid))

    fun <T> readAdvancements(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = jsonFiles.readJson(paths.advancement(playerUuid), deserializer, json)

    inline fun <reified T> readAdvancements(
        playerUuid: String,
        json: Json = Json,
    ): T = readAdvancements(playerUuid, json.serializersModule.serializer(), json)

    fun <T> readAdvancements(playerUuid: String, block: (KotlinxSource) -> T): T =
        jsonFiles.read(paths.advancement(playerUuid), block)

    fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionFile? = withRegion(position, storage, dimension) {
        readRegion()
    }

    fun <T> readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: RegionReadScope.() -> T,
    ): T? = withRegion(position, storage, dimension) {
        readRegion(block)
    }

    fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = readChunk(position.region, position.local, storage, dimension)

    fun readChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = withRegion(regionPosition, storage, dimension) {
        readChunk(position)
    }

    fun <T> readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = readChunk(position.region, position.local, storage, dimension, block)

    fun <T> readChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = withRegion(regionPosition, storage, dimension) {
        readChunk(position, block)
    }

    fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = doesChunkExist(position.region, position.local, storage, dimension)

    fun doesChunkExist(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = withRegion(regionPosition, storage, dimension) {
        doesChunkExist(position)
    } == true

    fun doesRegionExist(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean {
        val path = paths.regionDirectory(storage, dimension) / "r.${position.x}.${position.z}.mca"
        val metadata = files.fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        return true
    }

    fun readChunkNbtDocument(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = readChunkNbtDocument(position.region, position.local, storage, dimension)

    fun readChunkNbtDocument(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = withRegion(regionPosition, storage, dimension) {
        readChunkNbtDocument(position)
    }

    fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readChunkNbt(position.region, position.local, deserializer, storage, dimension)

    fun <T> readChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = withRegion(regionPosition, storage, dimension) {
        readChunkNbt(position, deserializer)
    }

    inline fun <reified T> readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readChunkNbt(
        position,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        storage,
        dimension,
    )

    inline fun <reified T> readChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readChunkNbt(
        regionPosition,
        position,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        storage,
        dimension,
    )

    /** Opens one caller-owned non-locking live Region, or returns null when its file is missing. */
    fun openRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveWorldRegion? {
        val directory = paths.regionDirectory(storage, dimension)
        val path = directory / "r.${position.x}.${position.z}.mca"
        val metadata = files.fileSystem.metadataOrNull(path)
            ?: return null
        if (!metadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val reader = LiveRegionFileReader.open(files, directory, position)
        return LiveWorldRegion(reader, chunkNbtFormat)
    }

    /** Opens one live Region for [block] and always closes it afterward. */
    fun <T> withRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: LiveWorldRegion.() -> T,
    ): T? {
        val region = openRegion(position, storage, dimension) ?: return null
        return useResource(region, { it.close() }) { it.block() }
    }

    companion object {
        fun open(root: Path): LiveMinecraftWorldReader =
            open(root, LiveMinecraftWorldReaderConfiguration())

        fun open(
            root: Path,
            configuration: LiveMinecraftWorldReaderConfiguration,
        ): LiveMinecraftWorldReader = open(root, systemFileSystem, configuration)

        internal fun open(
            root: Path,
            fileSystem: FileSystem,
            configuration: LiveMinecraftWorldReaderConfiguration = LiveMinecraftWorldReaderConfiguration(),
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
                configuration = configuration,
                files = WorldFileAccess.liveReadOnly(fileSystem),
            )
        }
    }
}
