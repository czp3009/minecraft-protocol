package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.datapack.DataPackFormat
import com.hiczp.minecraft.world.format.datapack.DataPackId
import com.hiczp.minecraft.world.format.datapack.WorldDataPackLoadResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path

/** Formats and Region-storage policy shared by every dimension opened under one world lease. */
data class MinecraftWorldAccessConfiguration(
    val regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    val standaloneNbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    val dataPackFormat: DataPackFormat = DataPackFormat(),
) {
    init {
        standaloneNbtFormat.requireStandaloneWorldRoot()
    }
}

/**
 * A mutable system-filesystem world lease backed by the vanilla `session.lock`.
 *
 * Semantic operations coordinate by complete logical resource identity. [directFiles] bypasses
 * that coordination while still joining the world close barrier. Blocking filesystem and codec
 * work runs in the calling coroutine's context.
 */
class MinecraftWorldAccess private constructor(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val configuration: MinecraftWorldAccessConfiguration,
    private val worldOperationLifecycle: WorldOperationLifecycle,
    private val logicalResourceCoordinator: LogicalResourceCoordinator<WorldResourceKey>,
    private val coordinatedRegionRegistry: CoordinatedRegionRegistry,
    private val levelDataStore: LevelDataStore,
    private val playerDataStore: PlayerDataStore,
    private val playerStatisticsStore: PlayerStatisticsStore,
    private val playerAdvancementsStore: PlayerAdvancementsStore,
    private val nbtFileStore: NbtFileStore,
    private val utf8JsonFileStore: Utf8JsonFileStore,
    private val worldDataPackReader: WorldDataPackReader,
) {
    val directFiles: MinecraftWorldDirectFiles = MinecraftWorldDirectFiles(
        worldOperationLifecycle,
        nbtFileStore.rawFileStore,
        nbtFileStore,
        utf8JsonFileStore,
    )

    suspend fun readEnabledDataPacks(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        withUncoordinatedOperation { worldDataPackReader.readEnabledDataPacks(enabledDataPackIds) }

    suspend fun inspectEnabledFileDataPacks(
        enabledDataPackIds: List<DataPackId>,
    ): List<DataPackInspection> = withUncoordinatedOperation {
        worldDataPackReader.inspectEnabledFileDataPacks(enabledDataPackIds)
    }

    suspend fun readEnabledDataPacks(): WorldDataPackLoadResult =
        withUncoordinatedOperation {
            worldDataPackReader.readEnabledDataPacks(readLevelDataWithinOperation<LevelDat>())
        }

    suspend fun inspectEnabledFileDataPacks(): List<DataPackInspection> =
        withUncoordinatedOperation {
            worldDataPackReader.inspectEnabledFileDataPacks(readLevelDataWithinOperation<LevelDat>())
        }

    suspend fun readLevelDataDocument(): NbtDocument = readRecovering(
        WorldResourceKey.LevelData,
        levelDataStore::readDocumentForSharedAccess,
        levelDataStore::readDocument,
    )

    suspend fun <T> readLevelData(deserializationStrategy: DeserializationStrategy<T>): T = readRecovering(
        WorldResourceKey.LevelData,
        { levelDataStore.readForSharedAccess(deserializationStrategy) },
        { levelDataStore.read(deserializationStrategy) },
    )

    suspend fun readLevelData(): LevelDat = readLevelData(LevelDat.serializer())

    suspend inline fun <reified T> readLevelDataAs(): T =
        readLevelData(configuration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun <T> readLevelData(block: (BufferedSource) -> T): T = readRecovering(
        WorldResourceKey.LevelData,
        { levelDataStore.readForSharedAccess(block) },
        { levelDataStore.read(block) },
    )

    suspend fun writeLevelDataDocument(nbtDocument: NbtDocument) =
        write(WorldResourceKey.LevelData) { levelDataStore.writeDocument(nbtDocument) }

    suspend fun <T> writeLevelData(serializationStrategy: SerializationStrategy<T>, value: T) =
        write(WorldResourceKey.LevelData) { levelDataStore.write(serializationStrategy, value) }

    suspend fun writeLevelData(levelDat: LevelDat) = writeLevelData(LevelDat.serializer(), levelDat)

    suspend inline fun <reified T> writeLevelDataAs(value: T) =
        writeLevelData(configuration.standaloneNbtFormat.serializersModule.serializer(), value)

    suspend fun writeLevelData(block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.LevelData) { levelDataStore.write(block) }

    suspend fun readPlayerDataDocument(playerUuid: String): NbtDocument? = readRecovering(
        WorldResourceKey.PlayerData(playerUuid),
        { playerDataStore.readDocumentForSharedAccess(playerUuid) },
        { playerDataStore.readDocument(playerUuid) },
    )

    suspend fun <T> readPlayerData(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readRecovering(
        WorldResourceKey.PlayerData(playerUuid),
        { playerDataStore.readForSharedAccess(playerUuid, deserializationStrategy) },
        { playerDataStore.read(playerUuid, deserializationStrategy) },
    )

    suspend fun readPlayerData(playerUuid: String): PlayerData? =
        readPlayerData(playerUuid, PlayerData.serializer())

    suspend inline fun <reified T> readPlayerDataAs(playerUuid: String): T? = readPlayerData(
        playerUuid,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    suspend fun <T> readPlayerData(playerUuid: String, block: (BufferedSource) -> T): T? = readRecovering(
        WorldResourceKey.PlayerData(playerUuid),
        { playerDataStore.readForSharedAccess(playerUuid, block) },
        { playerDataStore.read(playerUuid, block) },
    )

    suspend fun writePlayerDataDocument(playerUuid: String, nbtDocument: NbtDocument) =
        write(WorldResourceKey.PlayerData(playerUuid)) { playerDataStore.writeDocument(playerUuid, nbtDocument) }

    suspend fun <T> writePlayerData(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = write(WorldResourceKey.PlayerData(playerUuid)) {
        playerDataStore.write(playerUuid, serializationStrategy, value)
    }

    suspend fun writePlayerData(playerUuid: String, playerData: PlayerData) =
        writePlayerData(playerUuid, PlayerData.serializer(), playerData)

    suspend inline fun <reified T> writePlayerDataAs(playerUuid: String, value: T) = writePlayerData(
        playerUuid,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        value,
    )

    suspend fun writePlayerData(playerUuid: String, block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.PlayerData(playerUuid)) { playerDataStore.write(playerUuid, block) }

    suspend fun readSavedDataDocument(identifier: String, savedDataScope: SavedDataScope): NbtDocument? =
        withSavedData(identifier, savedDataScope, write = false) { savedDataStore ->
            savedDataStore.readDocument(identifier)
        }

    suspend fun <T> readSavedData(
        identifier: String,
        deserializationStrategy: DeserializationStrategy<T>,
        savedDataScope: SavedDataScope,
    ): T? = withSavedData(identifier, savedDataScope, write = false) { savedDataStore ->
        savedDataStore.read(identifier, deserializationStrategy)
    }

    suspend inline fun <reified T> readSavedData(
        identifier: String,
        savedDataScope: SavedDataScope,
    ): T? = readSavedData(
        identifier,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        savedDataScope,
    )

    suspend fun <T> readSavedData(
        identifier: String,
        savedDataScope: SavedDataScope,
        block: (BufferedSource) -> T,
    ): T? = withSavedData(identifier, savedDataScope, write = false) { savedDataStore ->
        savedDataStore.read(identifier, block)
    }

    suspend fun writeSavedDataDocument(
        identifier: String,
        nbtDocument: NbtDocument,
        savedDataScope: SavedDataScope,
    ) = withSavedData(identifier, savedDataScope, write = true) { savedDataStore ->
        savedDataStore.writeDocument(identifier, nbtDocument)
    }

    suspend fun <T> writeSavedData(
        identifier: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        savedDataScope: SavedDataScope,
    ) = withSavedData(identifier, savedDataScope, write = true) { savedDataStore ->
        savedDataStore.write(identifier, serializationStrategy, value)
    }

    suspend inline fun <reified T> writeSavedData(
        identifier: String,
        value: T,
        savedDataScope: SavedDataScope,
    ) = writeSavedData(
        identifier,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        value,
        savedDataScope,
    )

    suspend fun writeSavedData(
        identifier: String,
        savedDataScope: SavedDataScope,
        block: (BufferedSink) -> Unit,
    ) = withSavedData(identifier, savedDataScope, write = true) { savedDataStore ->
        savedDataStore.write(identifier, block)
    }

    suspend fun readWorldBorderData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): SavedDataFile<WorldBorderData>? = readSavedData(
        WORLD_BORDER_IDENTIFIER,
        SavedDataFile.serializer(WorldBorderData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun writeWorldBorderData(
        worldBorderData: SavedDataFile<WorldBorderData>,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeSavedData(
        WORLD_BORDER_IDENTIFIER,
        SavedDataFile.serializer(WorldBorderData.serializer()),
        worldBorderData,
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun readChunkTicketsData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): SavedDataFile<ChunkTicketsData>? = readSavedData(
        CHUNK_TICKETS_IDENTIFIER,
        SavedDataFile.serializer(ChunkTicketsData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun writeChunkTicketsData(
        chunkTicketsData: SavedDataFile<ChunkTicketsData>,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeSavedData(
        CHUNK_TICKETS_IDENTIFIER,
        SavedDataFile.serializer(ChunkTicketsData.serializer()),
        chunkTicketsData,
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun readRaidsData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): SavedDataFile<RaidsData>? = readSavedData(
        RAIDS_IDENTIFIER,
        SavedDataFile.serializer(RaidsData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun writeRaidsData(
        raidsData: SavedDataFile<RaidsData>,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeSavedData(
        RAIDS_IDENTIFIER,
        SavedDataFile.serializer(RaidsData.serializer()),
        raidsData,
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun readEnderDragonFightData(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.End,
    ): SavedDataFile<EnderDragonFightData>? = readSavedData(
        ENDER_DRAGON_FIGHT_IDENTIFIER,
        SavedDataFile.serializer(EnderDragonFightData.serializer()),
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun writeEnderDragonFightData(
        enderDragonFightData: SavedDataFile<EnderDragonFightData>,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.End,
    ) = writeSavedData(
        ENDER_DRAGON_FIGHT_IDENTIFIER,
        SavedDataFile.serializer(EnderDragonFightData.serializer()),
        enderDragonFightData,
        SavedDataScope.Dimension(dimensionDirectory),
    )

    suspend fun readStatisticsText(playerUuid: String): String =
        read(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.readText(playerUuid) }

    suspend fun readStatisticsJson(playerUuid: String, json: Json = Json): JsonElement =
        read(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.readJson(playerUuid, json) }

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = read(WorldResourceKey.Statistics(playerUuid)) {
        playerStatisticsStore.read(playerUuid, deserializationStrategy, json)
    }

    suspend inline fun <reified T> readStatistics(playerUuid: String, json: Json = Json): T =
        readStatistics(playerUuid, json.serializersModule.serializer(), json)

    suspend fun <T> readStatistics(playerUuid: String, block: (BufferedSource) -> T): T =
        read(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.read(playerUuid, block) }

    suspend fun writeStatisticsText(playerUuid: String, text: String) =
        write(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.writeText(playerUuid, text) }

    suspend fun writeStatisticsJson(playerUuid: String, jsonElement: JsonElement, json: Json = Json) =
        write(WorldResourceKey.Statistics(playerUuid)) {
            playerStatisticsStore.writeJson(playerUuid, jsonElement, json)
        }

    suspend fun <T> writeStatistics(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = write(WorldResourceKey.Statistics(playerUuid)) {
        playerStatisticsStore.write(playerUuid, serializationStrategy, value, json)
    }

    suspend inline fun <reified T> writeStatistics(playerUuid: String, value: T, json: Json = Json) =
        writeStatistics(playerUuid, json.serializersModule.serializer(), value, json)

    suspend fun writeStatistics(playerUuid: String, block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.write(playerUuid, block) }

    suspend fun readAdvancementsText(playerUuid: String): String =
        read(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.readText(playerUuid) }

    suspend fun readAdvancementsJson(playerUuid: String, json: Json = Json): JsonElement =
        read(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.readJson(playerUuid, json) }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = read(WorldResourceKey.Advancements(playerUuid)) {
        playerAdvancementsStore.read(playerUuid, deserializationStrategy, json)
    }

    suspend inline fun <reified T> readAdvancements(playerUuid: String, json: Json = Json): T =
        readAdvancements(playerUuid, json.serializersModule.serializer(), json)

    suspend fun <T> readAdvancements(playerUuid: String, block: (BufferedSource) -> T): T =
        read(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.read(playerUuid, block) }

    suspend fun writeAdvancementsText(playerUuid: String, text: String) =
        write(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.writeText(playerUuid, text) }

    suspend fun writeAdvancementsJson(playerUuid: String, jsonElement: JsonElement, json: Json = Json) =
        write(WorldResourceKey.Advancements(playerUuid)) {
            playerAdvancementsStore.writeJson(playerUuid, jsonElement, json)
        }

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = write(WorldResourceKey.Advancements(playerUuid)) {
        playerAdvancementsStore.write(playerUuid, serializationStrategy, value, json)
    }

    suspend inline fun <reified T> writeAdvancements(playerUuid: String, value: T, json: Json = Json) =
        writeAdvancements(playerUuid, json.serializersModule.serializer(), value, json)

    suspend fun writeAdvancements(playerUuid: String, block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.write(playerUuid, block) }

    suspend fun listRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = listRegionPositions(RegionStorageDirectory.CHUNKS, dimensionDirectory)

    suspend fun hasRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openRegion(regionPosition, dimensionDirectory).use(RegionHandle::hasRegion)

    suspend fun openRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionHandle = openRegion(RegionStorageDirectory.CHUNKS, dimensionDirectory, regionPosition)

    suspend fun listEntityRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = listRegionPositions(RegionStorageDirectory.ENTITIES, dimensionDirectory)

    suspend fun hasEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openEntityRegion(regionPosition, dimensionDirectory).use(EntityRegionHandle::hasRegion)

    suspend fun openEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): EntityRegionHandle = EntityRegionHandle(
        openRegion(RegionStorageDirectory.ENTITIES, dimensionDirectory, regionPosition),
    )

    suspend fun listPoiRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = listRegionPositions(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionDirectory)

    suspend fun hasPoiRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openPoiRegion(regionPosition, dimensionDirectory).use(PoiRegionHandle::hasRegion)

    suspend fun openPoiRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): PoiRegionHandle = PoiRegionHandle(
        openRegion(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionDirectory, regionPosition),
    )

    suspend fun flush() = worldOperationLifecycle.withOperation { coordinatedRegionRegistry.flush() }

    suspend fun close() = worldOperationLifecycle.close()

    suspend fun <T> use(block: suspend (MinecraftWorldAccess) -> T): T =
        useSuspendingResource(this, MinecraftWorldAccess::close, block)

    internal suspend fun activeLogicalResourceCount(): Int = logicalResourceCoordinator.activeEntryCount()

    internal suspend fun activeLogicalResourceUsers(): Int = logicalResourceCoordinator.activeUsers()

    internal suspend fun activeRegionDirectoryCount(): Int = coordinatedRegionRegistry.activeDirectoryCount()

    internal suspend fun activeRegionDirectoryUsers(): Int = coordinatedRegionRegistry.activeDirectoryUsers()

    internal suspend fun activeWorldOperations(): Int = worldOperationLifecycle.activeUsers()

    private suspend fun <T> read(key: WorldResourceKey, block: () -> T): T =
        worldOperationLifecycle.withOperation { logicalResourceCoordinator.read(key) { block() } }

    private suspend fun <T> write(key: WorldResourceKey, block: () -> T): T =
        worldOperationLifecycle.withOperation { logicalResourceCoordinator.write(key) { block() } }

    private suspend fun <T> readRecovering(
        key: WorldResourceKey,
        sharedRead: () -> CoordinatedRead<T>,
        exclusiveRead: () -> T,
    ): T = worldOperationLifecycle.withOperation {
        when (val coordinatedRead = logicalResourceCoordinator.read(key) { sharedRead() }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalResourceCoordinator.write(key) { exclusiveRead() }
        }
    }

    private suspend fun <T> withSavedData(
        identifier: String,
        savedDataScope: SavedDataScope,
        write: Boolean,
        block: (SavedDataStore) -> T,
    ): T {
        val path = minecraftWorldPaths.savedData(identifier, savedDataScope)
        val key = WorldResourceKey.SavedData(path)
        val savedDataStore = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore)
        return if (write) write(key) { block(savedDataStore) } else read(key) { block(savedDataStore) }
    }

    private suspend fun <T> withUncoordinatedOperation(block: suspend () -> T): T =
        worldOperationLifecycle.withOperation { block() }

    private suspend inline fun <reified T> readLevelDataWithinOperation(): T {
        val serializer = configuration.standaloneNbtFormat.serializersModule.serializer<T>()
        return when (val coordinatedRead = logicalResourceCoordinator.read(WorldResourceKey.LevelData) {
            levelDataStore.readForSharedAccess(serializer)
        }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalResourceCoordinator.write(WorldResourceKey.LevelData) {
                levelDataStore.read(serializer)
            }
        }
    }

    private suspend fun listRegionPositions(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): List<RegionPosition> = withUncoordinatedOperation {
        snapshotRegionPositions(
            nbtFileStore.fileSystem,
            minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionDirectory),
        )
    }

    private suspend fun openRegion(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        regionPosition: RegionPosition,
    ): RegionHandle {
        val worldOperationPin = worldOperationLifecycle.acquire()
        var transferred = false
        return withCleanup(cleanup = { if (transferred) null else worldOperationPin.release() }) {
            val regionHandle = coordinatedRegionRegistry.openRegion(
                regionStorageDirectory,
                dimensionDirectory,
                regionPosition,
                { failure -> worldOperationPin.release(failure) },
            )
            transferred = true
            regionHandle
        }
    }

    companion object {
        fun open(root: Path): MinecraftWorldAccess = open(root, MinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            configuration: MinecraftWorldAccessConfiguration,
        ): MinecraftWorldAccess {
            systemFileSystem.createDirectories(root)
            val minecraftWorldPaths = MinecraftWorldPaths(root)
            val worldDirectoryLock = acquireWorldDirectoryLock(minecraftWorldPaths.sessionLock)
            return create(
                minecraftWorldPaths,
                systemFileSystem,
                configuration,
                worldDirectoryLock,
            )
        }

        internal fun create(
            minecraftWorldPaths: MinecraftWorldPaths,
            fileSystem: FileSystem,
            configuration: MinecraftWorldAccessConfiguration = MinecraftWorldAccessConfiguration(),
            worldDirectoryLock: WorldDirectoryLock? = null,
        ): MinecraftWorldAccess {
            val worldFileAccess = WorldFileAccess.mutable(fileSystem)
            val rawFileStore = RawFileStore(worldFileAccess)
            val nbtFileStore = NbtFileStore(
                rawFileStore,
                configuration.standaloneNbtFormat,
            )
            val utf8JsonFileStore = Utf8JsonFileStore(rawFileStore)
            val worldOperationLifecycle = WorldOperationLifecycle(minecraftWorldPaths, worldDirectoryLock)
            return MinecraftWorldAccess(
                minecraftWorldPaths = minecraftWorldPaths,
                configuration = configuration,
                worldOperationLifecycle = worldOperationLifecycle,
                logicalResourceCoordinator = LogicalResourceCoordinator(),
                coordinatedRegionRegistry = CoordinatedRegionRegistry(
                    minecraftWorldPaths,
                    worldFileAccess,
                    configuration.chunkNbtFormat,
                    configuration.regionStorageConfiguration,
                ),
                levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore),
                playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore),
                playerStatisticsStore = PlayerStatisticsStore(minecraftWorldPaths, utf8JsonFileStore),
                playerAdvancementsStore = PlayerAdvancementsStore(minecraftWorldPaths, utf8JsonFileStore),
                nbtFileStore = nbtFileStore,
                utf8JsonFileStore = utf8JsonFileStore,
                worldDataPackReader = WorldDataPackReader(
                    fileSystem,
                    minecraftWorldPaths.dataPacksDirectory,
                    configuration.dataPackFormat,
                ),
            )
        }

        fun isLocked(root: Path): Boolean = isWorldDirectoryLocked(MinecraftWorldPaths(root).sessionLock)
    }
}

private sealed interface WorldResourceKey {
    data object LevelData : WorldResourceKey

    data class PlayerData(val playerUuid: String) : WorldResourceKey

    data class SavedData(val path: Path) : WorldResourceKey

    data class Statistics(val playerUuid: String) : WorldResourceKey

    data class Advancements(val playerUuid: String) : WorldResourceKey
}

internal const val WORLD_BORDER_IDENTIFIER = "minecraft:world_border"
internal const val CHUNK_TICKETS_IDENTIFIER = "minecraft:chunk_tickets"
internal const val RAIDS_IDENTIFIER = "minecraft:raids"
internal const val ENDER_DRAGON_FIGHT_IDENTIFIER = "minecraft:ender_dragon_fight"
