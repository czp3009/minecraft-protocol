package com.hiczp.minecraft.world.io.fixturetest.hostfilesystem

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.test.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.io.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Exercises the exact official release and this library against one Host-owned
 * world directory. Each platform writes every supported official chunk
 * compression into one mixed region before the server loads, saves, and
 * reloads it. Same-host JVM, Node, and desktop Native test compilations inherit
 * this entry directly from `hostFilesystemTest`.
 */
class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() = runTest(
        timeout = 5.minutes,
    ) {
        MinecraftTestSupport.newOfficialServer(
            OfficialMinecraftServerConfiguration(
                properties = mapOf(
                    "level-name" to WORLD_NAME,
                    "sync-chunk-writes" to "true",
                ),
            ),
        ).use { initialServer ->
            var server = initialServer
            val workingDirectory = MinecraftTestSupport
                .hostWorkingDirectory(server)
                .toPath()
            val worldDirectory = workingDirectory / WORLD_NAME

            prepareOfficialWorld(server, worldDirectory)
            val initial = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(initial)
            exerciseStandalonePolicies(worldDirectory, initial)
            exerciseCompressionMatrix(worldDirectory)
            val terrainMutation = exerciseTerrainMutation(worldDirectory)

            server = MinecraftTestSupport.restartServer(server)
            mutateAndStopOfficialServer(server, "platform_write")
            val afterExternal = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(afterExternal)

            restoreInternalAndClearEntity(
                worldDirectory = worldDirectory,
                terrainMutation = terrainMutation,
                entityPosition = checkNotNull(
                    afterExternal.firstChunks[RegionStorageDirectory.ENTITIES],
                ),
            )

            server = MinecraftTestSupport.restartServer(server)
            mutateAndStopOfficialServer(server, "official_resave")
            val final = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(final)

            MinecraftTestSupport.deleteWorkingDirectory(server)
            check(!systemFileSystem.exists(workingDirectory)) {
                "Fixture Host working directory remained after deletion"
            }
        }
    }

    private suspend fun prepareOfficialWorld(
        server: OfficialMinecraftServer,
        worldDirectory: Path,
    ) {
        var client: HeadlessMinecraftClient? = null
        var failure: Throwable? = null
        try {
            assertOfficialLockIsHeld(worldDirectory)
            generateWorldStorage(server)
            client = MinecraftTestSupport.newHeadlessClient(
                HeadlessMinecraftClientConfiguration(
                    playerName = PLAYER_NAME,
                ),
            )
            MinecraftTestSupport.connectHeadlessClient(
                client = client,
                endpoint = server.endpoint,
            )
            MinecraftTestSupport.waitForLog(
                server,
                "$PLAYER_NAME joined the game",
            )
            MinecraftTestSupport.sendCommand(
                server,
                "advancement grant $PLAYER_NAME only minecraft:story/root",
            )
            MinecraftTestSupport.sendCommand(
                server,
                "kick $PLAYER_NAME storage fixture complete",
            )
            MinecraftTestSupport.waitForLog(
                server,
                "$PLAYER_NAME left the game",
            )
            MinecraftTestSupport.closeProcess(client)
            saveAndStop(server)
            assertOfficialLockIsReleased(worldDirectory)
        } catch (caught: Throwable) {
            failure = caught
            throw officialFailure(server, client, caught)
        } finally {
            client?.let { launched ->
                try {
                    MinecraftTestSupport.close(launched)
                } catch (closeFailure: Throwable) {
                    if (failure == null) throw closeFailure
                    failure.addSuppressed(closeFailure)
                }
            }
        }
    }

    private suspend fun mutateAndStopOfficialServer(
        server: OfficialMinecraftServer,
        verificationPhase: String,
    ) {
        try {
            verifyOfficialCompressionMatrix(server, verificationPhase)
            generateWorldStorage(server)
            saveAndStop(server)
        } catch (failure: Throwable) {
            throw officialFailure(server, client = null, failure)
        }
    }

    private suspend fun generateWorldStorage(server: OfficialMinecraftServer) {
        MinecraftTestSupport.sendCommand(
            server,
            "forceload add 0 0 $TERRAIN_MUTATION_BLOCK_X 0",
        )
        MinecraftTestSupport.sendCommand(
            server,
            "scoreboard objectives add storage_audit dummy",
        )
        MinecraftTestSupport.sendCommand(
            server,
            "data modify storage minecraft:storage_audit value set value 1",
        )
        COMPRESSION_PROBES.forEach { probe ->
            MinecraftTestSupport.sendCommand(
                server,
                "setblock ${probe.blockX} 100 0 minecraft:lectern",
            )
        }
        MinecraftTestSupport.sendCommand(
            server,
            "setblock $TERRAIN_MUTATION_BLOCK_X 100 0 minecraft:lectern",
        )
        MinecraftTestSupport.sendCommand(
            server,
            "setblock 1 100 0 minecraft:bell",
        )
        MinecraftTestSupport.sendCommand(
            server,
            "summon minecraft:pig 2 100 2 {NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}",
        )
        MinecraftTestSupport.waitForLog(
            server,
            "Summoned new Pig",
        )
    }

    private suspend fun verifyOfficialCompressionMatrix(
        server: OfficialMinecraftServer,
        verificationPhase: String,
    ) {
        MinecraftTestSupport.sendCommand(
            server,
            "forceload add 0 0 ${COMPRESSION_PROBES.last().blockX} 0",
        )
        COMPRESSION_PROBES.forEach { probe ->
            val logToken =
                "minecraft_protocol_${verificationPhase}_${probe.name}"
            MinecraftTestSupport.sendCommand(
                server,
                "execute if block ${probe.blockX} 100 0 minecraft:lectern run say $logToken",
            )
            MinecraftTestSupport.waitForLog(server, logToken)
        }
    }

    private suspend fun saveAndStop(server: OfficialMinecraftServer) {
        MinecraftTestSupport.sendCommand(server, "save-all flush")
        MinecraftTestSupport.waitForLog(server, "Saved the game")
        val exitCode = MinecraftTestSupport.closeProcess(server)
        check(exitCode == 0) {
            "Official server exited with $exitCode"
        }
    }

    private suspend fun assertOfficialLockIsHeld(
        worldDirectory: Path,
    ) {
        check(MinecraftWorldAccess.isLocked(worldDirectory)) {
            "Official server did not hold session.lock"
        }
        val failure = captureLockAcquisitionFailure(worldDirectory)
        check(failure is IOException) {
            "Expected IOException, got ${failure::class.simpleName}"
        }
        if (failure is WorldLockException) {
            check(
                failure.message?.endsWith(
                    WORLD_LOCK_ALREADY_LOCKED_REASON,
                ) == true,
            ) {
                "Unexpected lock failure message: ${failure.message}"
            }
        }
    }

    private suspend fun captureLockAcquisitionFailure(
        worldDirectory: Path,
    ): Throwable {
        var acquired: MinecraftWorldAccess? = null
        val failure = try {
            acquired = MinecraftWorldAccess.open(worldDirectory)
            null
        } catch (caught: Throwable) {
            caught
        }
        acquired?.close()
        return checkNotNull(failure) {
            "Library acquired the live official world's session.lock"
        }
    }

    private suspend fun assertOfficialLockIsReleased(worldDirectory: Path) {
        check(!MinecraftWorldAccess.isLocked(worldDirectory)) {
            "Official server retained session.lock after exit"
        }
        MinecraftWorldAccess.open(worldDirectory).close()
    }

    private fun exerciseStandalonePolicies(
        worldDirectory: Path,
        audit: AuditResult,
    ) {
        val paths = MinecraftWorldPaths(worldDirectory)
        val nbtFiles = NbtFileStore()
        val fileSystem = nbtFiles.fileSystem

        val levelStore = LevelDataStore(paths, nbtFiles)
        val level = levelStore.read()
        levelStore.write(level)
        check(fileSystem.metadata(paths.previousLevelData).isRegularFile) {
            "level.dat write did not create level.dat_old"
        }
        fileSystem.writeRaw(paths.levelData, CORRUPTED_BYTES)
        check(levelStore.read() == level) {
            "level.dat fallback did not return level.dat_old"
        }
        check(
            fileSystem.list(paths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        ) {
            "level.dat fallback did not preserve the corrupted primary"
        }
        levelStore.write(level)

        val playerKey = audit.playerKeys.first()
        val playerStore = PlayerDataStore(paths, nbtFiles)
        val player = checkNotNull(playerStore.read(playerKey))
        playerStore.write(playerKey, player)
        fileSystem.writeRaw(paths.playerData(playerKey), CORRUPTED_BYTES)
        check(playerStore.read(playerKey) == player) {
            "Player fallback did not return the old data"
        }
        check(
            fileSystem.readFileWithinLimit(
                paths.playerData(playerKey),
                CORRUPTED_BYTES.size,
            ).contentEquals(CORRUPTED_BYTES),
        ) {
            "Player fallback promoted old data over the corrupted primary"
        }
        check(fileSystem.exists(paths.previousPlayerData(playerKey))) {
            "Player fallback removed the old data"
        }
        val playerDirectory = checkNotNull(paths.playerData(playerKey).parent)
        val playerFiles = fileSystem.list(playerDirectory)
        check(
            playerFiles.any {
                it.name.startsWith("${paths.playerData(playerKey).name}_corrupted_")
            },
        ) {
            "Player fallback did not preserve a corrupted copy; directory entries: ${playerFiles.joinToString { it.name }}"
        }
        nbtFiles.writeDirect(paths.playerData(playerKey), player)

        val savedIdentifier = audit.savedDataIdentifiers.first()
        val savedStore = SavedDataFileStore(paths, nbtFiles = nbtFiles)
        val savedData = checkNotNull(savedStore.read(savedIdentifier))
        savedStore.write(savedIdentifier, savedData)
        check(savedStore.read(savedIdentifier) == savedData) {
            "Saved data did not survive direct GZIP rewrite"
        }
    }

    private suspend fun exerciseCompressionMatrix(worldDirectory: Path) {
        val paths = MinecraftWorldPaths(worldDirectory)
        val documents = linkedMapOf<ChunkPosition, NbtDocument>()
        val readingStore = WorldRegionStore(paths)
        try {
            COMPRESSION_PROBES.forEach { probe ->
                documents[probe.position] = checkNotNull(
                    readingStore.readChunkNbt(probe.position),
                ) {
                    "Official fixture generated no chunk ${probe.position}"
                }
            }
        } finally {
            readingStore.close()
        }

        // One mixed region makes the official server exercise every platform codec in one restart. The marker block in
        // each original NBT document proves that the server loaded our bytes instead of accepting only the MCA header or
        // silently regenerating a missing chunk.
        val writingStore = WorldRegionStore(paths)
        try {
            COMPRESSION_PROBES.forEach { probe ->
                writingStore.writeChunkNbt(
                    probe.position,
                    documents.getValue(probe.position),
                    probe.compression,
                )
            }
        } finally {
            writingStore.close()
        }

        val verifyingStore = WorldRegionStore(paths)
        try {
            COMPRESSION_PROBES.forEach { probe ->
                val stored = checkNotNull(
                    verifyingStore.readChunk(probe.position),
                )
                check(stored.compression == probe.compression) {
                    "Chunk ${probe.position} stored ${stored.compression}, expected ${probe.compression}"
                }
                check(
                    verifyingStore.chunkNbtFormat.decode(stored) ==
                            documents.getValue(probe.position),
                ) {
                    "Chunk ${probe.position} changed while writing ${probe.compression}"
                }
            }
        } finally {
            verifyingStore.close()
        }
    }

    private suspend fun exerciseTerrainMutation(
        worldDirectory: Path,
    ): TerrainMutation {
        val paths = MinecraftWorldPaths(worldDirectory)
        val directory = paths.regionDirectory()
        val absolutePosition = TERRAIN_MUTATION_POSITION
        val regionPosition = absolutePosition.region
        val regionPath = paths.regionFile(regionPosition)
        val originalChunk: RegionChunk
        val originalDocument: NbtDocument
        val readingStore = WorldRegionStore(paths)
        try {
            originalChunk = checkNotNull(
                readingStore.readChunk(absolutePosition),
            ) {
                "Official fixture generated no terrain mutation chunk $absolutePosition"
            }
            originalDocument = readingStore.chunkNbtFormat.decode(
                originalChunk,
            )
        } finally {
            readingStore.close()
        }

        val oldSize = checkNotNull(systemFileSystem.metadata(regionPath).size)
        val oldLocation = checkNotNull(
            readRegionHeader(regionPath).location(absolutePosition.local),
        )
        val oldAllocation = readAtMost(
            regionPath,
            oldLocation.byteOffset,
            oldLocation.allocatedBytes,
        )
        check(oldAllocation.size == oldLocation.allocatedBytes)

        val writingStore = WorldRegionStore(paths)
        try {
            writingStore.writeChunk(absolutePosition, originalChunk)
        } finally {
            writingStore.close()
        }

        val newLocation = checkNotNull(
            readRegionHeader(regionPath).location(absolutePosition.local),
        )
        check(newLocation != oldLocation) {
            "Region update overwrote its old allocation"
        }
        check(
            readAtMost(
                regionPath,
                oldLocation.byteOffset,
                oldLocation.allocatedBytes,
            ).contentEquals(oldAllocation),
        ) {
            "Region update erased its old allocation"
        }
        check(checkNotNull(systemFileSystem.metadata(regionPath).size) >= oldSize) {
            "Region update shrank the MCA file"
        }
        val fixtureDocument = externalFixture(originalDocument)
        val externalStore = WorldRegionStore(
            paths = paths,
            configuration = WorldRegionStoreConfiguration(
                syncWrites = true,
                writeCompression = RegionCompression.NONE,
            ),
        )
        try {
            externalStore.writeChunkNbt(absolutePosition, fixtureDocument)
            val stored = checkNotNull(
                externalStore.readChunk(absolutePosition),
            )
            check(stored.payload.isExternal)
            check(externalStore.chunkNbtFormat.decode(stored) == fixtureDocument)
        } finally {
            externalStore.close()
        }
        check(systemFileSystem.exists(paths.externalChunk(absolutePosition))) {
            "External chunk sidecar was not committed"
        }

        return TerrainMutation(
            position = absolutePosition,
            originalDocument = originalDocument,
        )
    }

    private suspend fun restoreInternalAndClearEntity(
        worldDirectory: Path,
        terrainMutation: TerrainMutation,
        entityPosition: ChunkPosition,
    ) {
        val paths = MinecraftWorldPaths(worldDirectory)
        val terrain = WorldRegionStore(paths)
        try {
            terrain.writeChunkNbt(
                terrainMutation.position,
                terrainMutation.originalDocument,
            )
            check(
                checkNotNull(terrain.readChunk(terrainMutation.position))
                    .payload.isExternal.not(),
            )
        } finally {
            terrain.close()
        }
        check(!systemFileSystem.exists(paths.externalChunk(terrainMutation.position))) {
            "Internal rewrite retained the external chunk sidecar"
        }

        val entities = WorldRegionStore(
            paths,
            storage = RegionStorageDirectory.ENTITIES,
        )
        try {
            entities.clearChunk(entityPosition)
            check(entities.readChunk(entityPosition) == null)
        } finally {
            entities.close()
        }
    }

    private suspend fun auditWorld(worldDirectory: Path): AuditResult {
        val paths = MinecraftWorldPaths(worldDirectory)
        val fileSystem = systemFileSystem
        val nbtFiles = NbtFileStore()
        val level = LevelDataStore(paths, nbtFiles).read()
        check(level.root.value["Data"] is NbtCompound) {
            "Official level.dat has no Data compound"
        }

        val playerDirectory = checkNotNull(paths.playerData("probe").parent)
        val playerKeys = regularFiles(playerDirectory, ".dat")
            .filterNot { it.name.endsWith(".dat_old") }
            .map { it.name.removeSuffix(".dat") }
        val playerStore = PlayerDataStore(paths, nbtFiles)
        playerKeys.forEach { playerKey ->
            checkNotNull(playerStore.read(playerKey))
        }

        val savedDirectory = paths.savedDataDirectory()
        val savedDataIdentifiers = savedDataIdentifiers(savedDirectory)
        val savedStore = SavedDataFileStore(paths, nbtFiles = nbtFiles)
        savedDataIdentifiers.forEach { identifier ->
            checkNotNull(savedStore.read(identifier))
        }

        val statisticsDirectory = checkNotNull(paths.statistics("probe").parent)
        val advancementDirectory = checkNotNull(paths.advancement("probe").parent)
        val jsonStore = Utf8JsonFileStore(fileSystem)
        regularFiles(statisticsDirectory, ".json").forEach(jsonStore::read)
        regularFiles(advancementDirectory, ".json").forEach(jsonStore::read)

        val regionFiles = RegionStorageDirectory.entries
            .associateWith { 0 }
            .toMutableMap()
        val chunks = RegionStorageDirectory.entries
            .associateWith { 0 }
            .toMutableMap()
        val regionDiagnostics = RegionStorageDirectory.entries
            .associateWith { mutableListOf<String>() }
        val firstChunks = linkedMapOf<RegionStorageDirectory, ChunkPosition>()
        RegionStorageDirectory.entries.forEach { storage ->
            val directory = paths.regionDirectory(storage)
            if (fileSystem.metadataOrNull(directory)?.isDirectory != true) {
                return@forEach
            }
            val store = WorldRegionStore(paths, storage)
            try {
                regionPositions(directory).forEach { regionPosition ->
                    val region = store.readRegion(regionPosition)
                    regionFiles[storage] = checkNotNull(regionFiles[storage]) + 1
                    val regionPath = directory /
                            "r.${regionPosition.x}.${regionPosition.z}.mca"
                    regionDiagnostics.getValue(storage).add(
                        "$regionPath(size=${fileSystem.metadata(regionPath).size}, readableChunks=${region.chunks.size})",
                    )
                    region.chunks.forEach { (local, chunk) ->
                        store.chunkNbtFormat.decode(chunk)
                        chunks[storage] = checkNotNull(chunks[storage]) + 1
                        if (!firstChunks.containsKey(storage)) {
                            firstChunks[storage] = regionPosition.chunk(local)
                        }
                    }
                }
            } finally {
                store.close()
            }
        }
        return AuditResult(
            regionFiles = regionFiles,
            chunks = chunks,
            regionDiagnostics = regionDiagnostics,
            firstChunks = firstChunks,
            playerKeys = playerKeys,
            savedDataIdentifiers = savedDataIdentifiers,
        )
    }

    private fun requireCompleteOfficialFixture(audit: AuditResult) {
        RegionStorageDirectory.entries.forEach { storage ->
            check(checkNotNull(audit.regionFiles[storage]) > 0) {
                "Official fixture generated no ${storage.directoryName} MCA"
            }
            check(checkNotNull(audit.chunks[storage]) > 0) {
                "Official fixture generated no readable ${storage.directoryName} chunk: ${
                    audit.regionDiagnostics.getValue(
                        storage
                    )
                }"
            }
        }
        check(audit.playerKeys.isNotEmpty()) {
            "Official fixture generated no player NBT"
        }
        check(audit.savedDataIdentifiers.isNotEmpty()) {
            "Official fixture generated no dimension saved-data"
        }
    }

    private fun regionPositions(directory: Path): List<RegionPosition> =
        systemFileSystem.list(directory)
            .mapNotNull { path ->
                REGION_FILE_NAME.matchEntire(path.name)?.let { match ->
                    RegionPosition(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                    )
                }
            }

    private fun regularFiles(directory: Path, suffix: String): List<Path> {
        val fileSystem = systemFileSystem
        if (fileSystem.metadataOrNull(directory)?.isDirectory != true) {
            return emptyList()
        }
        return fileSystem.list(directory).filter { path ->
            path.name.endsWith(suffix) &&
                    fileSystem.metadataOrNull(path)?.isRegularFile == true
        }
    }

    private fun savedDataIdentifiers(directory: Path): List<String> {
        val fileSystem = systemFileSystem
        if (fileSystem.metadataOrNull(directory)?.isDirectory != true) {
            return emptyList()
        }
        val rootSegmentCount = directory.segments.size
        return fileSystem.listRecursively(directory)
            .filter { path ->
                path.name.endsWith(".dat") &&
                        fileSystem.metadataOrNull(path)?.isRegularFile == true
            }
            .map { path ->
                val relativeSegments = path.segments.drop(rootSegmentCount)
                check(relativeSegments.size >= 2) {
                    "Saved-data path has no namespace: $path"
                }
                val namespace = relativeSegments.first()
                val resourcePath = relativeSegments.drop(1)
                    .mapIndexed { index, segment ->
                        if (index == relativeSegments.size - 2) {
                            segment.removeSuffix(".dat")
                        } else {
                            segment
                        }
                    }
                    .joinToString("/")
                "$namespace:$resourcePath"
            }
            .sorted()
            .toList()
    }

    private fun readRegionHeader(path: Path): RegionHeader =
        RegionHeader.decode(readAtMost(path, 0L, REGION_HEADER_BYTES))

    private fun readAtMost(
        path: Path,
        offset: Long,
        byteCount: Int,
    ): ByteArray {
        val handle = systemFileSystem.openLiveReadOnly(path)
        var failure: Throwable? = null
        try {
            val result = ByteArray(byteCount)
            var total = 0
            while (total < result.size) {
                val read = handle.read(
                    offset + total,
                    result,
                    total,
                    result.size - total,
                )
                if (read < 0) break
                check(read > 0) { "File handle made no read progress for $path" }
                total += read
            }
            return if (total == result.size) result else result.copyOf(total)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(failure, handle::close)
        }
    }

    private fun externalFixture(original: NbtDocument): NbtDocument {
        val values = original.root.value.toMutableMap()
        values[EXTERNAL_FIXTURE_TAG] = NbtByteArray(
            ByteArray(EXTERNAL_FIXTURE_BYTES) { index ->
                ((index * 31) xor (index ushr 8)).toByte()
            },
        )
        return NbtDocument(NbtCompound(values))
    }

    private suspend fun officialFailure(
        server: OfficialMinecraftServer,
        client: HeadlessMinecraftClient?,
        failure: Throwable,
    ): AssertionError {
        val clientLog = if (client == null) {
            "<not launched>"
        } else {
            MinecraftTestSupport.logText(client)
        }
        return AssertionError(
            """
            |Official world interoperability failed.
            |--- official server log ---
            |${MinecraftTestSupport.logText(server)}
            |--- official client log ---
            |$clientLog
            """.trimMargin(),
            failure,
        )
    }

    private data class TerrainMutation(
        val position: ChunkPosition,
        val originalDocument: NbtDocument,
    )

    private data class CompressionProbe(
        val name: String,
        val compression: RegionCompression,
        val position: ChunkPosition,
        val blockX: Int,
    )

    private data class AuditResult(
        val regionFiles: Map<RegionStorageDirectory, Int>,
        val chunks: Map<RegionStorageDirectory, Int>,
        val regionDiagnostics: Map<RegionStorageDirectory, List<String>>,
        val firstChunks: Map<RegionStorageDirectory, ChunkPosition>,
        val playerKeys: List<String>,
        val savedDataIdentifiers: List<String>,
    )

    private companion object {
        const val WORLD_NAME = "wio"
        const val PLAYER_NAME = "StorageAudit"
        const val EXTERNAL_FIXTURE_TAG =
            "minecraft_protocol_external_fixture"
        const val EXTERNAL_FIXTURE_BYTES = 1_100_000
        const val TERRAIN_MUTATION_BLOCK_X = 64
        val TERRAIN_MUTATION_POSITION = ChunkPosition(4, 0)
        val COMPRESSION_PROBES = listOf(
            CompressionProbe(
                name = "gzip",
                compression = RegionCompression.GZIP,
                position = ChunkPosition(0, 0),
                blockX = 0,
            ),
            CompressionProbe(
                name = "zlib",
                compression = RegionCompression.ZLIB,
                position = ChunkPosition(1, 0),
                blockX = 16,
            ),
            CompressionProbe(
                name = "none",
                compression = RegionCompression.NONE,
                position = ChunkPosition(2, 0),
                blockX = 32,
            ),
            CompressionProbe(
                name = "lz4",
                compression = RegionCompression.LZ4,
                position = ChunkPosition(3, 0),
                blockX = 48,
            ),
        )
        val CORRUPTED_BYTES = byteArrayOf(1, 2, 3)
        val REGION_FILE_NAME = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")
    }
}

private fun FileSystem.writeRaw(path: Path, bytes: ByteArray) {
    val sink = sink(path)
    val buffer = Buffer().apply { write(bytes) }
    var failure: Throwable? = null
    try {
        sink.write(buffer, bytes.size.toLong())
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeAllPreserving(failure, sink::close)
    }
}
