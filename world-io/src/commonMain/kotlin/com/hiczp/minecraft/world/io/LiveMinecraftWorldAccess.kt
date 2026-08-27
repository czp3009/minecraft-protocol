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
 * failure. The world access never repairs or mutates files and has no close lifecycle. Region
 * handles returned from [openRegion] and [openEntityRegion] independently own resources and must
 * be closed by their callers.
 *
 * Public operations may be called concurrently. This class does not create a thread pool or select
 * a dispatcher; blocking filesystem I/O, NBT work, and compression run synchronously on the
 * calling thread and are not automatically main-safe.
 */
class LiveMinecraftWorldAccess private constructor(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val liveMinecraftWorldAccessConfiguration: LiveMinecraftWorldAccessConfiguration,
    private val worldFileAccess: WorldFileAccess,
) {
    private val nbtFileStore = NbtFileStore(worldFileAccess, liveMinecraftWorldAccessConfiguration.standaloneNbtFormat)
    private val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
    private val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
    private val utf8JsonFileStore = Utf8JsonFileStore(worldFileAccess)
    private val worldDataPackReader = WorldDataPackReader(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.dataPacksDirectory,
        liveMinecraftWorldAccessConfiguration.dataPackFormat,
    )

    fun readEnabledDataPacks(enabledDataPackReferences: List<String>): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(enabledDataPackReferences)

    fun inspectEnabledFileDataPacks(enabledDataPackReferences: List<String>): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(enabledDataPackReferences)

    fun readEnabledDataPacks(): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(readLevelData<LevelDat>())

    fun inspectEnabledFileDataPacks(): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(readLevelData<LevelDat>())

    fun readLevelDataDocument(): NbtDocument = levelDataStore.readDocument()

    fun <T> readLevelData(deserializationStrategy: DeserializationStrategy<T>): T =
        levelDataStore.read(deserializationStrategy)

    inline fun <reified T> readLevelData(): T =
        readLevelData(liveMinecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer())

    fun <T> readLevelData(block: (KotlinxSource) -> T): T = levelDataStore.read(block)

    fun readPlayerDataDocument(playerUuid: String): NbtDocument? =
        playerDataStore.readDocument(playerUuid)

    fun <T> readPlayerData(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = playerDataStore.read(playerUuid, deserializationStrategy)

    inline fun <reified T> readPlayerData(playerUuid: String): T? =
        readPlayerData(playerUuid, liveMinecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer())

    fun <T> readPlayerData(playerUuid: String, block: (KotlinxSource) -> T): T? =
        playerDataStore.read(playerUuid, block)

    fun readSavedDataDocument(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).readDocument(identifier)

    fun <T> readSavedData(
        identifier: String,
        deserializationStrategy: DeserializationStrategy<T>,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).read(identifier, deserializationStrategy)

    inline fun <reified T> readSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readSavedData(
        identifier,
        liveMinecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer(),
        dimensionDirectory,
    )

    fun <T> readSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSource) -> T,
    ): T? = SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).read(identifier, block)

    fun readStatisticsText(playerUuid: String): String = utf8JsonFileStore.readText(minecraftWorldPaths.statistics(playerUuid))

    fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = utf8JsonFileStore.readJson(minecraftWorldPaths.statistics(playerUuid), deserializationStrategy, json)

    inline fun <reified T> readStatistics(
        playerUuid: String,
        json: Json = Json,
    ): T = readStatistics(playerUuid, json.serializersModule.serializer(), json)

    fun <T> readStatistics(playerUuid: String, block: (KotlinxSource) -> T): T =
        utf8JsonFileStore.read(minecraftWorldPaths.statistics(playerUuid), block)

    fun readAdvancementsText(playerUuid: String): String = utf8JsonFileStore.readText(minecraftWorldPaths.advancement(playerUuid))

    fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = utf8JsonFileStore.readJson(minecraftWorldPaths.advancement(playerUuid), deserializationStrategy, json)

    inline fun <reified T> readAdvancements(
        playerUuid: String,
        json: Json = Json,
    ): T = readAdvancements(playerUuid, json.serializersModule.serializer(), json)

    fun <T> readAdvancements(playerUuid: String, block: (KotlinxSource) -> T): T =
        utf8JsonFileStore.read(minecraftWorldPaths.advancement(playerUuid), block)

    /**
     * Lists a detached snapshot of every canonical Chunk Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is
     * O(n), may be slow, and may exhaust memory for an extremely large world. Concurrent file
     * changes are not observed transactionally.
     */
    fun listRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = snapshotRegionPositions(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.regionDirectory(RegionStorageDirectory.CHUNKS, dimensionDirectory),
    )

    fun hasRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openRegion(regionPosition, dimensionDirectory).use(LiveRegionHandle::hasRegion)

    /**
     * Opens a caller-owned live Region resource without taking a lock or joining a shared cache.
     *
     * If the Region is missing at this point, the returned resource owns no file and its reads
     * return false, null, or an empty list. Close the handle after its final read.
     */
    fun openRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveRegionHandle = LiveRegionHandle(
        fileSystem = worldFileAccess.fileSystem,
        directory = minecraftWorldPaths.regionDirectory(RegionStorageDirectory.CHUNKS, dimensionDirectory),
        regionPosition = regionPosition,
        chunkNbtFormat = liveMinecraftWorldAccessConfiguration.chunkNbtFormat,
    )

    /**
     * Lists a detached snapshot of every canonical Entity Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is O(n), may be slow, and
     * may exhaust memory for an extremely large world. Concurrent file changes are not observed transactionally.
     */
    fun listEntityRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = snapshotRegionPositions(
        worldFileAccess.fileSystem,
        minecraftWorldPaths.regionDirectory(RegionStorageDirectory.ENTITIES, dimensionDirectory),
    )

    fun hasEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openEntityRegion(regionPosition, dimensionDirectory).use(LiveEntityRegionHandle::hasRegion)

    /** Opens a caller-owned live Entity Region resource with the same lifecycle as [openRegion]. */
    fun openEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): LiveEntityRegionHandle = LiveEntityRegionHandle(
        LiveRegionHandle(
            fileSystem = worldFileAccess.fileSystem,
            directory = minecraftWorldPaths.regionDirectory(RegionStorageDirectory.ENTITIES, dimensionDirectory),
            regionPosition = regionPosition,
            chunkNbtFormat = liveMinecraftWorldAccessConfiguration.chunkNbtFormat,
        ),
    )

    companion object {
        fun open(root: Path): LiveMinecraftWorldAccess =
            open(root, LiveMinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            liveMinecraftWorldAccessConfiguration: LiveMinecraftWorldAccessConfiguration,
        ): LiveMinecraftWorldAccess = open(root, systemFileSystem, liveMinecraftWorldAccessConfiguration)

        internal fun open(
            root: Path,
            fileSystem: FileSystem,
            liveMinecraftWorldAccessConfiguration: LiveMinecraftWorldAccessConfiguration = LiveMinecraftWorldAccessConfiguration(),
        ): LiveMinecraftWorldAccess {
            val fileMetadata = fileSystem.metadataOrNull(root)
                ?: throw WorldIOException("World directory does not exist: $root")
            if (!fileMetadata.isDirectory) {
                throw WorldIOException("World path is not a directory: $root")
            }
            return LiveMinecraftWorldAccess(
                minecraftWorldPaths = MinecraftWorldPaths(root),
                liveMinecraftWorldAccessConfiguration = liveMinecraftWorldAccessConfiguration,
                worldFileAccess = WorldFileAccess.liveReadOnly(fileSystem),
            )
        }
    }
}
