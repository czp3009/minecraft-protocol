package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import kotlin.jvm.JvmName

/** Read formats shared by every operation and logical Region opened through one live access. */
data class LiveMinecraftWorldAccessConfiguration(
    val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    val standaloneNbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    val standaloneJson: Json = Json,
    val dataPackFormat: DataPackFormat = DataPackFormat(),
) {
    init {
        standaloneNbtFormat.requireStandaloneWorldRoot()
    }
}

/**
 * Synchronous non-locking read access to a world that another process may be changing.
 *
 * This facade has no `session.lock`, close lifecycle, logical coordinator, or Region registry.
 * Every returned live Region handle independently owns its physical resource.
 */
class LiveMinecraftWorldAccess private constructor(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val configuration: LiveMinecraftWorldAccessConfiguration,
    private val worldFileAccess: WorldFileAccess,
) {
    private val rawFileStore = RawFileStore(worldFileAccess)
    private val nbtFileStore = NbtFileStore(rawFileStore, configuration.standaloneNbtFormat)
    private val utf8JsonFileStore = Utf8JsonFileStore(rawFileStore, configuration.standaloneJson)
    private val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
    private val worldDataPackReader = WorldDataPackReader(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.dataPacksDirectory,
        configuration.dataPackFormat,
    )

    val directFiles: LiveMinecraftWorldDirectFiles =
        LiveMinecraftWorldDirectFiles(rawFileStore, nbtFileStore, utf8JsonFileStore)

    val players: LiveMinecraftWorldPlayers = LiveMinecraftWorldPlayers(
        configuration,
        PlayerDataStore(minecraftWorldPaths, nbtFileStore),
        PlayerStatisticsStore(minecraftWorldPaths, utf8JsonFileStore),
        PlayerAdvancementsStore(minecraftWorldPaths, utf8JsonFileStore),
    )

    val data: LiveMinecraftSavedData = LiveMinecraftSavedData(this, SavedDataScope.WorldRoot)

    val dataPacks: LiveMinecraftWorldDataPacks = LiveMinecraftWorldDataPacks(this)

    val dimensions: LiveMinecraftWorldDimensions = LiveMinecraftWorldDimensions(this)

    internal fun inspectDataPack(dataPackId: DataPackId): DataPackInspection =
        worldDataPackReader.inspectDataPack(dataPackId)

    internal fun readDataPack(dataPackId: DataPackId): DataPack = worldDataPackReader.readDataPack(dataPackId)

    internal fun readDataPack(dataPackInspection: DataPackInspection): DataPack =
        worldDataPackReader.readDataPack(dataPackInspection)

    internal fun readDataPackArchive(dataPackId: DataPackId): DataPackArchive =
        worldDataPackReader.readDataPackArchive(dataPackId)

    internal fun readDataPackArchive(dataPackInspection: DataPackInspection): DataPackArchive =
        worldDataPackReader.readDataPackArchive(dataPackInspection)

    internal fun readDataPackFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = worldDataPackReader.readDataPackFile(dataPackId, dataPackFilePath)

    internal fun <T> readDataPackFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = worldDataPackReader.readDataPackFile(dataPackId, dataPackFilePath, block)

    internal fun readDataPackFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = worldDataPackReader.readDataPackFile(dataPackInspection, dataPackFilePath)

    internal fun <T> readDataPackFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = worldDataPackReader.readDataPackFile(dataPackInspection, dataPackFilePath, block)

    internal fun readEnabledDataPacks(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(enabledDataPackIds)

    internal fun inspectEnabledFileDataPacks(enabledDataPackIds: List<DataPackId>): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(enabledDataPackIds)

    internal fun readEnabledDataPacks(): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(readLevelData())

    internal fun inspectEnabledFileDataPacks(): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(readLevelData())

    fun readLevelDataDocument(): NbtDocument = levelDataStore.readDocument()

    fun <T> readLevelData(deserializationStrategy: DeserializationStrategy<T>): T =
        levelDataStore.read(deserializationStrategy)

    fun readLevelData(): LevelDat = readLevelData<LevelDat>()

    @JvmName("readTypedLevelData")
    inline fun <reified T> readLevelData(): T =
        readLevelData(configuration.standaloneNbtFormat.serializersModule.serializer())

    fun <T> readLevelData(block: (BufferedSource) -> T): T = levelDataStore.read(block)

    internal fun readSavedDataDocument(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
    ): NbtDocument? = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore).readDocument(savedDataId)

    internal fun <T> readSavedData(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore)
        .read(savedDataId, deserializationStrategy)

    internal fun <T> readSavedData(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
        block: (BufferedSource) -> T,
    ): T? = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore).read(savedDataId, block)

    internal fun listRegionPositions(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionId: DimensionId,
    ): List<RegionPosition> = snapshotRegionPositions(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionId),
    )

    internal fun openRegion(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
    ): LiveRegionHandle = LiveRegionHandle(
        RegionFileStore(
            directory = minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionId),
            worldFileAccess = worldFileAccess,
            chunkNbtFormat = configuration.chunkNbtFormat,
        ),
        regionPosition,
    )

    companion object {
        fun open(root: Path): LiveMinecraftWorldAccess = open(root, LiveMinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            configuration: LiveMinecraftWorldAccessConfiguration,
        ): LiveMinecraftWorldAccess = open(root, systemFileSystem, configuration)

        internal fun open(
            root: Path,
            fileSystem: FileSystem,
            configuration: LiveMinecraftWorldAccessConfiguration = LiveMinecraftWorldAccessConfiguration(),
        ): LiveMinecraftWorldAccess {
            val fileMetadata = fileSystem.metadataOrNull(root)
                ?: throw WorldIOException("World directory does not exist: $root")
            if (!fileMetadata.isDirectory) throw WorldIOException("World path is not a directory: $root")
            return LiveMinecraftWorldAccess(
                MinecraftWorldPaths(root),
                configuration,
                WorldFileAccess.liveReadOnly(fileSystem),
            )
        }
    }
}
