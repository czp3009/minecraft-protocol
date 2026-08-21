package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.RegionPosition
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
    val paths: MinecraftWorldPaths,
    val configuration: MinecraftWorldAccessConfiguration,
    private val world: OpenMinecraftWorld,
) {
    suspend fun readLevelDataDocument(): NbtDocument = world.readLevelDataDocument()

    suspend fun <T> readLevelData(deserializer: DeserializationStrategy<T>): T =
        world.readLevelData(deserializer)

    suspend inline fun <reified T> readLevelData(): T =
        readLevelData(configuration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun <T> readLevelData(block: (KotlinxSource) -> T): T =
        world.readLevelData(block)

    suspend fun writeLevelDataDocument(document: NbtDocument) =
        world.writeLevelDataDocument(document)

    suspend fun <T> writeLevelData(
        serializer: SerializationStrategy<T>,
        value: T,
    ) = world.writeLevelData(serializer, value)

    suspend inline fun <reified T> writeLevelData(value: T) =
        writeLevelData(configuration.standaloneNbtFormat.serializersModule.serializer(), value)

    suspend fun writeLevelData(block: (KotlinxSink) -> Unit) =
        world.writeLevelData(block)

    suspend fun readPlayerDataDocument(playerUuid: String): NbtDocument? =
        world.readPlayerDataDocument(playerUuid)

    suspend fun <T> readPlayerData(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = world.readPlayerData(playerUuid, deserializer)

    suspend inline fun <reified T> readPlayerData(playerUuid: String): T? =
        readPlayerData(playerUuid, configuration.standaloneNbtFormat.serializersModule.serializer())

    suspend fun <T> readPlayerData(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T? = world.readPlayerData(playerUuid, block)

    suspend fun writePlayerDataDocument(
        playerUuid: String,
        document: NbtDocument,
    ) = world.writePlayerDataDocument(playerUuid, document)

    suspend fun <T> writePlayerData(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
    ) = world.writePlayerData(playerUuid, serializer, value)

    suspend inline fun <reified T> writePlayerData(
        playerUuid: String,
        value: T,
    ) = writePlayerData(
        playerUuid,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        value,
    )

    suspend fun writePlayerData(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = world.writePlayerData(playerUuid, block)

    suspend fun readSavedDataDocument(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readSavedDataDocument(identifier, dimension)

    suspend fun <T> readSavedData(
        identifier: String,
        deserializer: DeserializationStrategy<T>,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = world.readSavedData(identifier, deserializer, dimension)

    suspend inline fun <reified T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readSavedData(
        identifier,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        dimension,
    )

    suspend fun <T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSource) -> T,
    ): T? = world.readSavedData(identifier, dimension, block)

    suspend fun writeSavedDataDocument(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeSavedDataDocument(identifier, document, dimension)

    suspend fun <T> writeSavedData(
        identifier: String,
        serializer: SerializationStrategy<T>,
        value: T,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeSavedData(identifier, serializer, value, dimension)

    suspend inline fun <reified T> writeSavedData(
        identifier: String,
        value: T,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeSavedData(
        identifier,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
        value,
        dimension,
    )

    suspend fun writeSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSink) -> Unit,
    ) = world.writeSavedData(identifier, dimension, block)

    suspend fun readStatisticsText(playerUuid: String): String =
        world.readStatisticsText(playerUuid)

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = world.readStatistics(playerUuid, deserializer, json)

    suspend inline fun <reified T> readStatistics(
        playerUuid: String,
        json: Json = Json,
    ): T = readStatistics(playerUuid, json.serializersModule.serializer(), json)

    suspend fun <T> readStatistics(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = world.readStatistics(playerUuid, block)

    suspend fun writeStatisticsText(playerUuid: String, text: String) =
        world.writeStatisticsText(playerUuid, text)

    suspend fun <T> writeStatistics(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = world.writeStatistics(playerUuid, serializer, value, json)

    suspend inline fun <reified T> writeStatistics(
        playerUuid: String,
        value: T,
        json: Json = Json,
    ) = writeStatistics(playerUuid, json.serializersModule.serializer(), value, json)

    suspend fun writeStatistics(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = world.writeStatistics(playerUuid, block)

    suspend fun readAdvancementsText(playerUuid: String): String =
        world.readAdvancementsText(playerUuid)

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = world.readAdvancements(playerUuid, deserializer, json)

    suspend inline fun <reified T> readAdvancements(
        playerUuid: String,
        json: Json = Json,
    ): T = readAdvancements(playerUuid, json.serializersModule.serializer(), json)

    suspend fun <T> readAdvancements(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = world.readAdvancements(playerUuid, block)

    suspend fun writeAdvancementsText(playerUuid: String, text: String) =
        world.writeAdvancementsText(playerUuid, text)

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = world.writeAdvancements(playerUuid, serializer, value, json)

    suspend inline fun <reified T> writeAdvancements(
        playerUuid: String,
        value: T,
        json: Json = Json,
    ) = writeAdvancements(playerUuid, json.serializersModule.serializer(), value, json)

    suspend fun writeAdvancements(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = world.writeAdvancements(playerUuid, block)

    /**
     * Lists a detached snapshot of every canonical Chunk Region filename in one dimension.
     *
     * This performs one full filesystem directory listing and materializes every result. It is
     * O(n), may be slow, and may exhaust memory for an extremely large world. Concurrent file
     * changes are not observed transactionally.
     */
    suspend fun listRegionPositions(
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): List<RegionPosition> = world.listRegionPositions(
        storage = RegionStorageDirectory.CHUNKS,
        dimension = dimension,
    )

    suspend fun hasRegion(
        position: RegionPosition,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = world.hasRegion(position, RegionStorageDirectory.CHUNKS, dimension)

    /**
     * Creates a coordinated logical Region handle without touching the filesystem.
     *
     * A missing Region still has a handle. Reads return false, null, or an empty list, and the
     * first write creates the physical storage.
     */
    suspend fun openRegion(
        position: RegionPosition,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionHandle = world.openRegion(position, RegionStorageDirectory.CHUNKS, dimension)

    suspend fun flush() = world.flush()

    suspend fun close() = world.close()

    /** Runs [block] and then closes this world lease with cancellation-safe cleanup. */
    suspend fun <T> use(block: suspend (MinecraftWorldAccess) -> T): T =
        useSuspendingResource(this, MinecraftWorldAccess::close, block)

    companion object {
        fun open(root: Path): MinecraftWorldAccess =
            open(root, MinecraftWorldAccessConfiguration())

        fun open(
            root: Path,
            configuration: MinecraftWorldAccessConfiguration,
        ): MinecraftWorldAccess {
            systemFileSystem.createDirectories(root)
            val paths = MinecraftWorldPaths(root)
            val lock = acquireWorldDirectoryLock(paths.sessionLock)
            val world = OpenMinecraftWorld(
                paths = paths,
                files = WorldFileAccess.mutable(systemFileSystem),
                nbtFormat = configuration.standaloneNbtFormat,
                chunkNbtFormat = configuration.chunkNbtFormat,
                regionStorageConfiguration = configuration.regionStorageConfiguration,
                directoryLock = lock,
            )
            return MinecraftWorldAccess(paths, configuration, world)
        }

        fun isLocked(root: Path): Boolean =
            isWorldDirectoryLocked(MinecraftWorldPaths(root).sessionLock)
    }
}
