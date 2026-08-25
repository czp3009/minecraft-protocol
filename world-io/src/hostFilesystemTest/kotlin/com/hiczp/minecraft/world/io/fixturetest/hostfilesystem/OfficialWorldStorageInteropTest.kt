package com.hiczp.minecraft.world.io.fixturetest.hostfilesystem

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.test.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.io.*
import kotlinx.coroutines.CancellationException
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
        timeout = 6.minutes,
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
            val structuredRewrite = exerciseStandalonePolicies(worldDirectory, initial)
            exerciseCompressionMatrix(worldDirectory)
            val terrainMutation = exerciseTerrainMutation(worldDirectory)

            server = MinecraftTestSupport.restartServer(server)
            mutateAndStopOfficialServer(server, "platform_write")
            val afterExternal = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(afterExternal)
            requireStructuredPlayerRewrite(
                worldDirectory,
                structuredRewrite,
                minimumLeaveGame = structuredRewrite.leaveGameAfterRewrite + 1,
            )

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
            requireStructuredPlayerRewrite(
                worldDirectory,
                structuredRewrite,
                minimumLeaveGame = structuredRewrite.leaveGameAfterRewrite + 2,
            )

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
                expectedNewOutput = "$PLAYER_NAME left the game",
            )
            MinecraftTestSupport.closeProcess(client)
            saveAndStop(server)
            assertOfficialLockIsReleased(worldDirectory)
        } catch (caught: CancellationException) {
            failure = caught
            throw caught
        } catch (caught: Throwable) {
            failure = caught
            val wrapped = try {
                officialFailure(server, client, caught)
            } catch (diagnosticCancellation: CancellationException) {
                diagnosticCancellation.addSuppressed(caught)
                failure = diagnosticCancellation
                throw diagnosticCancellation
            }
            failure = wrapped
            throw wrapped
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
        var client: HeadlessMinecraftClient? = null
        var failure: Throwable? = null
        try {
            verifyOfficialCompressionMatrix(server, verificationPhase)
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
            MinecraftTestSupport.waitForLog(server, "$PLAYER_NAME joined the game")
            val advancementToken = "minecraft_protocol_${verificationPhase}_advancement_loaded"
            MinecraftTestSupport.sendCommand(
                server,
                "execute if entity @a[advancements={minecraft:story/root=true}] run say $advancementToken",
                expectedNewOutput = advancementToken,
            )
            MinecraftTestSupport.sendCommand(
                server,
                "kick $PLAYER_NAME structured files loaded",
                expectedNewOutput = "$PLAYER_NAME left the game",
            )
            MinecraftTestSupport.closeProcess(client)
            saveAndStop(server)
        } catch (caught: CancellationException) {
            failure = caught
            throw caught
        } catch (caught: Throwable) {
            failure = caught
            val wrapped = try {
                officialFailure(server, client, caught)
            } catch (diagnosticCancellation: CancellationException) {
                diagnosticCancellation.addSuppressed(caught)
                failure = diagnosticCancellation
                throw diagnosticCancellation
            }
            failure = wrapped
            throw wrapped
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
            expectedNewOutput = "Summoned new Pig",
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
            val logToken = "minecraft_protocol_${verificationPhase}_${probe.name}"
            MinecraftTestSupport.sendCommand(
                server,
                "execute if block ${probe.blockX} 100 0 minecraft:lectern run say $logToken",
                expectedNewOutput = logToken,
            )
        }
    }

    private suspend fun saveAndStop(server: OfficialMinecraftServer) {
        MinecraftTestSupport.sendCommand(server, "save-all flush")
        MinecraftTestSupport.sendCommand(
            server,
            "say $SAVE_COMPLETE_TOKEN",
            expectedNewOutput = SAVE_COMPLETE_TOKEN,
        )
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
        val failure = try {
            MinecraftWorldAccess.open(worldDirectory).use {}
            null
        } catch (caught: CancellationException) {
            throw caught
        } catch (caught: Throwable) {
            caught
        }
        return checkNotNull(failure) {
            "Library acquired the live official world's session.lock"
        }
    }

    private suspend fun assertOfficialLockIsReleased(worldDirectory: Path) {
        check(!MinecraftWorldAccess.isLocked(worldDirectory)) {
            "Official server retained session.lock after exit"
        }
        MinecraftWorldAccess.open(worldDirectory).use {}
    }

    private fun exerciseStandalonePolicies(
        worldDirectory: Path,
        audit: AuditResult,
    ): StructuredPlayerRewrite {
        val paths = MinecraftWorldPaths(worldDirectory)
        val nbtFiles = NbtFileStore()
        val fileSystem = nbtFiles.fileSystem

        val levelStore = LevelDataStore(paths, nbtFiles)
        val level = levelStore.readDocument()
        levelStore.writeDocument(level)
        check(fileSystem.metadata(paths.previousLevelData).isRegularFile) {
            "level.dat write did not create level.dat_old"
        }
        fileSystem.writeRaw(paths.levelData, CORRUPTED_BYTES)
        check(levelStore.readDocument() == level) {
            "level.dat fallback did not return level.dat_old"
        }
        check(
            fileSystem.list(paths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        ) {
            "level.dat fallback did not preserve the corrupted primary"
        }
        levelStore.writeDocument(level)

        val typedLevel = levelStore.read(LevelDat.serializer())
        val renamedLevel = typedLevel.copy(
            data = typedLevel.data.copy(levelName = "typed-storage-fixture"),
        )
        levelStore.write(LevelDat.serializer(), renamedLevel)
        check(levelStore.read(LevelDat.serializer()).data.levelName == "typed-storage-fixture") {
            "Typed level.dat rewrite did not retain the selected-release schema"
        }

        val playerKey = audit.playerKeys.first()
        val playerStore = PlayerDataStore(paths, nbtFiles)
        val player = checkNotNull(playerStore.readDocument(playerKey))
        playerStore.writeDocument(playerKey, player)
        fileSystem.writeRaw(paths.playerData(playerKey), CORRUPTED_BYTES)
        check(playerStore.readDocument(playerKey) == player) {
            "Player fallback did not return the old data"
        }
        check(
            fileSystem.readFileBytes(paths.playerData(playerKey)).contentEquals(CORRUPTED_BYTES),
        ) {
            "Player fallback promoted old data over the corrupted primary"
        }
        check(fileSystem.exists(paths.previousPlayerData(playerKey))) {
            "Player fallback removed the old data"
        }
        val playerDirectory = checkNotNull(paths.playerData(playerKey).parent)
        val playerFiles = fileSystem.list(playerDirectory)
        val playerDirectoryEntries = playerFiles.joinToString { it.name }
        check(
            playerFiles.any {
                it.name.startsWith("${paths.playerData(playerKey).name}_corrupted_")
            },
        ) {
            "Player fallback did not preserve a corrupted copy; directory entries: $playerDirectoryEntries"
        }
        nbtFiles.writeDocument(paths.playerData(playerKey), player)

        val savedIdentifier = audit.savedDataIdentifiers.first()
        val savedStore = SavedDataFileStore(paths, nbtFiles = nbtFiles)
        val savedData = checkNotNull(savedStore.readDocument(savedIdentifier))
        savedStore.writeDocument(savedIdentifier, savedData)
        check(savedStore.readDocument(savedIdentifier) == savedData) {
            "Saved data did not survive direct GZIP rewrite"
        }

        val jsonStore = Utf8JsonFileStore(fileSystem)
        val statisticsPath = paths.statistics(playerKey)
        val statistics = jsonStore.readJson(statisticsPath, PlayerStatistics.serializer())
        val customStatistics = statistics.stats[CUSTOM_STATISTICS].orEmpty()
        val leaveGame = customStatistics[LEAVE_GAME_STATISTIC] ?: 0
        check(leaveGame < Int.MAX_VALUE - 2) {
            "Official fixture leave_game statistic cannot survive two verification disconnects"
        }
        val leaveGameAfterRewrite = leaveGame + 1
        val updatedStatistics = statistics.copy(
            stats = statistics.stats + (
                    CUSTOM_STATISTICS to (customStatistics + (LEAVE_GAME_STATISTIC to leaveGameAfterRewrite))
                    ),
        )
        jsonStore.writeJson(statisticsPath, PlayerStatistics.serializer(), updatedStatistics)
        check(jsonStore.readJson(statisticsPath, PlayerStatistics.serializer()) == updatedStatistics) {
            "Typed player statistics did not survive direct JSON rewrite"
        }

        val advancementPath = paths.advancement(playerKey)
        val advancements = jsonStore.readJson(advancementPath, PlayerAdvancements.serializer())
        check(advancements.advancements.isNotEmpty()) {
            "Official fixture generated no advancement progress"
        }
        jsonStore.writeJson(advancementPath, PlayerAdvancements.serializer(), advancements)
        check(jsonStore.readJson(advancementPath, PlayerAdvancements.serializer()) == advancements) {
            "Typed player advancements did not survive direct JSON rewrite"
        }
        return StructuredPlayerRewrite(
            playerKey = playerKey,
            leaveGameAfterRewrite = leaveGameAfterRewrite,
        )
    }

    private suspend fun exerciseCompressionMatrix(worldDirectory: Path) {
        val paths = MinecraftWorldPaths(worldDirectory)
        val documents = linkedMapOf<ChunkPosition, NbtDocument>()
        val readingStore = RegionStorage(paths)
        try {
            COMPRESSION_PROBES.forEach { probe ->
                documents[probe.position] = checkNotNull(
                    readingStore.readChunkNbtDocument(probe.position),
                ) {
                    "Official fixture generated no chunk ${probe.position}"
                }
            }
        } finally {
            readingStore.close()
        }

        // One mixed region makes the official server exercise every platform codec in one restart. The marker block
        // in each original NBT document proves that the server loaded our bytes instead of accepting only the MCA
        // header or silently regenerating a missing chunk.
        val writingStore = RegionStorage(paths)
        try {
            COMPRESSION_PROBES.forEachIndexed { index, probe ->
                val document = documents.getValue(probe.position)
                if (index == 0) {
                    val codec = strongChunkCodec(document)
                    val chunk = codec.decodeDocument(document, probe.position)
                    documents[probe.position] = codec.encodeDocument(chunk)
                    writingStore.writeChunk(probe.position, chunk, codec, probe.compression)
                } else {
                    writingStore.writeChunkNbtDocument(
                        probe.position,
                        document,
                        probe.compression,
                    )
                }
            }
        } finally {
            writingStore.close()
        }

        val verifyingStore = RegionStorage(paths)
        try {
            COMPRESSION_PROBES.forEach { probe ->
                val stored = checkNotNull(
                    verifyingStore.readCompressedChunk(probe.position),
                )
                check(stored.compression == probe.compression) {
                    "Chunk ${probe.position} stored ${stored.compression}, expected ${probe.compression}"
                }
                check(
                    verifyingStore.chunkNbtFormat.decodeDocument(stored) ==
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
        val originalChunk: CompressedChunk
        val originalDocument: NbtDocument
        val readingStore = RegionStorage(paths)
        try {
            originalChunk = checkNotNull(
                readingStore.readCompressedChunk(absolutePosition),
            ) {
                "Official fixture generated no terrain mutation chunk $absolutePosition"
            }
            originalDocument = readingStore.chunkNbtFormat.decodeDocument(
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

        val writingStore = RegionStorage(paths)
        try {
            writingStore.writeCompressedChunk(absolutePosition, originalChunk)
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
        val externalStore = RegionStorage(
            paths = paths,
            configuration = RegionStorageConfiguration(
                syncWrites = true,
                writeCompression = Compression.NONE,
            ),
        )
        try {
            externalStore.writeChunkNbtDocument(absolutePosition, fixtureDocument)
            val stored = checkNotNull(
                externalStore.readCompressedChunk(absolutePosition),
            )
            check(externalStore.readChunkInfo(absolutePosition)?.placement == AnvilChunkPlacement.EXTERNAL)
            check(externalStore.chunkNbtFormat.decodeDocument(stored) == fixtureDocument)
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
        val terrain = RegionStorage(paths)
        try {
            terrain.writeChunkNbtDocument(
                terrainMutation.position,
                terrainMutation.originalDocument,
            )
            check(
                terrain.readChunkInfo(terrainMutation.position)?.placement == AnvilChunkPlacement.INLINE,
            )
        } finally {
            terrain.close()
        }
        check(!systemFileSystem.exists(paths.externalChunk(terrainMutation.position))) {
            "Internal rewrite retained the external chunk sidecar"
        }

        val entities = RegionStorage(
            paths,
            storage = RegionStorageDirectory.ENTITIES,
        )
        try {
            entities.removeChunk(entityPosition)
            check(entities.readCompressedChunk(entityPosition) == null)
        } finally {
            entities.close()
        }
    }

    private suspend fun auditWorld(worldDirectory: Path): AuditResult {
        val paths = MinecraftWorldPaths(worldDirectory)
        val fileSystem = systemFileSystem
        val nbtFiles = NbtFileStore()
        val levelStore = LevelDataStore(paths, nbtFiles)
        val level = levelStore.readDocument()
        check(level.root.value["Data"] is NbtCompound) {
            "Official level.dat has no Data compound"
        }
        val typedLevel = levelStore.read(LevelDat.serializer())
        check(typedLevel.data.dataVersion == typedLevel.data.versionInfo.id) {
            "Official level.dat DataVersion and Version.Id disagree"
        }

        val playerDirectory = checkNotNull(paths.playerData("probe").parent)
        val playerKeys = regularFiles(playerDirectory, ".dat")
            .filterNot { it.name.endsWith(".dat_old") }
            .map { it.name.removeSuffix(".dat") }
        val playerStore = PlayerDataStore(paths, nbtFiles)
        playerKeys.forEach { playerKey ->
            checkNotNull(playerStore.readDocument(playerKey))
        }

        val savedDirectory = paths.savedDataDirectory()
        val savedDataIdentifiers = savedDataIdentifiers(savedDirectory)
        val savedStore = SavedDataFileStore(paths, nbtFiles = nbtFiles)
        savedDataIdentifiers.forEach { identifier ->
            checkNotNull(savedStore.readDocument(identifier))
        }

        val statisticsDirectory = checkNotNull(paths.statistics("probe").parent)
        val advancementDirectory = checkNotNull(paths.advancement("probe").parent)
        val jsonStore = Utf8JsonFileStore(fileSystem)
        val statisticFiles = regularFiles(statisticsDirectory, ".json")
        val advancementFiles = regularFiles(advancementDirectory, ".json")
        statisticFiles.forEach { path ->
            val statistics = jsonStore.readJson(path, PlayerStatistics.serializer())
            check(statistics.dataVersion == typedLevel.data.dataVersion) {
                "Statistics DataVersion does not match level.dat: $path"
            }
        }
        advancementFiles.forEach { path ->
            val advancements = jsonStore.readJson(path, PlayerAdvancements.serializer())
            check(advancements.dataVersion == typedLevel.data.dataVersion) {
                "Advancements DataVersion does not match level.dat: $path"
            }
        }

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
            val store = RegionStorage(paths, storage)
            try {
                regionPositions(directory).forEach { regionPosition ->
                    val region = checkNotNull(store.readAnvilRegion(regionPosition))
                    regionFiles[storage] = checkNotNull(regionFiles[storage]) + 1
                    val regionPath = directory / "r.${regionPosition.x}.${regionPosition.z}.mca"
                    val regionSize = fileSystem.metadata(regionPath).size
                    regionDiagnostics.getValue(storage).add(
                        "$regionPath(size=$regionSize, readableChunks=${region.chunks.size})",
                    )
                    region.chunks.forEach { (local, record) ->
                        val chunkPosition = regionPosition.chunk(local)
                        val document = store.chunkNbtFormat.decodeDocument(checkNotNull(record.content))
                        if (storage == RegionStorageDirectory.ENTITIES) {
                            val dataVersion = (document.root["DataVersion"] as? NbtInt)?.value
                                ?: error("Official Entity Chunk has no DataVersion: $chunkPosition")
                            val entityChunk = EntityChunkNbtCodec(dataVersion, NbtEntityDataRegistry())
                                .decodeDocument(document, chunkPosition)
                            check(!entityChunk.isEmpty) {
                                "Official Entity storage retained an empty Chunk: $chunkPosition"
                            }
                        }
                        chunks[storage] = checkNotNull(chunks[storage]) + 1
                        if (!firstChunks.containsKey(storage)) {
                            firstChunks[storage] = chunkPosition
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
            statisticFiles = statisticFiles,
            advancementFiles = advancementFiles,
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
        check(audit.statisticFiles.isNotEmpty()) {
            "Official fixture generated no player statistics JSON"
        }
        check(audit.advancementFiles.isNotEmpty()) {
            "Official fixture generated no player advancements JSON"
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

    private fun requireStructuredPlayerRewrite(
        worldDirectory: Path,
        rewrite: StructuredPlayerRewrite,
        minimumLeaveGame: Int,
    ) {
        val paths = MinecraftWorldPaths(worldDirectory)
        val jsonStore = Utf8JsonFileStore(systemFileSystem)
        val statistics = jsonStore.readJson(
            paths.statistics(rewrite.playerKey),
            PlayerStatistics.serializer(),
        )
        val leaveGame = statistics.stats[CUSTOM_STATISTICS]?.get(LEAVE_GAME_STATISTIC)
        check(leaveGame != null && leaveGame >= minimumLeaveGame) {
            "Official server did not load and save the rewritten leave_game statistic: $leaveGame"
        }
        val advancements = jsonStore.readJson(
            paths.advancement(rewrite.playerKey),
            PlayerAdvancements.serializer(),
        )
        check(advancements.advancements[ROOT_ADVANCEMENT]?.done == true) {
            "Official server did not retain the rewritten root advancement"
        }
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

    private fun strongChunkCodec(document: NbtDocument): ChunkNbtCodec<BlockStateDescriptor, String> {
        val dataVersion = (document.root["DataVersion"] as? NbtInt)?.value
            ?: error("Official terrain Chunk has no integer DataVersion")
        val minSectionY = (document.root["yPos"] as? NbtInt)?.value
            ?: error("Official terrain Chunk has no integer yPos")
        val sections = document.root["sections"] as? NbtList
            ?: error("Official terrain Chunk has no Section list")
        val maxSemanticSectionY = sections.value.mapNotNull { tag ->
            val section = tag as? NbtCompound ?: return@mapNotNull null
            if (section["block_states"] == null || section["biomes"] == null) return@mapNotNull null
            (section["Y"] as? NbtByte)?.value?.toInt()
        }.maxOrNull() ?: minSectionY
        check(maxSemanticSectionY >= minSectionY) {
            "Official terrain Chunk has a semantic Section below yPos"
        }
        return ChunkNbtCodec(
            ChunkNbtContext(
                layout = ChunkLayout(
                    minSectionY = minSectionY,
                    sectionCount = maxSemanticSectionY - minSectionY + 1,
                ),
                registries = ChunkDataRegistries(
                    blockStates = DescriptorBlockStateRegistry(),
                    biomes = NamedBiomeRegistry(),
                ),
                expectedDataVersion = dataVersion,
            ),
        )
    }

    private suspend fun officialFailure(
        server: OfficialMinecraftServer,
        client: HeadlessMinecraftClient?,
        failure: Throwable,
    ): AssertionError {
        val clientLog = if (client == null) {
            "<not launched>"
        } else {
            diagnosticLog(client, failure, "official client")
        }
        val serverLog = diagnosticLog(server, failure, "official server")
        return AssertionError(
            """
            |Official world interoperability failed.
            |--- failure ---
            |${failure::class.simpleName}: ${failure.message}
            |--- official server log ---
            |$serverLog
            |--- official client log ---
            |$clientLog
            """.trimMargin(),
            failure,
        )
    }

    private suspend fun diagnosticLog(
        resource: MinecraftTestResource,
        failure: Throwable,
        label: String,
    ): String = try {
        MinecraftTestSupport.logText(resource)
    } catch (logFailure: CancellationException) {
        throw logFailure
    } catch (logFailure: Throwable) {
        failure.addSuppressed(logFailure)
        "<$label log unavailable>"
    }

    private data class TerrainMutation(
        val position: ChunkPosition,
        val originalDocument: NbtDocument,
    )

    private data class CompressionProbe(
        val name: String,
        val compression: Compression,
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
        val statisticFiles: List<Path>,
        val advancementFiles: List<Path>,
    )

    private data class StructuredPlayerRewrite(
        val playerKey: String,
        val leaveGameAfterRewrite: Int,
    )

    private companion object {
        const val WORLD_NAME = "wio"
        const val PLAYER_NAME = "StorageAudit"
        const val SAVE_COMPLETE_TOKEN = "minecraft_protocol_save_complete"
        const val CUSTOM_STATISTICS = "minecraft:custom"
        const val LEAVE_GAME_STATISTIC = "minecraft:leave_game"
        const val ROOT_ADVANCEMENT = "minecraft:story/root"
        const val EXTERNAL_FIXTURE_TAG = "minecraft_protocol_external_fixture"
        const val EXTERNAL_FIXTURE_BYTES = 1_100_000
        const val TERRAIN_MUTATION_BLOCK_X = 64
        val TERRAIN_MUTATION_POSITION = ChunkPosition(4, 0)
        val COMPRESSION_PROBES = listOf(
            CompressionProbe(
                name = "gzip",
                compression = Compression.GZIP,
                position = ChunkPosition(0, 0),
                blockX = 0,
            ),
            CompressionProbe(
                name = "zlib",
                compression = Compression.ZLIB,
                position = ChunkPosition(1, 0),
                blockX = 16,
            ),
            CompressionProbe(
                name = "none",
                compression = Compression.NONE,
                position = ChunkPosition(2, 0),
                blockX = 32,
            ),
            CompressionProbe(
                name = "lz4",
                compression = Compression.LZ4,
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
