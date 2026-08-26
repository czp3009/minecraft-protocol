package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.LevelDat
import com.hiczp.minecraft.world.format.RegionPosition
import com.hiczp.minecraft.world.format.datapack.DataPackFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.Path
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

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
 * A system-filesystem world lease backed by the vanilla `session.lock`.
 *
 * Public operations may be called concurrently. Readers of one logical metadata file or Region
 * may run together; a writer has exclusive access to that logical resource, and independent resources may
 * progress concurrently. Admission is writer-preferring but not fair or FIFO among same-kind
 * waiters. This class does not create a thread pool or select a dispatcher: blocking filesystem I/O,
 * NBT work, and compression run in each calling coroutine's context, so callers must move work off
 * a main/UI thread when required. [close] seals admission, waits for every admitted operation and
 * resource cleanup, and releases `session.lock` last.
 */
class MinecraftWorldAccess private constructor(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val minecraftWorldAccessConfiguration: MinecraftWorldAccessConfiguration,
    private val openMinecraftWorld: OpenMinecraftWorld,
) {
    private val worldDataPackReader =
        WorldDataPackReader(minecraftWorldPaths, minecraftWorldAccessConfiguration.dataPackFormat)

    /** Reads the caller-selected on-disk packs without taking a logical-file or program-level lock. */
    fun readEnabledDataPacks(enabledDataPackReferences: List<String>): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(enabledDataPackReferences)

    /** Lists file paths and declared sizes without loading their contents. */
    fun inspectEnabledFileDataPacks(enabledDataPackReferences: List<String>): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(enabledDataPackReferences)

    /** Coordinates the changing `level.dat` read, then reads the selected immutable pack files without that lock. */
    suspend fun readEnabledDataPacks(): WorldDataPackLoadResult =
        worldDataPackReader.readEnabledDataPacks(readLevelData<LevelDat>())

    suspend fun inspectEnabledFileDataPacks(): List<DataPackInspection> =
        worldDataPackReader.inspectEnabledFileDataPacks(readLevelData<LevelDat>())

    suspend fun readLevelDataDocument(): NbtDocument = openMinecraftWorld.readLevelDataDocument()

    suspend fun <T> readLevelData(deserializationStrategy: DeserializationStrategy<T>): T =
        openMinecraftWorld.readLevelData(deserializationStrategy)

    suspend inline fun <reified T> readLevelData(): T =
        readLevelData(minecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun <T> readLevelData(block: (KotlinxSource) -> T): T =
        openMinecraftWorld.readLevelData(block)

    suspend fun writeLevelDataDocument(nbtDocument: NbtDocument) =
        openMinecraftWorld.writeLevelDataDocument(nbtDocument)

    suspend fun <T> writeLevelData(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = openMinecraftWorld.writeLevelData(serializationStrategy, value)

    suspend inline fun <reified T> writeLevelData(value: T) =
        writeLevelData(minecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer(), value)

    suspend fun writeLevelData(block: (KotlinxSink) -> Unit) =
        openMinecraftWorld.writeLevelData(block)

    suspend fun readPlayerDataDocument(playerUuid: String): NbtDocument? =
        openMinecraftWorld.readPlayerDataDocument(playerUuid)

    suspend fun <T> readPlayerData(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = openMinecraftWorld.readPlayerData(playerUuid, deserializationStrategy)

    suspend inline fun <reified T> readPlayerData(playerUuid: String): T? =
        readPlayerData(playerUuid, minecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun <T> readPlayerData(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T? = openMinecraftWorld.readPlayerData(playerUuid, block)

    suspend fun writePlayerDataDocument(
        playerUuid: String,
        nbtDocument: NbtDocument,
    ) = openMinecraftWorld.writePlayerDataDocument(playerUuid, nbtDocument)

    suspend fun <T> writePlayerData(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = openMinecraftWorld.writePlayerData(playerUuid, serializationStrategy, value)

    suspend inline fun <reified T> writePlayerData(
        playerUuid: String,
        value: T,
    ) = writePlayerData(
        playerUuid,
        minecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer(),
        value,
    )

    suspend fun writePlayerData(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = openMinecraftWorld.writePlayerData(playerUuid, block)

    suspend fun readSavedDataDocument(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = openMinecraftWorld.readSavedDataDocument(identifier, dimensionDirectory)

    suspend fun <T> readSavedData(
        identifier: String,
        deserializationStrategy: DeserializationStrategy<T>,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = openMinecraftWorld.readSavedData(identifier, deserializationStrategy, dimensionDirectory)

    suspend inline fun <reified T> readSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readSavedData(
        identifier,
        minecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer(),
        dimensionDirectory,
    )

    suspend fun <T> readSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSource) -> T,
    ): T? = openMinecraftWorld.readSavedData(identifier, dimensionDirectory, block)

    suspend fun writeSavedDataDocument(
        identifier: String,
        nbtDocument: NbtDocument,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ) = openMinecraftWorld.writeSavedDataDocument(identifier, nbtDocument, dimensionDirectory)

    suspend fun <T> writeSavedData(
        identifier: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ) = openMinecraftWorld.writeSavedData(identifier, serializationStrategy, value, dimensionDirectory)

    suspend inline fun <reified T> writeSavedData(
        identifier: String,
        value: T,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeSavedData(
        identifier,
        minecraftWorldAccessConfiguration.standaloneNbtFormat.serializersModule.serializer(),
        value,
        dimensionDirectory,
    )

    suspend fun writeSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSink) -> Unit,
    ) = openMinecraftWorld.writeSavedData(identifier, dimensionDirectory, block)

    suspend fun readStatisticsText(playerUuid: String): String =
        openMinecraftWorld.readStatisticsText(playerUuid)

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = openMinecraftWorld.readStatistics(playerUuid, deserializationStrategy, json)

    suspend inline fun <reified T> readStatistics(
        playerUuid: String,
        json: Json = Json,
    ): T = readStatistics(playerUuid, json.serializersModule.serializer(), json)

    suspend fun <T> readStatistics(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = openMinecraftWorld.readStatistics(playerUuid, block)

    suspend fun writeStatisticsText(playerUuid: String, text: String) =
        openMinecraftWorld.writeStatisticsText(playerUuid, text)

    suspend fun <T> writeStatistics(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = openMinecraftWorld.writeStatistics(playerUuid, serializationStrategy, value, json)

    suspend inline fun <reified T> writeStatistics(
        playerUuid: String,
        value: T,
        json: Json = Json,
    ) = writeStatistics(playerUuid, json.serializersModule.serializer(), value, json)

    suspend fun writeStatistics(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = openMinecraftWorld.writeStatistics(playerUuid, block)

    suspend fun readAdvancementsText(playerUuid: String): String =
        openMinecraftWorld.readAdvancementsText(playerUuid)

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = openMinecraftWorld.readAdvancements(playerUuid, deserializationStrategy, json)

    suspend inline fun <reified T> readAdvancements(
        playerUuid: String,
        json: Json = Json,
    ): T = readAdvancements(playerUuid, json.serializersModule.serializer(), json)

    suspend fun <T> readAdvancements(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = openMinecraftWorld.readAdvancements(playerUuid, block)

    suspend fun writeAdvancementsText(playerUuid: String, text: String) =
        openMinecraftWorld.writeAdvancementsText(playerUuid, text)

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = openMinecraftWorld.writeAdvancements(playerUuid, serializationStrategy, value, json)

    suspend inline fun <reified T> writeAdvancements(
        playerUuid: String,
        value: T,
        json: Json = Json,
    ) = writeAdvancements(playerUuid, json.serializersModule.serializer(), value, json)

    suspend fun writeAdvancements(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = openMinecraftWorld.writeAdvancements(playerUuid, block)

    /**
     * Lists a detached snapshot of every canonical Chunk Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is
     * O(n), may be slow, and may exhaust memory for an extremely large world. Concurrent file
     * changes are not observed transactionally.
     */
    suspend fun listRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = openMinecraftWorld.listRegionPositions(
        regionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionDirectory = dimensionDirectory,
    )

    suspend fun hasRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openMinecraftWorld.hasRegion(regionPosition, RegionStorageDirectory.CHUNKS, dimensionDirectory)

    /**
     * Creates a coordinated logical Region handle without touching the filesystem.
     *
     * A missing Region still has a handle. Reads return false, null, or an empty list, and the
     * first write creates the physical storage.
     */
    suspend fun openRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionHandle = openMinecraftWorld.openRegion(regionPosition, RegionStorageDirectory.CHUNKS, dimensionDirectory)

    /**
     * Lists a detached snapshot of every canonical Entity Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is O(n), may be slow, and
     * may exhaust memory for an extremely large world. Concurrent file changes are not observed transactionally.
     */
    suspend fun listEntityRegionPositions(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = openMinecraftWorld.listRegionPositions(RegionStorageDirectory.ENTITIES, dimensionDirectory)

    suspend fun hasEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = openMinecraftWorld.hasRegion(regionPosition, RegionStorageDirectory.ENTITIES, dimensionDirectory)

    /**
     * Creates a coordinated logical Entity Region handle without touching the filesystem.
     *
     * A missing Region still has a handle. Reads return false, null, or an empty list, and the first write creates the
     * logical Entity Region.
     */
    suspend fun openEntityRegion(
        regionPosition: RegionPosition,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): EntityRegionHandle = EntityRegionHandle(
        openMinecraftWorld.openRegion(regionPosition, RegionStorageDirectory.ENTITIES, dimensionDirectory),
    )

    suspend fun flush() = openMinecraftWorld.flush()

    suspend fun close() = openMinecraftWorld.close()

    /** Runs [block] and then closes this world lease with cancellation-safe cleanup. */
    suspend fun <T> use(block: suspend (MinecraftWorldAccess) -> T): T =
        useSuspendingResource(this, MinecraftWorldAccess::close, block)

    companion object {
        fun open(root: Path): MinecraftWorldAccess =
            open(root, MinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            minecraftWorldAccessConfiguration: MinecraftWorldAccessConfiguration,
        ): MinecraftWorldAccess {
            systemFileSystem.createDirectories(root)
            val minecraftWorldPaths = MinecraftWorldPaths(root)
            val worldDirectoryLock = acquireWorldDirectoryLock(minecraftWorldPaths.sessionLock)
            val openMinecraftWorld = OpenMinecraftWorld(
                minecraftWorldPaths = minecraftWorldPaths,
                worldFileAccess = WorldFileAccess.mutable(systemFileSystem),
                nbtFormat = minecraftWorldAccessConfiguration.standaloneNbtFormat,
                chunkNbtFormat = minecraftWorldAccessConfiguration.chunkNbtFormat,
                regionStorageConfiguration = minecraftWorldAccessConfiguration.regionStorageConfiguration,
                worldDirectoryLock = worldDirectoryLock,
            )
            return MinecraftWorldAccess(minecraftWorldPaths, minecraftWorldAccessConfiguration, openMinecraftWorld)
        }

        fun isLocked(root: Path): Boolean =
            isWorldDirectoryLocked(MinecraftWorldPaths(root).sessionLock)
    }
}
