package com.hiczp.minecraft.world.io.fixturetest.hostfilesystem

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.test.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.data.*
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
import kotlin.time.Duration.Companion.seconds

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
            var officialMinecraftServer = initialServer
            val workingDirectory = MinecraftTestSupport
                .hostWorkingDirectory(officialMinecraftServer)
                .toPath()
            val worldDirectory = workingDirectory / WORLD_NAME

            prepareOfficialWorld(officialMinecraftServer, worldDirectory)
            val initial = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(initial)
            val structuredPlayerRewrite = exerciseStandalonePolicies(worldDirectory, initial)
            exerciseCompressionMatrix(worldDirectory)
            val terrainMutation = exerciseTerrainMutation(worldDirectory)

            officialMinecraftServer = MinecraftTestSupport.restartServer(officialMinecraftServer)
            mutateAndStopOfficialServer(officialMinecraftServer, "platform_write")
            val afterExternal = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(afterExternal)
            requireStructuredPlayerRewrite(
                worldDirectory,
                structuredPlayerRewrite,
                minimumLeaveGame = structuredPlayerRewrite.leaveGameAfterRewrite + 1,
            )

            restoreInternalAndClearEntity(
                worldDirectory = worldDirectory,
                terrainMutation = terrainMutation,
                entityPosition = checkNotNull(
                    afterExternal.firstChunks[RegionStorageDirectory.ENTITIES],
                ),
            )

            officialMinecraftServer = MinecraftTestSupport.restartServer(officialMinecraftServer)
            mutateAndStopOfficialServer(officialMinecraftServer, "official_resave")
            val final = auditWorld(worldDirectory)
            requireCompleteOfficialFixture(final)
            requireStructuredPlayerRewrite(
                worldDirectory,
                structuredPlayerRewrite,
                minimumLeaveGame = structuredPlayerRewrite.leaveGameAfterRewrite + 2,
            )

            MinecraftTestSupport.deleteWorkingDirectory(officialMinecraftServer)
            check(!systemFileSystem.exists(workingDirectory)) {
                "Fixture Host working directory remained after deletion"
            }
        }
    }

    private suspend fun prepareOfficialWorld(
        officialMinecraftServer: OfficialMinecraftServer,
        worldDirectory: Path,
    ) {
        var activeOfficialMinecraftServer = officialMinecraftServer
        var headlessMinecraftClient: HeadlessMinecraftClient? = null
        var failure: Throwable? = null
        try {
            assertOfficialLockIsHeld(worldDirectory)
            generateWorldStorage(activeOfficialMinecraftServer)
            headlessMinecraftClient = MinecraftTestSupport.newHeadlessClient(
                HeadlessMinecraftClientConfiguration(
                    playerName = PLAYER_NAME,
                ),
            )
            activeOfficialMinecraftServer = connectOfficialClient(
                officialMinecraftServer = activeOfficialMinecraftServer,
                headlessMinecraftClient = headlessMinecraftClient,
            )
            MinecraftTestSupport.sendCommand(
                activeOfficialMinecraftServer,
                "advancement grant $PLAYER_NAME only minecraft:story/root",
            )
            MinecraftTestSupport.sendCommand(
                activeOfficialMinecraftServer,
                "kick $PLAYER_NAME storage fixture complete",
                expectedNewOutput = "$PLAYER_NAME left the game",
            )
            MinecraftTestSupport.closeProcess(headlessMinecraftClient)
            saveAndStop(activeOfficialMinecraftServer)
            assertOfficialLockIsReleased(worldDirectory)
        } catch (caught: CancellationException) {
            failure = caught
            throw caught
        } catch (caught: Throwable) {
            failure = caught
            val wrapped = try {
                officialFailure(activeOfficialMinecraftServer, headlessMinecraftClient, caught)
            } catch (diagnosticCancellation: CancellationException) {
                diagnosticCancellation.addSuppressed(caught)
                failure = diagnosticCancellation
                throw diagnosticCancellation
            }
            failure = wrapped
            throw wrapped
        } finally {
            headlessMinecraftClient?.let { launched ->
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
        officialMinecraftServer: OfficialMinecraftServer,
        verificationPhase: String,
    ) {
        var activeOfficialMinecraftServer = officialMinecraftServer
        var headlessMinecraftClient: HeadlessMinecraftClient? = null
        var failure: Throwable? = null
        try {
            verifyOfficialCompressionMatrix(activeOfficialMinecraftServer, verificationPhase)
            generateWorldStorage(activeOfficialMinecraftServer)
            headlessMinecraftClient = MinecraftTestSupport.newHeadlessClient(
                HeadlessMinecraftClientConfiguration(
                    playerName = PLAYER_NAME,
                ),
            )
            activeOfficialMinecraftServer = connectOfficialClient(
                officialMinecraftServer = activeOfficialMinecraftServer,
                headlessMinecraftClient = headlessMinecraftClient,
            )
            val advancementToken = "minecraft_protocol_${verificationPhase}_advancement_loaded"
            MinecraftTestSupport.sendCommand(
                activeOfficialMinecraftServer,
                "execute if entity @a[advancements={minecraft:story/root=true}] run say $advancementToken",
                expectedNewOutput = advancementToken,
            )
            MinecraftTestSupport.sendCommand(
                activeOfficialMinecraftServer,
                "kick $PLAYER_NAME structured files loaded",
                expectedNewOutput = "$PLAYER_NAME left the game",
            )
            MinecraftTestSupport.closeProcess(headlessMinecraftClient)
            saveAndStop(activeOfficialMinecraftServer)
        } catch (caught: CancellationException) {
            failure = caught
            throw caught
        } catch (caught: Throwable) {
            failure = caught
            val wrapped = try {
                officialFailure(activeOfficialMinecraftServer, headlessMinecraftClient, caught)
            } catch (diagnosticCancellation: CancellationException) {
                diagnosticCancellation.addSuppressed(caught)
                failure = diagnosticCancellation
                throw diagnosticCancellation
            }
            failure = wrapped
            throw wrapped
        } finally {
            headlessMinecraftClient?.let { launched ->
                try {
                    MinecraftTestSupport.close(launched)
                } catch (closeFailure: Throwable) {
                    if (failure == null) throw closeFailure
                    failure.addSuppressed(closeFailure)
                }
            }
        }
    }

    private suspend fun connectOfficialClient(
        officialMinecraftServer: OfficialMinecraftServer,
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): OfficialMinecraftServer {
        var activeOfficialMinecraftServer = officialMinecraftServer
        var lastConnectionFailure: Throwable? = null
        val failures = mutableListOf<String>()
        repeat(MAXIMUM_CONNECTION_ATTEMPTS) { index ->
            val attempt = index + 1
            val commandState = MinecraftTestSupport.connectHeadlessClient(
                headlessMinecraftClient = headlessMinecraftClient,
                minecraftTestEndpoint = activeOfficialMinecraftServer.minecraftTestEndpoint,
            )
            try {
                MinecraftTestSupport.waitForLog(
                    minecraftTestResource = activeOfficialMinecraftServer,
                    marker = "$PLAYER_NAME joined the game",
                    timeout = CONNECTION_ATTEMPT_TIMEOUT,
                )
                return activeOfficialMinecraftServer
            } catch (caught: CancellationException) {
                throw caught
            } catch (caught: Throwable) {
                if (!MinecraftTestSupport.isAlive(activeOfficialMinecraftServer) ||
                    !MinecraftTestSupport.isAlive(headlessMinecraftClient)
                ) {
                    throw caught
                }
                lastConnectionFailure = caught
                val finalState = MinecraftTestSupport.headlessClientState(headlessMinecraftClient)
                val stateChange = "${commandState.description()} -> ${finalState.description()}"
                failures += "attempt $attempt: no join in $CONNECTION_ATTEMPT_TIMEOUT; GUI $stateChange"
            }
            if (attempt < MAXIMUM_CONNECTION_ATTEMPTS) {
                MinecraftTestSupport.disconnectHeadlessClient(headlessMinecraftClient)
                activeOfficialMinecraftServer = MinecraftTestSupport.restartServer(activeOfficialMinecraftServer)
            }
        }
        val details = failures.joinToString(separator = "\n- ", prefix = "- ")
        throw IllegalStateException(
            "Official client did not join after $MAXIMUM_CONNECTION_ATTEMPTS attempts:\n$details",
            lastConnectionFailure,
        )
    }

    private fun HeadlessMinecraftClientState.description(): String = screenClassName ?: "no displayed GUI"

    private suspend fun generateWorldStorage(officialMinecraftServer: OfficialMinecraftServer) {
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "forceload add 0 0 $TERRAIN_MUTATION_BLOCK_X 0",
        )
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "scoreboard objectives add storage_audit dummy",
        )
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "data modify storage minecraft:storage_audit value set value 1",
        )
        COMPRESSION_PROBES.forEach { compressionProbe ->
            MinecraftTestSupport.sendCommand(
                officialMinecraftServer,
                "setblock ${compressionProbe.blockX} 100 0 minecraft:lectern",
            )
        }
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "setblock $TERRAIN_MUTATION_BLOCK_X 100 0 minecraft:lectern",
        )
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "setblock 1 100 0 minecraft:bell",
        )
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "summon minecraft:pig 2 100 2 {NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}",
            expectedNewOutput = "Summoned new Pig",
        )
    }

    private suspend fun verifyOfficialCompressionMatrix(
        officialMinecraftServer: OfficialMinecraftServer,
        verificationPhase: String,
    ) {
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "forceload add 0 0 ${COMPRESSION_PROBES.last().blockX} 0",
        )
        COMPRESSION_PROBES.forEach { compressionProbe ->
            val logToken = "minecraft_protocol_${verificationPhase}_${compressionProbe.name}"
            MinecraftTestSupport.sendCommand(
                officialMinecraftServer,
                "execute if block ${compressionProbe.blockX} 100 0 minecraft:lectern run say $logToken",
                expectedNewOutput = logToken,
            )
        }
    }

    private suspend fun saveAndStop(officialMinecraftServer: OfficialMinecraftServer) {
        MinecraftTestSupport.sendCommand(officialMinecraftServer, "save-all flush")
        MinecraftTestSupport.sendCommand(
            officialMinecraftServer,
            "say $SAVE_COMPLETE_TOKEN",
            expectedNewOutput = SAVE_COMPLETE_TOKEN,
        )
        val exitCode = MinecraftTestSupport.closeProcess(officialMinecraftServer)
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
        auditResult: AuditResult,
    ): StructuredPlayerRewrite {
        val minecraftWorldPaths = MinecraftWorldPaths(worldDirectory)
        val nbtFileStore = NbtFileStore()
        val fileSystem = nbtFileStore.fileSystem

        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        val level = levelDataStore.readDocument()
        levelDataStore.writeDocument(level)
        check(fileSystem.metadata(minecraftWorldPaths.previousLevelData).isRegularFile) {
            "level.dat write did not create level.dat_old"
        }
        fileSystem.writeRaw(minecraftWorldPaths.levelData, CORRUPTED_BYTES)
        check(levelDataStore.readDocument() == level) {
            "level.dat fallback did not return level.dat_old"
        }
        check(
            fileSystem.list(minecraftWorldPaths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        ) {
            "level.dat fallback did not preserve the corrupted primary"
        }
        levelDataStore.writeDocument(level)

        val typedLevel = levelDataStore.read(LevelDat.serializer())
        val renamedLevel = typedLevel.copy(
            data = typedLevel.data.copy(levelName = "typed-storage-fixture"),
        )
        levelDataStore.write(renamedLevel, LevelDat.serializer())
        check(levelDataStore.read(LevelDat.serializer()).data.levelName == "typed-storage-fixture") {
            "Typed level.dat rewrite did not retain the selected-release schema"
        }

        val playerKey = auditResult.playerKeys.first()
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        val player = checkNotNull(playerDataStore.readDocument(playerKey))
        playerDataStore.writeDocument(playerKey, player)
        fileSystem.writeRaw(minecraftWorldPaths.playerData(playerKey), CORRUPTED_BYTES)
        check(playerDataStore.readDocument(playerKey) == player) {
            "Player fallback did not return the old data"
        }
        check(
            fileSystem.readFileBytes(minecraftWorldPaths.playerData(playerKey)).contentEquals(CORRUPTED_BYTES),
        ) {
            "Player fallback promoted old data over the corrupted primary"
        }
        check(fileSystem.exists(minecraftWorldPaths.previousPlayerData(playerKey))) {
            "Player fallback removed the old data"
        }
        val playerDirectory = checkNotNull(minecraftWorldPaths.playerData(playerKey).parent)
        val playerFiles = fileSystem.list(playerDirectory)
        val playerDirectoryEntries = playerFiles.joinToString { it.name }
        check(
            playerFiles.any {
                it.name.startsWith("${minecraftWorldPaths.playerData(playerKey).name}_corrupted_")
            },
        ) {
            "Player fallback did not preserve a corrupted copy; directory entries: $playerDirectoryEntries"
        }
        nbtFileStore.writeDocument(minecraftWorldPaths.playerData(playerKey), player)
        val typedPlayer = checkNotNull(playerDataStore.read(playerKey, PlayerData.serializer()))
        playerDataStore.write(playerKey, typedPlayer, PlayerData.serializer())
        check(playerDataStore.read(playerKey, PlayerData.serializer()) == typedPlayer) {
            "Typed player data did not survive primary/previous replacement"
        }

        val savedDataId = auditResult.savedDataIds.first()
        val savedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.Overworld),
            nbtFileStore,
        )
        val savedData = checkNotNull(savedDataStore.readDocument(savedDataId))
        savedDataStore.writeDocument(savedDataId, savedData)
        check(savedDataStore.readDocument(savedDataId) == savedData) {
            "Saved data did not survive direct GZIP rewrite"
        }
        rewriteTypedSavedData(
            savedDataStore,
            WORLD_BORDER_ID,
            SavedDataFile.serializer(WorldBorderData.serializer()),
        )
        rewriteTypedSavedData(
            savedDataStore,
            CHUNK_TICKETS_ID,
            SavedDataFile.serializer(ChunkTicketsData.serializer()),
        )
        rewriteTypedSavedData(
            savedDataStore,
            RAIDS_ID,
            SavedDataFile.serializer(RaidsData.serializer()),
        )
        val endSavedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.End),
            nbtFileStore,
        )
        rewriteTypedSavedData(
            endSavedDataStore,
            ENDER_DRAGON_FIGHT_ID,
            SavedDataFile.serializer(EnderDragonFightData.serializer()),
        )

        val utf8JsonFileStore = Utf8JsonFileStore(fileSystem)
        val statisticsPath = minecraftWorldPaths.statistics(playerKey)
        val playerStatistics = utf8JsonFileStore.readJson(statisticsPath, PlayerStatistics.serializer())
        val customStatistics = playerStatistics.stats[CUSTOM_STATISTICS].orEmpty()
        val leaveGame = customStatistics[LEAVE_GAME_STATISTIC] ?: 0
        check(leaveGame < Int.MAX_VALUE - 2) {
            "Official fixture leave_game statistic cannot survive two verification disconnects"
        }
        val leaveGameAfterRewrite = leaveGame + 1
        val updatedStatistics = playerStatistics.copy(
            stats = playerStatistics.stats + (
                    CUSTOM_STATISTICS to (customStatistics + (LEAVE_GAME_STATISTIC to leaveGameAfterRewrite))
                    ),
        )
        utf8JsonFileStore.writeJson(statisticsPath, updatedStatistics, PlayerStatistics.serializer())
        check(utf8JsonFileStore.readJson(statisticsPath, PlayerStatistics.serializer()) == updatedStatistics) {
            "Typed player statistics did not survive direct JSON rewrite"
        }

        val advancementPath = minecraftWorldPaths.advancements(playerKey)
        val playerAdvancements = utf8JsonFileStore.readJson(advancementPath, PlayerAdvancements.serializer())
        check(playerAdvancements.advancements.isNotEmpty()) {
            "Official fixture generated no advancement progress"
        }
        utf8JsonFileStore.writeJson(advancementPath, playerAdvancements, PlayerAdvancements.serializer())
        check(
            utf8JsonFileStore.readJson(advancementPath, PlayerAdvancements.serializer()) == playerAdvancements,
        ) {
            "Typed player advancements did not survive direct JSON rewrite"
        }
        return StructuredPlayerRewrite(
            playerKey = playerKey,
            leaveGameAfterRewrite = leaveGameAfterRewrite,
        )
    }

    private suspend fun exerciseCompressionMatrix(worldDirectory: Path) {
        val minecraftWorldPaths = MinecraftWorldPaths(worldDirectory)
        val documents = linkedMapOf<ChunkPosition, NbtDocument>()
        val readingStore = CoordinatedRegionStore(minecraftWorldPaths)
        try {
            COMPRESSION_PROBES.forEach { compressionProbe ->
                documents[compressionProbe.chunkPosition] = checkNotNull(
                    readingStore.readChunkNbtDocument(compressionProbe.chunkPosition),
                ) {
                    "Official fixture generated no chunk ${compressionProbe.chunkPosition}"
                }
            }
        } finally {
            readingStore.close()
        }

        // One mixed region makes the official server exercise every platform codec in one restart. The marker block
        // in each original NBT document proves that the server loaded our bytes instead of accepting only the MCA
        // header or silently regenerating a missing chunk.
        val writingStore = CoordinatedRegionStore(minecraftWorldPaths)
        try {
            COMPRESSION_PROBES.forEachIndexed { index, compressionProbe ->
                val nbtDocument = documents.getValue(compressionProbe.chunkPosition)
                if (index == 0) {
                    val chunkNbtCodec = strongChunkCodec(nbtDocument)
                    val chunk = chunkNbtCodec.decodeDocument(nbtDocument, compressionProbe.chunkPosition)
                    documents[compressionProbe.chunkPosition] = chunkNbtCodec.encodeDocument(chunk)
                    writingStore.writeChunk(
                        compressionProbe.chunkPosition,
                        chunk,
                        chunkNbtCodec,
                        compressionProbe.compression
                    )
                } else {
                    writingStore.writeChunkNbtDocument(
                        compressionProbe.chunkPosition,
                        nbtDocument,
                        compressionProbe.compression,
                    )
                }
            }
        } finally {
            writingStore.close()
        }

        val verifyingStore = CoordinatedRegionStore(minecraftWorldPaths)
        try {
            COMPRESSION_PROBES.forEach { compressionProbe ->
                val chunkPosition = compressionProbe.chunkPosition
                val expectedCompression = compressionProbe.compression
                val stored = checkNotNull(
                    verifyingStore.readCompressedChunk(chunkPosition),
                )
                check(stored.compression == expectedCompression) {
                    "Chunk $chunkPosition stored ${stored.compression}, expected $expectedCompression"
                }
                check(
                    verifyingStore.chunkNbtFormat.decodeDocument(stored) ==
                            documents.getValue(chunkPosition),
                ) {
                    "Chunk $chunkPosition changed while writing $expectedCompression"
                }
            }
        } finally {
            verifyingStore.close()
        }
    }

    private suspend fun exerciseTerrainMutation(
        worldDirectory: Path,
    ): TerrainMutation {
        val minecraftWorldPaths = MinecraftWorldPaths(worldDirectory)
        val directory = minecraftWorldPaths.regionDirectory()
        val absolutePosition = TERRAIN_MUTATION_POSITION
        val regionPosition = absolutePosition.regionPosition
        val regionPath = minecraftWorldPaths.regionFile(regionPosition)
        val originalChunk: CompressedChunk
        val originalDocument: NbtDocument
        val readingStore = CoordinatedRegionStore(minecraftWorldPaths)
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
            readRegionHeader(regionPath).location(absolutePosition.localChunkPosition),
        )
        val oldAllocation = readAtMost(
            regionPath,
            oldLocation.byteOffset,
            oldLocation.allocatedBytes,
        )
        check(oldAllocation.size == oldLocation.allocatedBytes)

        val writingStore = CoordinatedRegionStore(minecraftWorldPaths)
        try {
            writingStore.writeCompressedChunk(absolutePosition, originalChunk)
        } finally {
            writingStore.close()
        }

        val newLocation = checkNotNull(
            readRegionHeader(regionPath).location(absolutePosition.localChunkPosition),
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
        val externalStore = CoordinatedRegionStore(
            minecraftWorldPaths = minecraftWorldPaths,
            regionStorageConfiguration = RegionStorageConfiguration(
                syncWrites = true,
                writeCompression = Compression.NONE,
            ),
        )
        try {
            externalStore.writeChunkNbtDocument(absolutePosition, fixtureDocument)
            val stored = checkNotNull(
                externalStore.readCompressedChunk(absolutePosition),
            )
            check(externalStore.readChunkInfo(absolutePosition)?.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL)
            check(externalStore.chunkNbtFormat.decodeDocument(stored) == fixtureDocument)
        } finally {
            externalStore.close()
        }
        check(systemFileSystem.exists(minecraftWorldPaths.externalChunk(absolutePosition))) {
            "External chunk sidecar was not committed"
        }

        return TerrainMutation(
            chunkPosition = absolutePosition,
            originalDocument = originalDocument,
        )
    }

    private suspend fun restoreInternalAndClearEntity(
        worldDirectory: Path,
        terrainMutation: TerrainMutation,
        entityPosition: ChunkPosition,
    ) {
        val minecraftWorldPaths = MinecraftWorldPaths(worldDirectory)
        val terrain = CoordinatedRegionStore(minecraftWorldPaths)
        try {
            terrain.writeChunkNbtDocument(
                terrainMutation.chunkPosition,
                terrainMutation.originalDocument,
            )
            check(
                terrain.readChunkInfo(terrainMutation.chunkPosition)?.anvilChunkPlacement == AnvilChunkPlacement.INLINE,
            )
        } finally {
            terrain.close()
        }
        check(!systemFileSystem.exists(minecraftWorldPaths.externalChunk(terrainMutation.chunkPosition))) {
            "Internal rewrite retained the external chunk sidecar"
        }

        val entities = CoordinatedRegionStore(
            minecraftWorldPaths,
            regionStorageDirectory = RegionStorageDirectory.ENTITIES,
        )
        try {
            entities.removeChunk(entityPosition)
            check(entities.readCompressedChunk(entityPosition) == null)
        } finally {
            entities.close()
        }
    }

    private suspend fun auditWorld(worldDirectory: Path): AuditResult {
        val minecraftWorldPaths = MinecraftWorldPaths(worldDirectory)
        val fileSystem = systemFileSystem
        val nbtFileStore = NbtFileStore()
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        val level = levelDataStore.readDocument()
        check(level.root.value["Data"] is NbtCompound) {
            "Official level.dat has no Data compound"
        }
        val typedLevel = levelDataStore.read(LevelDat.serializer())
        check(typedLevel.data.dataVersion == typedLevel.data.versionInfo.id) {
            "Official level.dat DataVersion and Version.Id disagree"
        }

        val playerDirectory = checkNotNull(minecraftWorldPaths.playerData("probe").parent)
        val playerKeys = regularFiles(playerDirectory, ".dat")
            .filterNot { it.name.endsWith(".dat_old") }
            .map { it.name.removeSuffix(".dat") }
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        playerKeys.forEach { playerKey ->
            checkNotNull(playerDataStore.readDocument(playerKey))
            val playerData = checkNotNull(playerDataStore.read(playerKey, PlayerData.serializer()))
            check(playerData.dataVersion == typedLevel.data.dataVersion) {
                "Player DataVersion does not match level.dat: $playerKey"
            }
        }

        val savedDirectory = minecraftWorldPaths.savedDataDirectory(SavedDataScope.Dimension(DimensionId.Overworld))
        val savedDataIds = savedDataIds(savedDirectory)
        val savedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.Overworld),
            nbtFileStore,
        )
        savedDataIds.forEach { savedDataId ->
            checkNotNull(savedDataStore.readDocument(savedDataId))
        }
        checkNotNull(
            savedDataStore.read(
                WORLD_BORDER_ID,
                SavedDataFile.serializer(WorldBorderData.serializer()),
            ),
        )
        checkNotNull(
            savedDataStore.read(
                CHUNK_TICKETS_ID,
                SavedDataFile.serializer(ChunkTicketsData.serializer()),
            ),
        )
        checkNotNull(
            savedDataStore.read(
                RAIDS_ID,
                SavedDataFile.serializer(RaidsData.serializer()),
            ),
        )
        val endSavedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.End),
            nbtFileStore,
        )
        checkNotNull(
            endSavedDataStore.read(
                ENDER_DRAGON_FIGHT_ID,
                SavedDataFile.serializer(EnderDragonFightData.serializer()),
            ),
        )

        val rootSavedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.WorldRoot,
            nbtFileStore,
        )
        rootSavedDataStore.read(
            SavedDataId("world_gen_settings"),
            SavedDataFile.serializer(WorldGenSettingsData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("world_clocks"),
            SavedDataFile.serializer(WorldClocksData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("weather"),
            SavedDataFile.serializer(WeatherData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("wandering_trader"),
            SavedDataFile.serializer(WanderingTraderData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("stopwatches"),
            SavedDataFile.serializer(StopwatchesData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("scoreboard"),
            SavedDataFile.serializer(ScoreboardData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("scheduled_events"),
            SavedDataFile.serializer(ScheduledEventsData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("random_sequences"),
            SavedDataFile.serializer(RandomSequencesData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("game_rules"),
            SavedDataFile.serializer(GameRulesData.serializer()),
        )
        rootSavedDataStore.read(
            SavedDataId("custom_boss_events"),
            SavedDataFile.serializer(CustomBossEventsData.serializer()),
        )

        val statisticsDirectory = checkNotNull(minecraftWorldPaths.statistics("probe").parent)
        val advancementDirectory = checkNotNull(minecraftWorldPaths.advancements("probe").parent)
        val utf8JsonFileStore = Utf8JsonFileStore(fileSystem)
        val statisticFiles = regularFiles(statisticsDirectory, ".json")
        val advancementFiles = regularFiles(advancementDirectory, ".json")
        statisticFiles.forEach { path ->
            val playerStatistics = utf8JsonFileStore.readJson(path, PlayerStatistics.serializer())
            check(playerStatistics.dataVersion == typedLevel.data.dataVersion) {
                "Statistics DataVersion does not match level.dat: $path"
            }
        }
        advancementFiles.forEach { path ->
            val playerAdvancements = utf8JsonFileStore.readJson(path, PlayerAdvancements.serializer())
            check(playerAdvancements.dataVersion == typedLevel.data.dataVersion) {
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
        RegionStorageDirectory.entries.forEach { regionStorageDirectory ->
            val directory = minecraftWorldPaths.regionDirectory(regionStorageDirectory)
            if (fileSystem.metadataOrNull(directory)?.isDirectory != true) {
                return@forEach
            }
            val regionStorage = CoordinatedRegionStore(minecraftWorldPaths, regionStorageDirectory)
            try {
                regionPositions(directory).forEach { regionPosition ->
                    val positionedAnvilRegion = checkNotNull(regionStorage.readAnvilRegion(regionPosition))
                    regionFiles[regionStorageDirectory] = checkNotNull(regionFiles[regionStorageDirectory]) + 1
                    val regionPath = directory / "r.${regionPosition.x}.${regionPosition.z}.mca"
                    val regionSize = fileSystem.metadata(regionPath).size
                    regionDiagnostics.getValue(regionStorageDirectory).add(
                        "$regionPath(size=$regionSize, readableChunks=${positionedAnvilRegion.chunks.size})",
                    )
                    positionedAnvilRegion.chunks.forEach { (localChunkPosition, anvilChunkRecord) ->
                        val chunkPosition = regionPosition.chunk(localChunkPosition)
                        val nbtDocument =
                            regionStorage.chunkNbtFormat.decodeDocument(checkNotNull(anvilChunkRecord.content))
                        if (regionStorageDirectory == RegionStorageDirectory.ENTITIES) {
                            val entityChunk = EntityChunkNbtCodec(NbtEntityDataRegistry())
                                .decodeDocument(nbtDocument, chunkPosition)
                            check(!entityChunk.isEmpty) {
                                "Official Entity storage retained an empty Chunk: $chunkPosition"
                            }
                        } else if (regionStorageDirectory == RegionStorageDirectory.POINTS_OF_INTEREST) {
                            PoiChunkNbtCodec().decodeDocument(nbtDocument, chunkPosition)
                        }
                        chunks[regionStorageDirectory] = checkNotNull(chunks[regionStorageDirectory]) + 1
                        if (!firstChunks.containsKey(regionStorageDirectory)) {
                            firstChunks[regionStorageDirectory] = chunkPosition
                        }
                    }
                }
            } finally {
                regionStorage.close()
            }
        }
        return AuditResult(
            regionFiles = regionFiles,
            chunks = chunks,
            regionDiagnostics = regionDiagnostics,
            firstChunks = firstChunks,
            playerKeys = playerKeys,
            savedDataIds = savedDataIds,
            statisticFiles = statisticFiles,
            advancementFiles = advancementFiles,
        )
    }

    private fun requireCompleteOfficialFixture(auditResult: AuditResult) {
        RegionStorageDirectory.entries.forEach { regionStorageDirectory ->
            check(checkNotNull(auditResult.regionFiles[regionStorageDirectory]) > 0) {
                "Official fixture generated no ${regionStorageDirectory.directoryName} MCA"
            }
            check(checkNotNull(auditResult.chunks[regionStorageDirectory]) > 0) {
                "Official fixture generated no readable ${regionStorageDirectory.directoryName} chunk: ${
                    auditResult.regionDiagnostics.getValue(
                        regionStorageDirectory
                    )
                }"
            }
        }
        check(auditResult.playerKeys.isNotEmpty()) {
            "Official fixture generated no player NBT"
        }
        check(auditResult.savedDataIds.isNotEmpty()) {
            "Official fixture generated no dimension saved-data"
        }
        check(auditResult.statisticFiles.isNotEmpty()) {
            "Official fixture generated no player statistics JSON"
        }
        check(auditResult.advancementFiles.isNotEmpty()) {
            "Official fixture generated no player advancements JSON"
        }
    }

    private fun regionPositions(directory: Path): List<RegionPosition> =
        systemFileSystem.list(directory)
            .mapNotNull { path ->
                REGION_FILE_NAME.matchEntire(path.name)?.let { matchResult ->
                    RegionPosition(
                        matchResult.groupValues[1].toInt(),
                        matchResult.groupValues[2].toInt(),
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

    private fun savedDataIds(directory: Path): List<SavedDataId> {
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
                SavedDataId(resourcePath, namespace)
            }
            .sortedBy(SavedDataId::toString)
            .toList()
    }

    private fun <T> rewriteTypedSavedData(
        savedDataStore: SavedDataStore,
        savedDataId: SavedDataId,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) {
        val value = checkNotNull(savedDataStore.read(savedDataId, serializer)) {
            "Official fixture generated no $savedDataId saved data"
        }
        savedDataStore.write(savedDataId, value, serializer)
        check(savedDataStore.read(savedDataId, serializer) == value) {
            "Typed $savedDataId saved data did not survive direct GZIP rewrite"
        }
    }

    private fun requireStructuredPlayerRewrite(
        worldDirectory: Path,
        structuredPlayerRewrite: StructuredPlayerRewrite,
        minimumLeaveGame: Int,
    ) {
        val minecraftWorldPaths = MinecraftWorldPaths(worldDirectory)
        val utf8JsonFileStore = Utf8JsonFileStore(systemFileSystem)
        val playerStatistics = utf8JsonFileStore.readJson(
            minecraftWorldPaths.statistics(structuredPlayerRewrite.playerKey),
            PlayerStatistics.serializer(),
        )
        val leaveGame = playerStatistics.stats[CUSTOM_STATISTICS]?.get(LEAVE_GAME_STATISTIC)
        check(leaveGame != null && leaveGame >= minimumLeaveGame) {
            "Official server did not load and save the rewritten leave_game statistic: $leaveGame"
        }
        val playerAdvancements = utf8JsonFileStore.readJson(
            minecraftWorldPaths.advancements(structuredPlayerRewrite.playerKey),
            PlayerAdvancements.serializer(),
        )
        check(playerAdvancements.advancements[ROOT_ADVANCEMENT]?.done == true) {
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
        val fileHandle = systemFileSystem.openLiveReadOnly(path)
        var failure: Throwable? = null
        try {
            val result = ByteArray(byteCount)
            var total = 0
            while (total < result.size) {
                val read = fileHandle.read(
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
            closeAllPreserving(failure, fileHandle::close)
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

    private fun strongChunkCodec(nbtDocument: NbtDocument): ChunkNbtCodec<BlockStateDescriptor, String> {
        val minSectionY = (nbtDocument.root["yPos"] as? NbtInt)?.value
            ?: error("Official terrain Chunk has no integer yPos")
        val sections = nbtDocument.root["sections"] as? NbtList
            ?: error("Official terrain Chunk has no Section list")
        val maxSemanticSectionY = sections.value.mapNotNull { nbtTag ->
            val section = nbtTag as? NbtCompound ?: return@mapNotNull null
            if (section["block_states"] == null || section["biomes"] == null) return@mapNotNull null
            (section["Y"] as? NbtByte)?.value?.toInt()
        }.maxOrNull() ?: minSectionY
        check(maxSemanticSectionY >= minSectionY) {
            "Official terrain Chunk has a semantic Section below yPos"
        }
        return ChunkNbtCodec(
            ChunkNbtContext(
                chunkLayout = ChunkLayout(
                    minSectionY = minSectionY,
                    sectionCount = maxSemanticSectionY - minSectionY + 1,
                ),
                chunkDataRegistries = ChunkDataRegistries(
                    blockStates = DescriptorBlockStateRegistry(),
                    biomes = NamedBiomeRegistry(),
                ),
            ),
        )
    }

    private suspend fun officialFailure(
        officialMinecraftServer: OfficialMinecraftServer,
        headlessMinecraftClient: HeadlessMinecraftClient?,
        failure: Throwable,
    ): AssertionError {
        val clientLog = if (headlessMinecraftClient == null) {
            "<not launched>"
        } else {
            diagnosticLog(headlessMinecraftClient, failure, "official client")
        }
        val serverLog = diagnosticLog(officialMinecraftServer, failure, "official server")
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
        minecraftTestResource: MinecraftTestResource,
        failure: Throwable,
        label: String,
    ): String = try {
        MinecraftTestSupport.logText(minecraftTestResource)
    } catch (logFailure: CancellationException) {
        throw logFailure
    } catch (logFailure: Throwable) {
        failure.addSuppressed(logFailure)
        "<$label log unavailable>"
    }

    private data class TerrainMutation(
        val chunkPosition: ChunkPosition,
        val originalDocument: NbtDocument,
    )

    private data class CompressionProbe(
        val name: String,
        val compression: Compression,
        val chunkPosition: ChunkPosition,
        val blockX: Int,
    )

    private data class AuditResult(
        val regionFiles: Map<RegionStorageDirectory, Int>,
        val chunks: Map<RegionStorageDirectory, Int>,
        val regionDiagnostics: Map<RegionStorageDirectory, List<String>>,
        val firstChunks: Map<RegionStorageDirectory, ChunkPosition>,
        val playerKeys: List<String>,
        val savedDataIds: List<SavedDataId>,
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
        const val MAXIMUM_CONNECTION_ATTEMPTS = 3
        val CONNECTION_ATTEMPT_TIMEOUT = 20.seconds
        val TERRAIN_MUTATION_POSITION = ChunkPosition(4, 0)
        val COMPRESSION_PROBES = listOf(
            CompressionProbe(
                name = "gzip",
                compression = Compression.GZIP,
                chunkPosition = ChunkPosition(0, 0),
                blockX = 0,
            ),
            CompressionProbe(
                name = "zlib",
                compression = Compression.ZLIB,
                chunkPosition = ChunkPosition(1, 0),
                blockX = 16,
            ),
            CompressionProbe(
                name = "none",
                compression = Compression.NONE,
                chunkPosition = ChunkPosition(2, 0),
                blockX = 32,
            ),
            CompressionProbe(
                name = "lz4",
                compression = Compression.LZ4,
                chunkPosition = ChunkPosition(3, 0),
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
