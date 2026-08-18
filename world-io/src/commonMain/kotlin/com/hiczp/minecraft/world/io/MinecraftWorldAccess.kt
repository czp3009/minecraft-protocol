package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.Path
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Region-storage policy shared by every dimension opened under one world
 * lease. It affects chunks newly encoded through NBT convenience writes;
 * existing and caller-supplied compressed chunks remain unchanged.
 * Standalone world files retain their own official storage policies.
 */
data class MinecraftWorldAccessConfiguration(
    val regionStoreConfiguration: WorldRegionStoreConfiguration = WorldRegionStoreConfiguration(),
    val regionChunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
    val standaloneNbtFormat: NbtFormat = minecraftWorldNbtFormat(),
) {
    init {
        standaloneNbtFormat.requireStandaloneWorldRoot()
    }
}

/**
 * A system-filesystem world lease backed by the vanilla `session.lock`.
 *
 * Public operations may be called concurrently. Readers of one logical metadata file or `.mca`
 * file may run together; a writer has exclusive access to that file, and independent files may
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

    suspend fun readPlayerData(playerUuid: String): NbtDocument? =
        world.readPlayerData(playerUuid)

    suspend fun <T> readPlayerData(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T? = world.readPlayerData(playerUuid, block)

    suspend fun writePlayerData(
        playerUuid: String,
        document: NbtDocument,
    ) = world.writePlayerData(playerUuid, document)

    suspend fun writePlayerData(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = world.writePlayerData(playerUuid, block)

    suspend fun readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readSavedData(identifier, dimension)

    suspend fun <T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSource) -> T,
    ): T? = world.readSavedData(identifier, dimension, block)

    suspend fun writeSavedData(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeSavedData(identifier, document, dimension)

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
        block: BufferedSource.() -> T,
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
        block: BufferedSink.() -> Unit,
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
        block: BufferedSource.() -> T,
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
        block: BufferedSink.() -> Unit,
    ) = world.writeAdvancements(playerUuid, block)

    suspend fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionFile = world.readRegion(position, storage, dimension)

    suspend fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = world.readChunk(position, storage, dimension)

    suspend fun <T> readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T? = world.readChunk(position, storage, dimension, block)

    suspend fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = world.doesChunkExist(position, storage, dimension)

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunk(position, chunk, storage, dimension)

    suspend fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: BufferedSink.() -> Unit,
    ) = world.writeChunk(position, compression, compressedLength, storage, dimension, block)

    suspend fun clearChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.clearChunk(position, storage, dimension)

    suspend fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readChunkNbt(position, storage, dimension)

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbt(position, document, storage, dimension)

    suspend fun flush() = world.flush()

    suspend fun close() = world.close()

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
                regionChunkNbtFormat = configuration.regionChunkNbtFormat,
                regionStoreConfiguration = configuration.regionStoreConfiguration,
                directoryLock = lock,
            )
            return MinecraftWorldAccess(paths, configuration, world)
        }

        fun isLocked(root: Path): Boolean =
            isWorldDirectoryLocked(MinecraftWorldPaths(root).sessionLock)
    }
}
