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
    val paths: MinecraftWorldPaths,
    private val files: WorldFileAccess,
    private val nbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    private val chunkNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    private val regionStorageConfiguration: RegionStorageConfiguration = RegionStorageConfiguration(),
    private val directoryLock: WorldDirectoryLock? = null,
) {
    init {
        require(!files.liveReadOnly) { "OpenMinecraftWorld requires mutable file access" }
    }

    private val state = Mutex()
    private val nbtFiles = NbtFileStore(files, nbtFormat)
    private val levelData = LevelDataStore(paths, nbtFiles)
    private val playerData = PlayerDataStore(paths, nbtFiles)
    private val jsonFiles = Utf8JsonFileStore(files)
    private val regionStorages = mutableMapOf<RegionStorageKey, RegionStorageEntry>()
    private val metadataEntries = mutableMapOf<MetadataKey, MetadataEntry>()
    private var closed = false
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    // Mutable healthy reads share access. Official fallback promotion and corrupt-player copying
    // mutate the logical file group, so a recoverable primary failure is retried exclusively.
    suspend fun readLevelDataDocument(): NbtDocument = withMetadataEntry({ MetadataKey.LevelData }) { entry ->
        val fileAccess = entry.fileAccess
        when (val read = fileAccess.read { levelData.readDocumentForSharedAccess() }) {
            is CoordinatedRead.Complete -> read.value
            CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.readDocument() }
        }
    }

    suspend fun <T> readLevelData(deserializer: DeserializationStrategy<T>): T =
        withMetadataEntry({ MetadataKey.LevelData }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { levelData.readForSharedAccess(deserializer) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.read(deserializer) }
            }
        }

    suspend fun <T> readLevelData(block: (KotlinxSource) -> T): T =
        withMetadataEntry({ MetadataKey.LevelData }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { levelData.readForSharedAccess(block) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { levelData.read(block) }
            }
        }

    suspend fun writeLevelDataDocument(document: NbtDocument) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelData.writeDocument(document)
        }

    suspend fun <T> writeLevelData(
        serializer: SerializationStrategy<T>,
        value: T,
    ) = withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
        levelData.write(serializer, value)
    }

    suspend fun writeLevelData(block: (KotlinxSink) -> Unit) =
        withMetadata({ MetadataKey.LevelData }, MetadataAccess.WRITE) {
            levelData.write(block)
        }

    suspend fun readPlayerDataDocument(playerUuid: String): NbtDocument? =
        withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { entry ->
            val fileAccess = entry.fileAccess
            when (val read = fileAccess.read { playerData.readDocumentForSharedAccess(playerUuid) }) {
                is CoordinatedRead.Complete -> read.value
                CoordinatedRead.RequiresExclusive -> fileAccess.write { playerData.readDocument(playerUuid) }
            }
        }

    suspend fun <T> readPlayerData(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { entry ->
        val fileAccess = entry.fileAccess
        val block = { source: KotlinxSource -> nbtFormat.decodeFromSource(deserializer, source) }
        when (val read = fileAccess.read { playerData.readForSharedAccess(playerUuid, block) }) {
            is CoordinatedRead.Complete -> read.value
            CoordinatedRead.RequiresExclusive -> fileAccess.write { playerData.read(playerUuid, deserializer) }
        }
    }

    suspend fun <T> readPlayerData(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T? = withMetadataEntry({ MetadataKey.PlayerData(playerUuid) }) { entry ->
        val fileAccess = entry.fileAccess
        when (val read = fileAccess.read { playerData.readForSharedAccess(playerUuid, block) }) {
            is CoordinatedRead.Complete -> read.value
            CoordinatedRead.RequiresExclusive -> fileAccess.write { playerData.read(playerUuid, block) }
        }
    }

    suspend fun writePlayerDataDocument(
        playerUuid: String,
        document: NbtDocument,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerData.writeDocument(playerUuid, document)
    }

    suspend fun <T> writePlayerData(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerData.write(playerUuid, serializer, value)
    }

    suspend fun writePlayerData(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.PlayerData(playerUuid) }, MetadataAccess.WRITE) {
        playerData.write(playerUuid, block)
    }

    suspend fun readSavedDataDocument(
        identifier: String,
        dimension: DimensionDirectory,
    ): NbtDocument? = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.READ,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).readDocument(identifier)
    }

    suspend fun <T> readSavedData(
        identifier: String,
        deserializer: DeserializationStrategy<T>,
        dimension: DimensionDirectory,
    ): T? = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.READ,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).read(identifier, deserializer)
    }

    suspend fun <T> readSavedData(
        identifier: String,
        dimension: DimensionDirectory,
        block: (KotlinxSource) -> T,
    ): T? = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.READ,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).read(identifier, block)
    }

    suspend fun writeSavedDataDocument(
        identifier: String,
        document: NbtDocument,
        dimension: DimensionDirectory,
    ) = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).writeDocument(identifier, document)
    }

    suspend fun <T> writeSavedData(
        identifier: String,
        serializer: SerializationStrategy<T>,
        value: T,
        dimension: DimensionDirectory,
    ) = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).write(identifier, serializer, value)
    }

    suspend fun writeSavedData(
        identifier: String,
        dimension: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata(
        key = { MetadataKey.SavedData(paths.savedData(identifier, dimension)) },
        access = MetadataAccess.WRITE,
    ) {
        SavedDataFileStore(paths, dimension, nbtFiles).write(identifier, block)
    }

    suspend fun readStatisticsText(playerUuid: String): String =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
            jsonFiles.readText(paths.statistics(playerUuid))
        }

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.readJson(paths.statistics(playerUuid), deserializer, json)
    }

    suspend fun <T> readStatistics(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.read(paths.statistics(playerUuid), block)
    }

    suspend fun writeStatisticsText(playerUuid: String, text: String) =
        withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
            jsonFiles.writeText(paths.statistics(playerUuid), text)
        }

    suspend fun <T> writeStatistics(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.writeJson(paths.statistics(playerUuid), serializer, value, json)
    }

    suspend fun writeStatistics(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.Statistics(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.write(paths.statistics(playerUuid), block)
    }

    suspend fun readAdvancementsText(playerUuid: String): String =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
            jsonFiles.readText(paths.advancement(playerUuid))
        }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.readJson(paths.advancement(playerUuid), deserializer, json)
    }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): T = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.READ) {
        jsonFiles.read(paths.advancement(playerUuid), block)
    }

    suspend fun writeAdvancementsText(playerUuid: String, text: String) =
        withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
            jsonFiles.writeText(paths.advancement(playerUuid), text)
        }

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.writeJson(paths.advancement(playerUuid), serializer, value, json)
    }

    suspend fun writeAdvancements(
        playerUuid: String,
        block: (KotlinxSink) -> Unit,
    ) = withMetadata({ MetadataKey.Advancements(playerUuid) }, MetadataAccess.WRITE) {
        jsonFiles.write(paths.advancement(playerUuid), block)
    }

    suspend fun readAnvilRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): PositionedAnvilRegion? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readAnvilRegion(position)
    }

    suspend fun <T> withRegionReadScope(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: RegionReadScope.() -> T,
    ): T = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.withReadScope(position, block)
    }

    suspend fun replaceRegion(
        position: RegionPosition,
        region: AnvilRegion,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.replaceRegion(position, region)
    }

    suspend fun replaceRegion(
        position: RegionPosition,
        chunks: Collection<RegionChunkInput>,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.replaceRegion(position, chunks)
    }

    suspend fun replaceRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: RegionReplacementScope.() -> Unit,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.replaceRegion(position, block)
    }

    suspend fun clear(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.clear(position)
    }

    suspend fun hasRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Boolean = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.hasRegion(position)
    }

    suspend fun listRegionPositions(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): List<RegionPosition> = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.listRegionPositions()
    }

    suspend fun readChunkInfo(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionChunkInfo? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkInfo(position)
    }

    suspend fun readChunkInfo(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionChunkInfo? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkInfo(region, local)
    }

    suspend fun readChunkInfos(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): List<RegionChunkInfo> = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkInfos(position)
    }

    suspend fun readCompressedChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): CompressedChunk? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readCompressedChunk(position)
    }

    suspend fun readCompressedChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): CompressedChunk? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readCompressedChunk(region, local)
    }

    suspend fun <T> withCompressedChunkSource(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.withCompressedChunkSource(position, block)
    }

    suspend fun <T> withCompressedChunkSource(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.withCompressedChunkSource(region, local, block)
    }

    suspend fun <T> withChunkNbtSource(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.withChunkNbtSource(position, block)
    }

    suspend fun <T> withChunkNbtSource(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (RegionChunkInfo, KotlinxSource) -> T,
    ): T? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.withChunkNbtSource(region, local, block)
    }

    suspend fun hasChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Boolean = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.hasChunk(position)
    }

    suspend fun hasChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Boolean = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.hasChunk(region, local)
    }

    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        chunk: CompressedChunkInput,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeCompressedChunk(position, chunk)
    }

    suspend fun writeCompressedChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        chunk: CompressedChunkInput,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeCompressedChunk(region, local, chunk)
    }

    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeCompressedChunk(position, compression, compressedByteCount, block)
    }

    suspend fun writeCompressedChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: (KotlinxSink) -> Unit,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeCompressedChunk(region, local, compression, compressedByteCount, block)
    }

    suspend fun removeChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.removeChunk(position)
    }

    suspend fun removeChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.removeChunk(region, local)
    }

    suspend fun readChunkNbtDocument(
        position: ChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): NbtDocument? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkNbtDocument(position)
    }

    suspend fun readChunkNbtDocument(
        region: RegionPosition,
        local: LocalChunkPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): NbtDocument? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkNbtDocument(region, local)
    }

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): T? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkNbt(position, deserializer)
    }

    suspend fun <T> readChunkNbt(
        region: RegionPosition,
        local: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): T? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunkNbt(region, local, deserializer)
    }

    suspend fun <B : Any, M : Any> readChunk(
        position: ChunkPosition,
        codec: ChunkNbtCodec<B, M>,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Chunk<B, M>? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunk(position, codec)
    }

    suspend fun <B : Any, M : Any> readChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        codec: ChunkNbtCodec<B, M>,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Chunk<B, M>? = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.readChunk(region, local, codec)
    }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunkNbtDocument(position, document)
    }

    suspend fun writeChunkNbtDocument(
        region: RegionPosition,
        local: LocalChunkPosition,
        document: NbtDocument,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunkNbtDocument(region, local, document)
    }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunkNbtDocument(position, document, compression)
    }

    suspend fun writeChunkNbtDocument(
        region: RegionPosition,
        local: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunkNbtDocument(region, local, document, compression)
    }

    suspend fun <T> writeChunkNbt(
        position: ChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunkNbt(position, serializer, value, compression)
    }

    suspend fun <T> writeChunkNbt(
        region: RegionPosition,
        local: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunkNbt(region, local, serializer, value, compression)
    }

    suspend fun <B : Any, M : Any> writeChunk(
        position: ChunkPosition,
        chunk: Chunk<B, M>,
        codec: ChunkNbtCodec<B, M>,
        compression: Compression,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunk(position, chunk, codec, compression)
    }

    suspend fun <B : Any, M : Any> writeChunk(
        region: RegionPosition,
        local: LocalChunkPosition,
        chunk: Chunk<B, M>,
        codec: ChunkNbtCodec<B, M>,
        compression: Compression,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ) = withRegionStorage(storage, dimension) { regionStorage ->
        regionStorage.writeChunk(region, local, chunk, codec, compression)
    }

    suspend fun openRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionHandle {
        val entry = acquireRegionStorage(storage, dimension)
        var transferred = false
        return withCleanup(
            cleanup = {
                if (transferred) null else releaseRegionStorage(entry)
            },
        ) {
            val region = entry.storage.openRegion(position) {
                releaseRegionStorage(entry)
            }
            transferred = true
            region
        }
    }

    suspend fun flush() {
        val pinned = state.withLock {
            checkValid()
            regionStorages.values.filterNot { it.closing }.onEach { it.users++ }
        }
        if (pinned.isEmpty()) return

        var failure: Throwable? = null
        pinned.forEach { entry ->
            var entryFailure: Throwable? = null
            // Every entry was pinned as one snapshot. After cancellation, skip further flush work
            // but still release every pin through non-cancellable cleanup.
            if (failure !is CancellationException) {
                try {
                    entry.storage.flush()
                } catch (caught: Throwable) {
                    entryFailure = caught
                }
            }
            entryFailure = collectCleanupFailure(entryFailure) { releaseRegionStorage(entry) }
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
                directoryLock?.close()
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
        key: () -> MetadataKey,
        access: MetadataAccess,
        block: suspend () -> T,
    ): T = withMetadataEntry(key) { entry ->
        when (access) {
            MetadataAccess.READ -> entry.fileAccess.read(block)
            MetadataAccess.WRITE -> entry.fileAccess.write(block)
        }
    }

    private suspend fun <T> withMetadataEntry(
        key: () -> MetadataKey,
        block: suspend (MetadataEntry) -> T,
    ): T {
        val entry = acquireMetadata(key)
        return withCleanup(
            cleanup = { releaseMetadata(entry) },
        ) {
            block(entry)
        }
    }

    private suspend fun acquireMetadata(key: () -> MetadataKey): MetadataEntry = state.withLock {
        checkValid()
        val metadataKey = key()
        metadataEntries.getOrPut(metadataKey) {
            MetadataEntry(metadataKey)
        }.also { entry ->
            entry.users++
        }
    }

    private suspend fun releaseMetadata(entry: MetadataEntry): Throwable? {
        state.withLock {
            check(entry.users > 0) { "Metadata entry is not in use: ${entry.key}" }
            entry.users--
            if (entry.users > 0) return@withLock
            if (metadataEntries[entry.key] === entry) {
                metadataEntries.remove(entry.key)
            }
            entry.closed.complete(Unit)
        }
        return null
    }

    private suspend fun acquireRegionStorage(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): RegionStorageEntry {
        while (true) {
            val closing = state.withLock {
                checkValid()
                val key = RegionStorageKey(storage, dimension)
                val entry = regionStorages[key]
                if (entry == null) {
                    val created = RegionStorageEntry(
                        key = key,
                        storage = RegionStorage(
                            paths = paths,
                            storage = storage,
                            dimension = dimension,
                            files = files,
                            chunkNbtFormat = chunkNbtFormat,
                            configuration = regionStorageConfiguration,
                        ),
                    )
                    regionStorages[key] = created
                    return created
                }
                if (!entry.closing) {
                    entry.users++
                    return entry
                }
                entry.closed
            }
            closing.await()
        }
    }

    private suspend fun <T> withRegionStorage(
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        block: suspend (RegionStorage) -> T,
    ): T {
        val entry = acquireRegionStorage(storage, dimension)
        return withCleanup(
            cleanup = { releaseRegionStorage(entry) },
        ) {
            block(entry.storage)
        }
    }

    private suspend fun releaseRegionStorage(entry: RegionStorageEntry): Throwable? {
        val shouldClose = state.withLock {
            check(entry.users > 0) { "Region storage entry is not in use: ${entry.key}" }
            check(!entry.closing) { "Region storage entry is already closing: ${entry.key}" }
            entry.users--
            if (entry.users > 0) return@withLock false
            entry.closing = true
            true
        }
        if (!shouldClose) return null
        var closeFailure: Throwable? = null
        try {
            entry.storage.close()
        } catch (caught: Throwable) {
            closeFailure = caught
        }
        state.withLock {
            if (regionStorages[entry.key] === entry) {
                regionStorages.remove(entry.key)
            }
            entry.closed.complete(Unit)
            closeFailure?.let {
                if (closed) closeBarrierFailures += it
            }
        }
        return closeFailure
    }

    private fun checkValid() {
        check(!closed) { "World access is closed: ${paths.root}" }
        val lock = directoryLock ?: return
        if (!lock.isValid) {
            throw WorldLockException(
                "World directory lock is no longer valid: ${paths.root}",
            )
        }
    }

    private data class RegionStorageKey(
        val storage: RegionStorageDirectory,
        val dimension: DimensionDirectory,
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

    private class MetadataEntry(val key: MetadataKey) {
        val fileAccess = LogicalFileAccess()
        var users = 0
        val closed = CompletableDeferred<Unit>()
    }

    private class RegionStorageEntry(
        val key: RegionStorageKey,
        val storage: RegionStorage,
    ) {
        var users = 1
        var closing = false
        val closed = CompletableDeferred<Unit>()
    }
}
