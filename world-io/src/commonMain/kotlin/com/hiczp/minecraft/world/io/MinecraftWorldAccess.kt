package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import kotlin.jvm.JvmName

/** Formats and Region-storage policy shared by every dimension opened under one world lease. */
data class MinecraftWorldAccessConfiguration(
    val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    val standaloneNbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    val standaloneJson: Json = Json,
    val dataPackFormat: DataPackFormat = DataPackFormat(),
    val regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
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
    val players: MinecraftWorldPlayers,
    private val worldOperationLifecycle: WorldOperationLifecycle,
    private val logicalResourceCoordinator: LogicalResourceCoordinator<WorldResourceKey>,
    private val coordinatedRegionRegistry: CoordinatedRegionRegistry,
    private val levelDataStore: LevelDataStore,
    private val nbtFileStore: NbtFileStore,
    private val utf8JsonFileStore: Utf8JsonFileStore,
    private val worldDataPackReader: WorldDataPackReader,
) {
    val data: MinecraftSavedData = MinecraftSavedData(this, SavedDataScope.WorldRoot)

    val dataPacks: MinecraftWorldDataPacks = MinecraftWorldDataPacks(this)

    val dimensions: MinecraftWorldDimensions = MinecraftWorldDimensions(this)

    val directFiles: MinecraftWorldDirectFiles = MinecraftWorldDirectFiles(
        worldOperationLifecycle,
        nbtFileStore.rawFileStore,
        nbtFileStore,
        utf8JsonFileStore,
    )

    internal suspend fun inspectDataPack(dataPackId: DataPackId): DataPackInspection =
        withUncoordinatedOperation { worldDataPackReader.inspectDataPack(dataPackId) }

    internal suspend fun readDataPack(dataPackId: DataPackId): DataPack =
        withUncoordinatedOperation { worldDataPackReader.readDataPack(dataPackId) }

    internal suspend fun readDataPack(dataPackInspection: DataPackInspection): DataPack =
        withUncoordinatedOperation { worldDataPackReader.readDataPack(dataPackInspection) }

    internal suspend fun readDataPackArchive(dataPackId: DataPackId): DataPackArchive =
        withUncoordinatedOperation { worldDataPackReader.readDataPackArchive(dataPackId) }

    internal suspend fun readDataPackArchive(dataPackInspection: DataPackInspection): DataPackArchive =
        withUncoordinatedOperation { worldDataPackReader.readDataPackArchive(dataPackInspection) }

    internal suspend fun readDataPackFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = withUncoordinatedOperation {
        worldDataPackReader.readDataPackFile(dataPackId, dataPackFilePath)
    }

    internal suspend fun <T> readDataPackFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = withUncoordinatedOperation {
        worldDataPackReader.readDataPackFile(dataPackId, dataPackFilePath, block)
    }

    internal suspend fun readDataPackFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = withUncoordinatedOperation {
        worldDataPackReader.readDataPackFile(dataPackInspection, dataPackFilePath)
    }

    internal suspend fun <T> readDataPackFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = withUncoordinatedOperation {
        worldDataPackReader.readDataPackFile(dataPackInspection, dataPackFilePath, block)
    }

    internal suspend fun readEnabledDataPacks(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        withUncoordinatedOperation { worldDataPackReader.readEnabledDataPacks(enabledDataPackIds) }

    internal suspend fun inspectEnabledFileDataPacks(
        enabledDataPackIds: List<DataPackId>,
    ): List<DataPackInspection> = withUncoordinatedOperation {
        worldDataPackReader.inspectEnabledFileDataPacks(enabledDataPackIds)
    }

    internal suspend fun readEnabledDataPacks(): WorldDataPackLoadResult =
        withUncoordinatedOperation {
            worldDataPackReader.readEnabledDataPacks(readLevelDataWithinOperation())
        }

    internal suspend fun inspectEnabledFileDataPacks(): List<DataPackInspection> =
        withUncoordinatedOperation {
            worldDataPackReader.inspectEnabledFileDataPacks(readLevelDataWithinOperation())
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

    suspend fun readLevelData(): LevelDat = readLevelData<LevelDat>()

    @JvmName("readTypedLevelData")
    suspend inline fun <reified T> readLevelData(): T =
        readLevelData(configuration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun <T> readLevelData(block: (BufferedSource) -> T): T = readRecovering(
        WorldResourceKey.LevelData,
        { levelDataStore.readForSharedAccess(block) },
        { levelDataStore.read(block) },
    )

    suspend fun writeLevelDataDocument(nbtDocument: NbtDocument) =
        write(WorldResourceKey.LevelData) { levelDataStore.writeDocument(nbtDocument) }

    suspend fun <T> writeLevelData(value: T, serializationStrategy: SerializationStrategy<T>) =
        write(WorldResourceKey.LevelData) { levelDataStore.write(value, serializationStrategy) }

    suspend fun writeLevelData(levelDat: LevelDat) = writeLevelData<LevelDat>(levelDat)

    suspend inline fun <reified T> writeLevelData(value: T) =
        writeLevelData(value, configuration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun writeLevelData(block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.LevelData) { levelDataStore.write(block) }

    internal suspend fun readSavedDataDocument(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
    ): NbtDocument? = withSavedData(savedDataId, savedDataScope, write = false) { savedDataStore ->
        savedDataStore.readDocument(savedDataId)
    }

    internal suspend fun <T> readSavedData(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withSavedData(savedDataId, savedDataScope, write = false) { savedDataStore ->
        savedDataStore.read(savedDataId, deserializationStrategy)
    }

    internal suspend fun <T> readSavedData(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
        block: (BufferedSource) -> T,
    ): T? = withSavedData(savedDataId, savedDataScope, write = false) { savedDataStore ->
        savedDataStore.read(savedDataId, block)
    }

    internal suspend fun writeSavedDataDocument(
        savedDataId: SavedDataId,
        nbtDocument: NbtDocument,
        savedDataScope: SavedDataScope,
    ) = withSavedData(savedDataId, savedDataScope, write = true) { savedDataStore ->
        savedDataStore.writeDocument(savedDataId, nbtDocument)
    }

    internal suspend fun <T> writeSavedData(
        savedDataId: SavedDataId,
        value: T,
        savedDataScope: SavedDataScope,
        serializationStrategy: SerializationStrategy<T>,
    ) = withSavedData(savedDataId, savedDataScope, write = true) { savedDataStore ->
        savedDataStore.write(savedDataId, value, serializationStrategy)
    }

    internal suspend fun writeSavedData(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
        block: (BufferedSink) -> Unit,
    ) = withSavedData(savedDataId, savedDataScope, write = true) { savedDataStore ->
        savedDataStore.write(savedDataId, block)
    }

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
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
        write: Boolean,
        block: (SavedDataStore) -> T,
    ): T {
        val path = minecraftWorldPaths.savedData(savedDataId, savedDataScope)
        val key = WorldResourceKey.SavedData(path)
        val savedDataStore = SavedDataStore(minecraftWorldPaths, savedDataScope, nbtFileStore)
        return if (write) write(key) { block(savedDataStore) } else read(key) { block(savedDataStore) }
    }

    private suspend fun <T> withUncoordinatedOperation(block: suspend () -> T): T =
        worldOperationLifecycle.withOperation { block() }

    private suspend fun readLevelDataWithinOperation(): LevelDat {
        return when (val coordinatedRead = logicalResourceCoordinator.read(WorldResourceKey.LevelData) {
            levelDataStore.readForSharedAccess<LevelDat>()
        }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalResourceCoordinator.write(WorldResourceKey.LevelData) {
                levelDataStore.read<LevelDat>()
            }
        }
    }

    internal suspend fun listRegionPositions(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionId: DimensionId,
    ): List<RegionPosition> = withUncoordinatedOperation {
        snapshotRegionPositions(
            nbtFileStore.fileSystem,
            minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionId),
        )
    }

    internal suspend fun openRegion(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
    ): RegionHandle {
        val worldOperationPin = worldOperationLifecycle.acquire()
        var transferred = false
        return withCleanup(cleanup = { if (transferred) null else worldOperationPin.release() }) {
            val regionHandle = coordinatedRegionRegistry.openRegion(
                regionStorageDirectory,
                dimensionId,
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
            val utf8JsonFileStore = Utf8JsonFileStore(rawFileStore, configuration.standaloneJson)
            val worldOperationLifecycle = WorldOperationLifecycle(minecraftWorldPaths, worldDirectoryLock)
            val logicalResourceCoordinator = LogicalResourceCoordinator<WorldResourceKey>()
            return MinecraftWorldAccess(
                minecraftWorldPaths = minecraftWorldPaths,
                configuration = configuration,
                players = MinecraftWorldPlayers(
                    configuration,
                    worldOperationLifecycle,
                    logicalResourceCoordinator,
                    PlayerDataStore(minecraftWorldPaths, nbtFileStore),
                    PlayerStatisticsStore(minecraftWorldPaths, utf8JsonFileStore),
                    PlayerAdvancementsStore(minecraftWorldPaths, utf8JsonFileStore),
                ),
                worldOperationLifecycle = worldOperationLifecycle,
                logicalResourceCoordinator = logicalResourceCoordinator,
                coordinatedRegionRegistry = CoordinatedRegionRegistry(
                    minecraftWorldPaths,
                    worldFileAccess,
                    configuration.chunkNbtFormat,
                    configuration.regionStorageConfiguration,
                ),
                levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore),
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

internal sealed interface WorldResourceKey {
    data object LevelData : WorldResourceKey

    data class PlayerData(val playerUuid: String) : WorldResourceKey

    data class SavedData(val path: Path) : WorldResourceKey

    data class Statistics(val playerUuid: String) : WorldResourceKey

    data class Advancements(val playerUuid: String) : WorldResourceKey
}
