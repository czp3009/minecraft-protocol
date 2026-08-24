package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.LevelDat
import com.hiczp.minecraft.world.format.RegionPosition
import com.hiczp.minecraft.world.format.datapack.DataPackFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.FileSystem
import okio.Path
import kotlinx.io.Source as KotlinxSource

/** Read formats shared by every operation and logical Region opened through one live access. */
data class LiveMinecraftWorldAccessConfiguration(
    val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    val standaloneNbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    val dataPackFormat: DataPackFormat = DataPackFormat(),
) {
    init {
        standaloneNbtFormat.requireStandaloneWorldRoot()
    }
}

/**
 * Non-locking read access to a world that may be modified concurrently.
 *
 * This class takes neither `session.lock` nor per-file operating-system or in-process exclusion.
 * Reads may observe stale or torn state and propagate the resulting I/O, format, or decompression
 * failure. The access never repairs or mutates files and has no close lifecycle.
 *
 * Public operations may be called concurrently. This class does not create a thread pool or select
 * a dispatcher; blocking filesystem I/O, NBT work, and compression run synchronously on the
 * calling thread and are not automatically main-safe.
 */
class LiveMinecraftWorldAccess private constructor(
    val paths: MinecraftWorldPaths,
    val configuration: LiveMinecraftWorldAccessConfiguration,
    private val files: WorldFileAccess,
) {
    val chunkNbtFormat: CompressedNbtFormat
        get() = configuration.chunkNbtFormat

    private val nbtFiles = NbtFileStore(files, configuration.standaloneNbtFormat)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)
    private val dataPackStore = WorldDataPackStore(files.fileSystem, paths.dataPacks, configuration.dataPackFormat)

    fun readDataPacks(enabledReferences: List<String>): LoadedWorldDataPacks =
        dataPackStore.readEnabled(enabledReferences)

    fun inspectDataPacks(enabledReferences: List<String>): List<DataPackInspection> =
        dataPackStore.inspectEnabled(enabledReferences)

    fun readEnabledDataPacks(): LoadedWorldDataPacks = dataPackStore.readEnabled(readLevelData<LevelDat>())

    fun inspectEnabledDataPacks(): List<DataPackInspection> =
        dataPackStore.inspectEnabled(readLevelData<LevelDat>())

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

    /**
     * Lists a detached snapshot of every canonical Chunk Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is
     * O(n), may be slow, and may exhaust memory for an extremely large world. Concurrent file
     * changes are not observed transactionally.
     */
    fun listRegionPositions(
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = snapshotRegionPositions(
        files.fileSystem,
        paths.regionDirectory(RegionStorageDirectory.CHUNKS, dimension),
    )

    fun hasRegion(
        position: RegionPosition,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openRegion(position, dimension).hasRegion()

    /**
     * Creates a stateless logical Region handle without touching the filesystem.
     *
     * A missing Region still has a handle; its reads return false, null, or an empty list.
     */
    fun openRegion(
        position: RegionPosition,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveRegionHandle = LiveRegionHandle(
        fileSystem = files.fileSystem,
        directory = paths.regionDirectory(RegionStorageDirectory.CHUNKS, dimension),
        position = position,
        chunkNbtFormat = chunkNbtFormat,
    )

    /**
     * Lists a detached snapshot of every canonical Entity Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is O(n), may be slow, and
     * may exhaust memory for an extremely large world. Concurrent file changes are not observed transactionally.
     */
    fun listEntityRegionPositions(
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = snapshotRegionPositions(
        files.fileSystem,
        paths.regionDirectory(RegionStorageDirectory.ENTITIES, dimension),
    )

    fun hasEntityRegion(
        position: RegionPosition,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openEntityRegion(position, dimension).hasRegion()

    /** Creates a stateless logical Entity Region handle without touching the filesystem. */
    fun openEntityRegion(
        position: RegionPosition,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveEntityRegionHandle = LiveEntityRegionHandle(
        LiveRegionHandle(
            fileSystem = files.fileSystem,
            directory = paths.regionDirectory(RegionStorageDirectory.ENTITIES, dimension),
            position = position,
            chunkNbtFormat = chunkNbtFormat,
        ),
    )

    companion object {
        fun open(root: Path): LiveMinecraftWorldAccess =
            open(root, LiveMinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            configuration: LiveMinecraftWorldAccessConfiguration,
        ): LiveMinecraftWorldAccess = open(root, systemFileSystem, configuration)

        internal fun open(
            root: Path,
            fileSystem: FileSystem,
            configuration: LiveMinecraftWorldAccessConfiguration = LiveMinecraftWorldAccessConfiguration(),
        ): LiveMinecraftWorldAccess {
            val metadata = fileSystem.metadataOrNull(root)
                ?: throw WorldIOException("World directory does not exist: $root")
            if (!metadata.isDirectory) {
                throw WorldIOException("World path is not a directory: $root")
            }
            return LiveMinecraftWorldAccess(
                paths = MinecraftWorldPaths(root),
                configuration = configuration,
                files = WorldFileAccess.liveReadOnly(fileSystem),
            )
        }
    }
}
