package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.datapack.DataPackFormat
import com.hiczp.minecraft.world.format.datapack.DataPackId
import com.hiczp.minecraft.world.format.datapack.WorldDataPackLoadResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okio.BufferedSource
import okio.FileSystem
import okio.Path

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
    private val utf8JsonFileStore = Utf8JsonFileStore(rawFileStore)
    private val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
    private val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
    private val playerStatisticsStore = PlayerStatisticsStore(minecraftWorldPaths, utf8JsonFileStore)
    private val playerAdvancementsStore = PlayerAdvancementsStore(minecraftWorldPaths, utf8JsonFileStore)
    private val worldDataPackReader = WorldDataPackReader(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.dataPacksDirectory,
        configuration.dataPackFormat,
    )

    val directFiles: LiveMinecraftWorldDirectFiles =
        LiveMinecraftWorldDirectFiles(rawFileStore, nbtFileStore, utf8JsonFileStore)

    fun readEnabledDataPacks(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(enabledDataPackIds)

    fun inspectEnabledFileDataPacks(enabledDataPackIds: List<DataPackId>): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(enabledDataPackIds)

    fun readEnabledDataPacks(): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(readLevelData())

    fun inspectEnabledFileDataPacks(): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(readLevelData())

    fun readLevelDataDocument(): NbtDocument = levelDataStore.readDocument()

    fun <T> readLevelData(deserializationStrategy: DeserializationStrategy<T>): T =
        levelDataStore.read(deserializationStrategy)

    fun readLevelData(): LevelDat = readLevelData(LevelDat.serializer())

    inline fun <reified T> readLevelDataAs(): T =
        readLevelData(configuration.standaloneNbtFormat.serializersModule.serializer())

    fun <T> readLevelData(block: (BufferedSource) -> T): T = levelDataStore.read(block)

    fun readPlayerDataDocument(playerUuid: String): NbtDocument? = playerDataStore.readDocument(playerUuid)

    fun <T> readPlayerData(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = playerDataStore.read(playerUuid, deserializationStrategy)

    fun readPlayerData(playerUuid: String): PlayerData? = readPlayerData(playerUuid, PlayerData.serializer())

    inline fun <reified T> readPlayerDataAs(playerUuid: String): T? = readPlayerData(
        playerUuid,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    fun <T> readPlayerData(playerUuid: String, block: (BufferedSource) -> T): T? =
        playerDataStore.read(playerUuid, block)

    fun readSavedDataDocument(identifier: String, savedDataScope: SavedDataScope): NbtDocument? =
        SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore).readDocument(identifier)

    fun <T> readSavedData(
        identifier: String,
        deserializationStrategy: DeserializationStrategy<T>,
        savedDataScope: SavedDataScope,
    ): T? = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore)
        .read(identifier, deserializationStrategy)

    inline fun <reified T> readSavedData(identifier: String, savedDataScope: SavedDataScope): T? = readSavedData(
        identifier,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        savedDataScope,
    )

    fun <T> readSavedData(
        identifier: String,
        savedDataScope: SavedDataScope,
        block: (BufferedSource) -> T,
    ): T? = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore).read(identifier, block)

    fun readWorldBorderData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): SavedDataFile<WorldBorderData>? = readSavedData(
        WORLD_BORDER_IDENTIFIER,
        SavedDataFile.serializer(WorldBorderData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    fun readChunkTicketsData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): SavedDataFile<ChunkTicketsData>? = readSavedData(
        CHUNK_TICKETS_IDENTIFIER,
        SavedDataFile.serializer(ChunkTicketsData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    fun readRaidsData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): SavedDataFile<RaidsData>? = readSavedData(
        RAIDS_IDENTIFIER,
        SavedDataFile.serializer(RaidsData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    fun readEnderDragonFightData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.End,
    ): SavedDataFile<EnderDragonFightData>? = readSavedData(
        ENDER_DRAGON_FIGHT_IDENTIFIER,
        SavedDataFile.serializer(EnderDragonFightData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    fun readStatisticsText(playerUuid: String): String = playerStatisticsStore.readText(playerUuid)

    fun readStatisticsJson(playerUuid: String, json: Json = Json): JsonElement =
        playerStatisticsStore.readJson(playerUuid, json)

    fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = playerStatisticsStore.read(playerUuid, deserializationStrategy, json)

    inline fun <reified T> readStatistics(playerUuid: String, json: Json = Json): T =
        readStatistics(playerUuid, json.serializersModule.serializer(), json)

    fun <T> readStatistics(playerUuid: String, block: (BufferedSource) -> T): T =
        playerStatisticsStore.read(playerUuid, block)

    fun readAdvancementsText(playerUuid: String): String = playerAdvancementsStore.readText(playerUuid)

    fun readAdvancementsJson(playerUuid: String, json: Json = Json): JsonElement =
        playerAdvancementsStore.readJson(playerUuid, json)

    fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = playerAdvancementsStore.read(playerUuid, deserializationStrategy, json)

    inline fun <reified T> readAdvancements(playerUuid: String, json: Json = Json): T =
        readAdvancements(playerUuid, json.serializersModule.serializer(), json)

    fun <T> readAdvancements(playerUuid: String, block: (BufferedSource) -> T): T =
        playerAdvancementsStore.read(playerUuid, block)

    fun listRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = listRegionPositions(RegionStorageDirectory.CHUNKS, dimensionDirectory)

    fun hasRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openRegion(regionPosition, dimensionDirectory).use(LiveRegionHandle::hasRegion)

    fun openRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveRegionHandle = openRegion(RegionStorageDirectory.CHUNKS, dimensionDirectory, regionPosition)

    fun listEntityRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = listRegionPositions(RegionStorageDirectory.ENTITIES, dimensionDirectory)

    fun hasEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openEntityRegion(regionPosition, dimensionDirectory).use(LiveEntityRegionHandle::hasRegion)

    fun openEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveEntityRegionHandle = LiveEntityRegionHandle(
        openRegion(RegionStorageDirectory.ENTITIES, dimensionDirectory, regionPosition),
    )

    fun listPoiRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = listRegionPositions(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionDirectory)

    fun hasPoiRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openPoiRegion(regionPosition, dimensionDirectory).use(LivePoiRegionHandle::hasRegion)

    fun openPoiRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): LivePoiRegionHandle = LivePoiRegionHandle(
        openRegion(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionDirectory, regionPosition),
    )

    private fun listRegionPositions(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): List<RegionPosition> = snapshotRegionPositions(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionDirectory),
    )

    private fun openRegion(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        regionPosition: RegionPosition,
    ): LiveRegionHandle = LiveRegionHandle(
        RegionFileStore(
            directory = minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionDirectory),
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
