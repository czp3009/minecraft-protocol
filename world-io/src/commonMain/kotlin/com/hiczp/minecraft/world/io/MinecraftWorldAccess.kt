package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
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

    suspend fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionFile? = world.readRegion(position, storage, dimension)

    suspend fun <T> readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: RegionReadScope.() -> T,
    ): T? = world.readRegion(position, storage, dimension, block)

    suspend fun writeRegion(
        position: RegionPosition,
        region: RegionFile,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeRegion(position, region, storage, dimension)

    suspend fun writeRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: RegionWriteScope.() -> Unit,
    ) = world.writeRegion(position, storage, dimension, block)

    suspend fun clearRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.clearRegion(position, storage, dimension)

    suspend fun doesRegionExist(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = world.doesRegionExist(position, storage, dimension)

    suspend fun openRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): WorldRegion = world.openRegion(position, storage, dimension)

    suspend fun <T> withRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: suspend WorldRegion.() -> T,
    ): T = world.withRegion(position, storage, dimension, block)

    suspend fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = world.readChunk(position, storage, dimension)

    suspend fun readChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? = world.readChunk(regionPosition, position, storage, dimension)

    suspend fun <T> readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = world.readChunk(position, storage, dimension, block)

    suspend fun <T> readChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = world.readChunk(regionPosition, position, storage, dimension, block)

    suspend fun doesChunkExist(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = world.doesChunkExist(position, storage, dimension)

    suspend fun doesChunkExist(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Boolean = world.doesChunkExist(regionPosition, position, storage, dimension)

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunk(position, chunk, storage, dimension)

    suspend fun writeChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        chunk: RegionChunk,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunk(regionPosition, position, chunk, storage, dimension)

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSink) -> Unit,
    ) = world.writeChunk(position, compression, compressedLength, storage, dimension, block)

    suspend fun writeChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        block: (KotlinxSink) -> Unit,
    ) = world.writeChunk(regionPosition, position, compression, compressedLength, storage, dimension, block)

    suspend fun clearChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.clearChunk(position, storage, dimension)

    suspend fun clearChunk(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.clearChunk(regionPosition, position, storage, dimension)

    suspend fun readChunkNbtDocument(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readChunkNbtDocument(position, storage, dimension)

    suspend fun readChunkNbtDocument(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? = world.readChunkNbtDocument(regionPosition, position, storage, dimension)

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = world.readChunkNbt(position, deserializer, storage, dimension)

    suspend fun <T> readChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = world.readChunkNbt(regionPosition, position, deserializer, storage, dimension)

    suspend inline fun <reified T> readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readChunkNbt(
        position,
        configuration.regionChunkNbtFormat.nbt.serializersModule.serializer(),
        storage,
        dimension,
    )

    suspend inline fun <reified T> readChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): T? = readChunkNbt(
        regionPosition,
        position,
        configuration.regionChunkNbtFormat.nbt.serializersModule.serializer(),
        storage,
        dimension,
    )

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbtDocument(position, document, storage, dimension)

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbtDocument(regionPosition, position, document, storage, dimension)

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbtDocument(position, document, compression, storage, dimension)

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbtDocument(regionPosition, position, document, compression, storage, dimension)

    suspend fun <T> writeChunkNbt(
        position: ChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.regionStoreConfiguration.writeCompression,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbt(position, serializer, value, compression, storage, dimension)

    suspend fun <T> writeChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.regionStoreConfiguration.writeCompression,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = world.writeChunkNbt(regionPosition, position, serializer, value, compression, storage, dimension)

    suspend inline fun <reified T> writeChunkNbt(
        position: ChunkPosition,
        value: T,
        compression: Compression = configuration.regionStoreConfiguration.writeCompression,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeChunkNbt(
        position,
        configuration.regionChunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
        storage,
        dimension,
    )

    suspend inline fun <reified T> writeChunkNbt(
        regionPosition: RegionPosition,
        position: LocalChunkPosition,
        value: T,
        compression: Compression = configuration.regionStoreConfiguration.writeCompression,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) = writeChunkNbt(
        regionPosition,
        position,
        configuration.regionChunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
        storage,
        dimension,
    )

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
