package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okio.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Shared resource-owning implementation behind mutable world access.
 *
 * The state mutex protects only in-memory registries and admission state. Each logical file has
 * shared readers or one writer. Filesystem work runs in the callers' coroutine contexts.
 */
internal class OpenMinecraftWorld(
    val minecraftWorldPaths: MinecraftWorldPaths,
    private val worldFileAccess: WorldFileAccess,
    private val nbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    private val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    private val regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    private val worldDirectoryLock: WorldDirectoryLock? = null,
) {
    init {
        require(!worldFileAccess.liveReadOnly) { "OpenMinecraftWorld requires mutable file access" }
    }

    private val state = Mutex()
    private val nbtFileStore = NbtFileStore(worldFileAccess, nbtFormat)
    private val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
    private val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
    private val utf8JsonFileStore = Utf8JsonFileStore(worldFileAccess)
    private val regionStorages = mutableMapOf<RegionStorageKey, RegionStorageEntry>()
    private val metadataEntries = mutableMapOf<MetadataKey, MetadataEntry>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    // Mutable healthy reads share access. Official fallback promotion and corrupt-player copying
    // mutate the logical file group, so a recoverable primary failure is retried exclusively.
    suspend fun readLevelDataDocument(): NbtDocument = withMetadataEntry({ MetadataKey.LevelData }) { metadataEntry ->
        val logicalFileAccess = metadataEntry.logicalFileAccess
        when (val coordinatedRead = logicalFileAccess.read { levelDataStore.readDocumentForSharedAccess() }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalFileAccess.write { levelDataStore.readDocument() }
        }
    }

    suspend fun <T> readLevelData(deserializationStrategy: DeserializationStrategy<T>): T =
        withMetadataEntry({ MetadataKey.LevelData }) { metadataEntry ->
            val logicalFileAccess = metadataEntry.logicalFileAccess
            when (val coordinatedRead = logicalFileAccess.read { levelDataStore.readForSharedAccess(deserializationStrategy) }) {
                is CoordinatedRead.Complete -> coordinatedRead.value
                CoordinatedRead.RequiresExclusive -> logicalFileAccess.write { levelDataStore.read(deserializationStrategy) }
            }
        }

    suspend fun <T> readLevelData(block: (KotlinxSource) -> T): T =
        withMetadataEntry({ MetadataKey.LevelData }) { metadataEntry ->
            val logicalFileAccess = metadataEntry.logicalFileAccess
            when (val coordinatedRead = logicalFileAccess.read { levelDataStore.readForSharedAccess(block) }) {
                is CoordinatedRead.Complete -> coordinatedRead.value
                CoordinatedRead.RequiresExclusive -> logicalFileAccess.write { levelDataStore.read(block) }
            }
        }

    suspend fun writeLevelDataDocument(nbtDocument: NbtDocument) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelDataStore.writeDocument(nbtDocument)
        }

    suspend fun <T> writeLevelData(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
        levelDataStore.write(serializationStrategy, value)
    }

    suspend fun writeLevelData(block: (KotlinxSink) -> Unit) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelDataStore.write(block)
        }

    suspend fun readPlayerDataDocument(playerUuid: String): NbtDocument? =
        withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { metadataEntry ->
            val logicalFileAccess = metadataEntry.logicalFileAccess
            when (val coordinatedRead = logicalFileAccess.read { playerDataStore.readDocumentForSharedAccess(playerUuid) }) {
                is CoordinatedRead.Complete -> coordinatedRead.value
                CoordinatedRead.RequiresExclusive -> logicalFileAccess.write { playerDataStore.readDocument(playerUuid) }
            }
        }

    suspend fun <T> readPlayerData(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { metadataEntry ->
        val logicalFileAccess = metadataEntry.logicalFileAccess
        val block =
            { kotlinxSource: KotlinxSource -> nbtFormat.decodeFromSource(deserializationStrategy, kotlinxSource) }
        when (val coordinatedRead = logicalFileAccess.read { playerDataStore.readForSharedAccess(playerUuid, block) }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalFileAccess.write { playerDataStore.read(playerUuid, deserializationStrategy) }
        }
    }

    suspend fun <T> readPlayerData(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T? = withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { metadataEntry ->
        val logicalFileAccess = metadataEntry.logicalFileAccess
        when (val coordinatedRead = logicalFileAccess.read { playerDataStore.readForSharedAccess(playerUuid, block) }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalFileAccess.write { playerDataStore.read(playerUuid, block) }
        }
    }

    suspend fun writePlayerDataDocument(
        playerUuid: String,
        nbtDocument: NbtDocument,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerDataStore.writeDocument(playerUuid, nbtDocument)
    }

    suspend fun <T> writePlayerData(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerDataStore.write(playerUuid, serializationStrategy, value)
    }

    suspend fun writePlayerData(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerDataStore.write(playerUuid, block)
    }

    suspend fun readSavedDataDocument(
        identifier: String,
        dimensionDirectory: DimensionDirectory,
    ): NbtDocument? = withMetadata(
        metadataKeyProvider = { MetadataKey.SavedData(minecraftWorldPaths.savedData(identifier, dimensionDirectory)) },
        metadataAccess = MetadataAccess.READ,
    ) {
        SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).readDocument(identifier)
    }

    suspend fun <T> readSavedData(
        identifier: String,
        deserializationStrategy: DeserializationStrategy<T>,
        dimensionDirectory: DimensionDirectory,
    ): T? = withMetadata(
        metadataKeyProvider = { MetadataKey.SavedData(minecraftWorldPaths.savedData(identifier, dimensionDirectory)) },
        metadataAccess = MetadataAccess.READ,
    ) {
        SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).read(identifier, deserializationStrategy)
    }

    suspend fun <T> readSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory,
        block: (KotlinxSource) -> T,
    ): T? = withMetadata(
        metadataKeyProvider = { MetadataKey.SavedData(minecraftWorldPaths.savedData(identifier, dimensionDirectory)) },
        metadataAccess = MetadataAccess.READ,
    ) {
        SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).read(identifier, block)
    }

    suspend fun writeSavedDataDocument(
        identifier: String,
        nbtDocument: NbtDocument,
        dimensionDirectory: DimensionDirectory,
    ) = withMetadata(
        metadataKeyProvider = { MetadataKey.SavedData(minecraftWorldPaths.savedData(identifier, dimensionDirectory)) },
        metadataAccess = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).writeDocument(identifier, nbtDocument)
    }

    suspend fun <T> writeSavedData(
        identifier: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        dimensionDirectory: DimensionDirectory,
    ) = withMetadata(
        metadataKeyProvider = { MetadataKey.SavedData(minecraftWorldPaths.savedData(identifier, dimensionDirectory)) },
        metadataAccess = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).write(identifier, serializationStrategy, value)
    }

    suspend fun writeSavedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata(
        metadataKeyProvider = { MetadataKey.SavedData(minecraftWorldPaths.savedData(identifier, dimensionDirectory)) },
        metadataAccess = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(minecraftWorldPaths, dimensionDirectory, nbtFileStore).write(identifier, block)
    }

    suspend fun readStatisticsText(playerUuid: String): String =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
            utf8JsonFileStore.readText(minecraftWorldPaths.statistics(playerUuid))
        }

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
        utf8JsonFileStore.readJson(minecraftWorldPaths.statistics(playerUuid), deserializationStrategy, json)
    }

    suspend fun <T> readStatistics(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
        utf8JsonFileStore.read(minecraftWorldPaths.statistics(playerUuid), block)
    }

    suspend fun writeStatisticsText(playerUuid: String, text: String) =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
            utf8JsonFileStore.writeText(minecraftWorldPaths.statistics(playerUuid), text)
        }

    suspend fun <T> writeStatistics(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
        utf8JsonFileStore.writeJson(minecraftWorldPaths.statistics(playerUuid), serializationStrategy, value, json)
    }

    suspend fun writeStatistics(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
        utf8JsonFileStore.write(minecraftWorldPaths.statistics(playerUuid), block)
    }

    suspend fun readAdvancementsText(playerUuid: String): String =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
            utf8JsonFileStore.readText(minecraftWorldPaths.advancement(playerUuid))
        }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
        utf8JsonFileStore.readJson(minecraftWorldPaths.advancement(playerUuid), deserializationStrategy, json)
    }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
        utf8JsonFileStore.read(minecraftWorldPaths.advancement(playerUuid), block)
    }

    suspend fun writeAdvancementsText(playerUuid: String, text: String) =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
            utf8JsonFileStore.writeText(minecraftWorldPaths.advancement(playerUuid), text)
        }

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
        utf8JsonFileStore.writeJson(minecraftWorldPaths.advancement(playerUuid), serializationStrategy, value, json)
    }

    suspend fun writeAdvancements(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
        utf8JsonFileStore.write(minecraftWorldPaths.advancement(playerUuid), block)
    }

    suspend fun readAnvilRegion(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): PositionedAnvilRegion? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readAnvilRegion(regionPosition)
    }

    suspend fun <T> withRegionReadScope(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: RegionReadScope.() -> T,
    ): T = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.withReadScope(regionPosition, block)
    }

    suspend fun replaceRegion(
        regionPosition: RegionPosition,
        anvilRegion: AnvilRegion,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.replaceRegion(regionPosition, anvilRegion)
    }

    suspend fun replaceRegion(
        regionPosition: RegionPosition,
        chunks: Collection<RegionChunkInput>,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.replaceRegion(regionPosition, chunks)
    }

    suspend fun replaceRegion(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: RegionReplacementScope.() -> Unit,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.replaceRegion(regionPosition, block)
    }

    suspend fun clear(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.clear(regionPosition)
    }

    suspend fun hasRegion(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): Boolean = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.hasRegion(regionPosition)
    }

    suspend fun listRegionPositions(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): List<RegionPosition> = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.listRegionPositions()
    }

    suspend fun readChunkInfo(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): RegionChunkInfo? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkInfo(chunkPosition)
    }

    suspend fun readChunkInfo(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): RegionChunkInfo? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkInfo(regionPosition, localChunkPosition)
    }

    suspend fun readChunkInfos(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): List<RegionChunkInfo> = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkInfos(regionPosition)
    }

    suspend fun readCompressedChunk(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): CompressedChunk? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readCompressedChunk(chunkPosition)
    }

    suspend fun readCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): CompressedChunk? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readCompressedChunk(regionPosition, localChunkPosition)
    }

    suspend fun <T> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.withCompressedChunkSource(chunkPosition, block)
    }

    suspend fun <T> withCompressedChunkSource(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.withCompressedChunkSource(regionPosition, localChunkPosition, block)
    }

    suspend fun <T> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.withChunkNbtSource(chunkPosition, block)
    }

    suspend fun <T> withChunkNbtSource(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.withChunkNbtSource(regionPosition, localChunkPosition, block)
    }

    suspend fun hasChunk(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): Boolean = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.hasChunk(chunkPosition)
    }

    suspend fun hasChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): Boolean = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.hasChunk(regionPosition, localChunkPosition)
    }

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compressedChunkInput: CompressedChunkInput,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeCompressedChunk(chunkPosition, compressedChunkInput)
    }

    suspend fun writeCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compressedChunkInput: CompressedChunkInput,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeCompressedChunk(regionPosition, localChunkPosition, compressedChunkInput)
    }

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeCompressedChunk(chunkPosition, compression, compressedByteCount, block)
    }

    suspend fun writeCompressedChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeCompressedChunk(regionPosition, localChunkPosition, compression, compressedByteCount, block)
    }

    suspend fun removeChunk(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.removeChunk(chunkPosition)
    }

    suspend fun removeChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.removeChunk(regionPosition, localChunkPosition)
    }

    suspend fun readChunkNbtDocument(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): NbtDocument? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkNbtDocument(chunkPosition)
    }

    suspend fun readChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): NbtDocument? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkNbtDocument(regionPosition, localChunkPosition)
    }

    suspend fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): T? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkNbt(chunkPosition, deserializationStrategy)
    }

    suspend fun <T> readChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): T? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunkNbt(regionPosition, localChunkPosition, deserializationStrategy)
    }

    suspend fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): Chunk<B, M>? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunk(chunkPosition, chunkNbtCodec)
    }

    suspend fun <B : Any, M : Any> readChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): Chunk<B, M>? = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.readChunk(regionPosition, localChunkPosition, chunkNbtCodec)
    }

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunkNbtDocument(chunkPosition, nbtDocument)
    }

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunkNbtDocument(regionPosition, localChunkPosition, nbtDocument)
    }

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunkNbtDocument(chunkPosition, nbtDocument, compression)
    }

    suspend fun writeChunkNbtDocument(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunkNbtDocument(regionPosition, localChunkPosition, nbtDocument, compression)
    }

    suspend fun <T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunkNbt(chunkPosition, serializationStrategy, value, compression)
    }

    suspend fun <T> writeChunkNbt(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunkNbt(regionPosition, localChunkPosition, serializationStrategy, value, compression)
    }

    suspend fun <B : Any, M : Any> writeChunk(
        chunkPosition: ChunkPosition,
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunk(chunkPosition, chunk, chunkNbtCodec, compression)
    }

    suspend fun <B : Any, M : Any> writeChunk(
        regionPosition: RegionPosition,
        localChunkPosition: LocalChunkPosition,
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ) = withRegionStorage(regionStorageDirectory, dimensionDirectory) { regionStorage ->
        regionStorage.writeChunk(regionPosition, localChunkPosition, chunk, chunkNbtCodec, compression)
    }

    suspend fun openRegion(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): RegionHandle {
        val regionStorageEntry = acquireRegionStorage(regionStorageDirectory, dimensionDirectory)
        var transferred = false
        return withCleanup(
            cleanup = {
                if (transferred) null else releaseRegionStorage(regionStorageEntry)
            },
        ) {
            val regionHandle = regionStorageEntry.regionStorage.openRegion(regionPosition) {
                releaseRegionStorage(regionStorageEntry)
            }
            transferred = true
            regionHandle
        }
    }

    suspend fun flush() {
        val pinned = state.withLock {
            checkValid()
            regionStorages.values.filterNot { it.closing }.onEach { it.users++ }
        }
        if (pinned.isEmpty()) return

        var failure: Throwable? = null
        pinned.forEach { regionStorageEntry ->
            var entryFailure: Throwable? = null
            // Every entry was pinned as one snapshot. After cancellation, skip further flush work
            // but still release every pin through non-cancellable cleanup.
            if (failure !is CancellationException) {
                try {
                    regionStorageEntry.regionStorage.flush()
                } catch (caught: Throwable) {
                    entryFailure = caught
                }
            }
            entryFailure = collectCleanupFailure(entryFailure) { releaseRegionStorage(regionStorageEntry) }
            entryFailure?.let { caught ->
                failure = combineFailures(failure, caught)
            }
        }
        throwFailureOrCancellation(failure)
    }

    suspend fun close() {
        val completion: CompletableDeferred<Unit>
        val owner: Boolean
        state.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                owner = false
            } else {
                closed = true
                completion = CompletableDeferred()
                closeCompletion = completion
                owner = true
            }
        }
        if (owner) {
            firstClose(completion)
        } else {
            if (!completion.isCompleted) completion.await()
            closeFailure?.let { throw it }
        }
    }

    internal suspend fun activeMetadataEntryCount(): Int = state.withLock {
        metadataEntries.size
    }

    internal suspend fun activeMetadataUsers(): Int = state.withLock {
        metadataEntries.values.sumOf { it.users }
    }

    internal suspend fun activeRegionStorageCount(): Int = state.withLock {
        regionStorages.size
    }

    internal suspend fun activeRegionStorageUsers(): Int = state.withLock {
        regionStorages.values.sumOf { it.users }
    }

    private suspend fun firstClose(completion: CompletableDeferred<Unit>) {
        val failure: Throwable? = withContext(NonCancellable) {
            val storeEntries = state.withLock {
                regionStorages.values.toList()
            }
            val activeMetadataEntries = state.withLock {
                metadataEntries.values.toList()
            }
            storeEntries.map { it.closed }.awaitAll()
            activeMetadataEntries.map { it.closed }.awaitAll()

            var failure = state.withLock {
                closeBarrierFailures.reduceOrNull { current, caught ->
                    combineFailures(current, caught)
                }
            }
            try {
                worldDirectoryLock?.close()
            } catch (lockFailure: Throwable) {
                failure = combineFailures(failure, lockFailure)
            }
            state.withLock {
                regionStorages.clear()
                metadataEntries.clear()
                closeBarrierFailures.clear()
                closeFailure = failure
            }
            completion.complete(Unit)
            failure
        }
        // The cleanup itself must finish even if its owner is cancelled. Observe that cancellation
        // only after the shared close result has been finalized for every current and later caller.
        throwFailureOrCancellation(failure)
    }

    private suspend fun <T> withMetadata(
        metadataKeyProvider: () -> MetadataKey,
        metadataAccess: MetadataAccess,
        block: suspend () -> T,
    ): T = withMetadataEntry(metadataKeyProvider) { metadataEntry ->
        when (metadataAccess) {
            MetadataAccess.READ -> metadataEntry.logicalFileAccess.read(block)
            MetadataAccess.WRITE -> metadataEntry.logicalFileAccess.write(block)
        }
    }

    private suspend fun <T> withMetadataEntry(
        metadataKeyProvider: () -> MetadataKey,
        block: suspend (MetadataEntry) -> T,
    ): T {
        val metadataEntry = acquireMetadata(metadataKeyProvider)
        return withCleanup(
            cleanup = { releaseMetadata(metadataEntry) },
        ) {
            block(metadataEntry)
        }
    }

    private suspend fun acquireMetadata(metadataKeyProvider: () -> MetadataKey): MetadataEntry = state.withLock {
        checkValid()
        val metadataKey = metadataKeyProvider()
        metadataEntries.getOrPut(metadataKey) {
            MetadataEntry(metadataKey)
        }.also { metadataEntry ->
            metadataEntry.users++
        }
    }

    private suspend fun releaseMetadata(metadataEntry: MetadataEntry): Throwable? {
        state.withLock {
            check(metadataEntry.users > 0) { "Metadata entry is not in use: ${metadataEntry.metadataKey}" }
            metadataEntry.users--
            if (metadataEntry.users > 0) return@withLock
            if (metadataEntries[metadataEntry.metadataKey] === metadataEntry) {
                metadataEntries.remove(metadataEntry.metadataKey)
            }
            metadataEntry.closed.complete(Unit)
        }
        return null
    }

    private suspend fun acquireRegionStorage(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): RegionStorageEntry {
        while (true) {
            val closing = state.withLock {
                checkValid()
                val regionStorageKey = RegionStorageKey(regionStorageDirectory, dimensionDirectory)
                val regionStorageEntry = regionStorages[regionStorageKey]
                if (regionStorageEntry == null) {
                    val created = RegionStorageEntry(
                        regionStorageKey = regionStorageKey,
                        regionStorage = RegionStorage(
                            minecraftWorldPaths = minecraftWorldPaths,
                            regionStorageDirectory = regionStorageDirectory,
                            dimensionDirectory = dimensionDirectory,
                            worldFileAccess = worldFileAccess,
                            chunkNbtFormat = chunkNbtFormat,
                            regionStorageConfiguration = regionStorageConfiguration,
                        ),
                    )
                    regionStorages[regionStorageKey] = created
                    return created
                }
                if (!regionStorageEntry.closing) {
                    regionStorageEntry.users++
                    return regionStorageEntry
                }
                regionStorageEntry.closed
            }
            closing.await()
        }
    }

    private suspend fun <T> withRegionStorage(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        block: suspend (RegionStorage) -> T,
    ): T {
        val regionStorageEntry = acquireRegionStorage(regionStorageDirectory, dimensionDirectory)
        return withCleanup(
            cleanup = { releaseRegionStorage(regionStorageEntry) },
        ) {
            block(regionStorageEntry.regionStorage)
        }
    }

    private suspend fun releaseRegionStorage(regionStorageEntry: RegionStorageEntry): Throwable? {
        val shouldClose = state.withLock {
            check(regionStorageEntry.users > 0) { "Region storage entry is not in use: ${regionStorageEntry.regionStorageKey}" }
            check(!regionStorageEntry.closing) { "Region storage entry is already closing: ${regionStorageEntry.regionStorageKey}" }
            regionStorageEntry.users--
            if (regionStorageEntry.users > 0) return@withLock false
            regionStorageEntry.closing = true
            true
        }
        if (!shouldClose) return null
        var closeFailure: Throwable? = null
        try {
            regionStorageEntry.regionStorage.close()
        } catch (caught: Throwable) {
            closeFailure = caught
        }
        state.withLock {
            if (regionStorages[regionStorageEntry.regionStorageKey] === regionStorageEntry) {
                regionStorages.remove(regionStorageEntry.regionStorageKey)
            }
            regionStorageEntry.closed.complete(Unit)
            closeFailure?.let {
                if (closed) closeBarrierFailures += it
            }
        }
        return closeFailure
    }

    private fun checkValid() {
        check(!closed) { "World access is closed: ${minecraftWorldPaths.root}" }
        val worldDirectoryLock = worldDirectoryLock ?: return
        if (!worldDirectoryLock.isValid) {
            throw WorldLockException(
                "World directory lock is no longer valid: ${minecraftWorldPaths.root}",
            )
        }
    }

    private data class RegionStorageKey(
        val regionStorageDirectory: RegionStorageDirectory,
        val dimensionDirectory: DimensionDirectory,
    )

    private sealed interface MetadataKey {
        data object LevelData : MetadataKey

        data class PlayerData(
            val playerUuid: String,
        ) : MetadataKey

        data class SavedData(
            val path: Path,
        ) : MetadataKey

        data class Statistics(
            val playerUuid: String,
        ) : MetadataKey

        data class Advancements(
            val playerUuid: String,
        ) : MetadataKey
    }

    private enum class MetadataAccess {
        READ,
        WRITE,
    }

    private class MetadataEntry(val metadataKey: MetadataKey) {
        val logicalFileAccess = LogicalFileAccess()
        var users = 0
        val closed = CompletableDeferred<Unit>()
    }

    private class RegionStorageEntry(
        val regionStorageKey: RegionStorageKey,
        val regionStorage: RegionStorage,
    ) {
        var users = 1
        var closing = false
        val closed = CompletableDeferred<Unit>()
    }
}
