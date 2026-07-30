package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.offlineUuid
import com.hiczp.minecraft.protocol.auth.toUndashedString
import com.hiczp.minecraft.protocol.data.VanillaStaticData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/**
 * Black-box interoperability runner for the matching official client through
 * HeadlessMC's LWJGL stubs. No display server or GUI path is involved.
 */
internal object OfficialClientEndToEndRunner {
    private const val PLAYER_NAME = "KmpE2EClient"
    private const val INITIAL_KEEP_ALIVE_ID = 0x1020_3040_5060_7080L
    private const val PLAY_PROBE_KEEP_ALIVE_ID = 0x1122_3344_5566_7788L
    private const val CONFIGURATION_KEEP_ALIVE_ID =
        0x1234_5678_1020_3040L
    private const val POST_CONFIGURATION_KEEP_ALIVE_ID =
        0x2233_4455_6677_0102L
    private const val PLAY_PING_ID = 0x1020_3040
    private const val CONFIGURATION_PING_ID = 0x5060_7080
    private const val PRE_CONFIGURATION_PING_ID = 0x5566_7788
    private const val PRE_CONFIGURATION_KEEP_ALIVE_ID =
        0x3344_5566_7788_0102L
    private const val POST_CONFIGURATION_PING_ID = 0x1122_3344
    private const val RESPAWN_PING_ID = 0x2435_4657
    private const val RESPAWN_KEEP_ALIVE_ID =
        0x3141_5926_5358_9793L
    private val COOKIE_KEY = Identifier("minecraft-protocol:e2e")
    private val COOKIE_PAYLOAD = ByteString(
        "official-client-cookie".encodeToByteArray(),
    )

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 6) {
            "Expected <client-java> <minecraft-directory> <version> " +
                    "<work-directory> <report.json> <headlessmc.jar>"
        }
        val javaExecutable = Path.of(arguments[0]).toAbsolutePath().normalize()
        val minecraftDirectory =
            Path.of(arguments[1]).toAbsolutePath().normalize()
        val version = arguments[2]
        val workDirectory =
            Path.of(arguments[3]).toAbsolutePath().normalize()
        val report = Path.of(arguments[4]).toAbsolutePath().normalize()
        val headlessLauncher = Path.of(arguments[5])
            .toAbsolutePath()
            .normalize()

        require(Files.isRegularFile(javaExecutable)) {
            "Minecraft analysis Java does not exist: $javaExecutable"
        }
        require(Files.isDirectory(minecraftDirectory)) {
            "Prepared Minecraft client directory does not exist: " +
                    minecraftDirectory
        }
        require(Files.isRegularFile(headlessLauncher)) {
            "HeadlessMC launcher does not exist: $headlessLauncher"
        }
        Files.createDirectories(workDirectory)
        Files.createDirectories(report.parent)

        val installation = ClientInstallation.load(
            minecraftDirectory = minecraftDirectory,
            version = version,
        )
        require(
            installation.javaMajorVersion == javaMajorVersion(javaExecutable),
        ) {
            "Minecraft $version requires Java ${installation.javaMajorVersion}"
        }
        val runDirectory = workDirectory.resolve(
            "run-${System.currentTimeMillis()}",
        )
        val gameDirectory = runDirectory.resolve("game")
        Files.createDirectories(gameDirectory)
        writeClientOptions(gameDirectory)

        val clientLog = StringBuilder()
        var process: Process? = null
        var logThread: Thread? = null
        try {
            val outcome = runBlocking {
                SelectorManager(Dispatchers.IO).use { selector ->
                    MinecraftServer.bind(
                        selectorManager = selector,
                        host = "127.0.0.1",
                        port = 0,
                        configuration = MinecraftServerConfiguration(
                            compressionThreshold = 64,
                            viewDistance = 2,
                            simulationDistance = 5,
                            statusDescription =
                                "minecraft-protocol official client E2E",
                        ),
                    ).use { server ->
                        val launched = launchHeadlessClient(
                            javaExecutable = javaExecutable,
                            minecraftDirectory = minecraftDirectory,
                            installation = installation,
                            gameDirectory = gameDirectory,
                            launcher = headlessLauncher,
                            port = server.port,
                        )
                        process = launched
                        logThread = captureLog(
                            process = launched,
                            clientLog = clientLog,
                            output = runDirectory.resolve("client.log"),
                        )
                        awaitPlayRoundTrip(server, launched)
                    }
                }
            }
            writeReport(
                output = report,
                installation = installation,
                outcome = outcome,
            )
            println(
                "Official Minecraft ${installation.version} client reached " +
                        "Play twice, accepted initial, Respawn, and " +
                        "post-Configuration world projections, and " +
                        "completed Play/Configuration packet round trips " +
                        "without a display server",
            )
        } catch (failure: Throwable) {
            val log = synchronized(clientLog) { clientLog.toString() }
            throw AssertionError(
                "Official client -> production initial-world E2E failed.\n" +
                        "--- official client log ---\n$log",
                failure,
            )
        } finally {
            process?.let(::stopProcess)
            logThread?.join(Duration.ofSeconds(5))
        }
    }

    private suspend fun awaitPlayRoundTrip(
        server: MinecraftServer,
        process: Process,
    ): EndToEndOutcome {
        val processWatcher = Thread.ofVirtual()
            .name("official-client-e2e-process")
            .start {
                try {
                    process.waitFor()
                    server.close()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        try {
            return withTimeout(Duration.ofMinutes(2).toMillis()) {
                var statusConnections = 0
                while (true) {
                    check(process.isAlive) {
                        "Official client exited with ${process.exitValue()}"
                    }
                    val connection = try {
                        server.accept()
                    } catch (failure: Throwable) {
                        if (!process.isAlive) {
                            error(
                                "Official client exited with " +
                                        process.exitValue(),
                            )
                        }
                        throw failure
                    }
                    try {
                        when (val negotiation = connection.negotiate()) {
                            MinecraftServerNegotiationResult.StatusCompleted -> {
                                statusConnections++
                                connection.close()
                            }

                            is MinecraftServerNegotiationResult.PlayReady -> {
                                val pig = MinecraftEntitySnapshot(
                                    entityId = 2,
                                    uuid = Uuid(0, 2),
                                    type = Identifier("pig"),
                                    position = Vector3d(3.5, 65.0, 3.5),
                                )
                                val arrow = MinecraftEntitySnapshot(
                                    entityId = 3,
                                    uuid = Uuid(0, 3),
                                    type = Identifier("arrow"),
                                    position = Vector3d(2.5, 66.0, 2.5),
                                    velocity = Vector3d(0.05, 0.0, 0.0),
                                )
                                val minecart = MinecraftEntitySnapshot(
                                    entityId = 4,
                                    uuid = Uuid(0, 4),
                                    type = Identifier("minecart"),
                                    position = Vector3d(4.5, 65.0, 4.5),
                                )
                                val horse = MinecraftEntitySnapshot(
                                    entityId = 5,
                                    uuid = Uuid(0, 5),
                                    type = Identifier("horse"),
                                    position = Vector3d(5.5, 65.0, 5.5),
                                )
                                val world = MinecraftInitialWorld.flatVanilla(
                                    configuration = server.configuration,
                                    entities =
                                        listOf(pig, arrow, minecart, horse),
                                )
                                val synchronization =
                                    connection.synchronizeInitialWorld(world)
                                connection.session.send(
                                    PlayClientboundKeepAlivePacket(
                                        INITIAL_KEEP_ALIVE_ID,
                                    ),
                                )
                                val observed = mutableListOf<String>()
                                var teleportAcknowledged = false
                                var chunkBatchAcknowledged = false
                                var keepAliveAcknowledged = false
                                var clientTickObserved = false
                                var initialPacketBudget =
                                    server.configuration.maximumPacketsPerPhase
                                while (
                                    initialPacketBudget-- > 0 &&
                                    !(
                                            teleportAcknowledged &&
                                                    chunkBatchAcknowledged &&
                                                    keepAliveAcknowledged &&
                                                    clientTickObserved
                                            )
                                ) {
                                    val packet = connection.session.receive()
                                    observed +=
                                        packet::class.simpleName ?: "<anonymous>"
                                    when (packet) {
                                        is ConfirmTeleportationPacket ->
                                            teleportAcknowledged =
                                                packet.teleportId ==
                                                        synchronization.teleportId

                                        is ChunkBatchReceivedPacket ->
                                            chunkBatchAcknowledged = true

                                        is PlayServerboundKeepAlivePacket ->
                                            keepAliveAcknowledged =
                                                packet.id ==
                                                        INITIAL_KEEP_ALIVE_ID

                                        is ClientTickEndPacket ->
                                            clientTickObserved = true

                                        else -> Unit
                                    }
                                }
                                check(
                                    teleportAcknowledged &&
                                        chunkBatchAcknowledged &&
                                        keepAliveAcknowledged &&
                                            clientTickObserved,
                                ) {
                                    "Client did not complete initial-world " +
                                            "acknowledgements; teleport=" +
                                            "$teleportAcknowledged, chunkBatch=" +
                                            "$chunkBatchAcknowledged, keepAlive=" +
                                            "$keepAliveAcknowledged, clientTick=" +
                                            "$clientTickObserved; observed " +
                                            observed.joinToString()
                                }

                                val playProbes = exercisePlayPackets(
                                    connection = connection,
                                    playerEntityId = negotiation.login.playerId,
                                    entity = pig,
                                    projectile = arrow,
                                    vehicle = minecart,
                                    horse = horse,
                                    nextTeleportId =
                                        synchronization.teleportId + 1,
                                    observed = observed,
                                )
                                val respawn = exerciseRespawn(
                                    connection = connection,
                                    world = world.copy(
                                        teleportId = world.teleportId + 2,
                                    ),
                                    observed = observed,
                                )
                                val reconfiguration =
                                    exerciseReconfiguration(
                                        connection = connection,
                                        world = world,
                                        observedPlayPackets = observed,
                                    )
                                delay(CONNECTION_STABILITY_DELAY_MILLIS)
                                check(process.isAlive) {
                                    "Official client exited after protocol " +
                                            "round-trip probes"
                                }
                                connection.close()
                                return@withTimeout EndToEndOutcome(
                                    statusConnections = statusConnections,
                                    playerName = negotiation.profile.name,
                                    acceptedKnownPacks =
                                        negotiation.acceptedKnownPacks.size,
                                    synchronizedChunks =
                                        synchronization.chunkCount,
                                    synchronizedEntities =
                                        synchronization.entityCount,
                                    entityType = pig.type.toString(),
                                    entityTypeId = pig.typeId,
                                    teleportAcknowledged = true,
                                    chunkBatchAcknowledged = true,
                                    clientTickObserved = true,
                                    clientRemainedConnected = true,
                                    playProbePacketTransmissions =
                                        playProbes.probePacketTransmissions,
                                    playBarrierPacketTransmissions =
                                        playProbes.barrierPacketTransmissions,
                                    playClientboundPacketTypes =
                                        playProbes.clientboundPacketTypes,
                                    playCookieRoundTrip =
                                        playProbes.cookieRoundTrip,
                                    playPingRoundTrip =
                                        playProbes.pingRoundTrip,
                                    secondTeleportAcknowledged =
                                        playProbes.teleportAcknowledged,
                                    respawnSynchronizedChunks =
                                        respawn.synchronizedChunks,
                                    respawnSynchronizedEntities =
                                        respawn.synchronizedEntities,
                                    respawnTeleportAcknowledged =
                                        respawn.teleportAcknowledged,
                                    respawnChunkBatchAcknowledged =
                                        respawn.chunkBatchAcknowledged,
                                    respawnPlayerLoaded =
                                        respawn.playerLoaded,
                                    respawnPlayRoundTrip =
                                        respawn.playRoundTrip,
                                    reconfigurationAcknowledged =
                                        reconfiguration.acknowledged,
                                    playerLoadedBeforeReconfiguration =
                                        reconfiguration.playerLoaded,
                                    reconfigurationClientInformationObserved =
                                        reconfiguration.clientInformation,
                                    reconfigurationKnownPacks =
                                        reconfiguration.knownPacks,
                                    configurationCookieRoundTrip =
                                        reconfiguration.cookieRoundTrip,
                                    configurationKeepAliveRoundTrip =
                                        reconfiguration.keepAliveRoundTrip,
                                    configurationPingRoundTrip =
                                        reconfiguration.pingRoundTrip,
                                    reconfigurationCompleted =
                                        reconfiguration.completed,
                                    reconfigurationSynchronizedChunks =
                                        reconfiguration.synchronizedChunks,
                                    reconfigurationSynchronizedEntities =
                                        reconfiguration.synchronizedEntities,
                                    reconfigurationTeleportAcknowledged =
                                        reconfiguration.teleportAcknowledged,
                                    reconfigurationChunkBatchAcknowledged =
                                        reconfiguration.chunkBatchAcknowledged,
                                    reconfigurationPlayerLoaded =
                                        reconfiguration.playerLoadedAfterward,
                                    postConfigurationPlayRoundTrip =
                                        reconfiguration.postPlayRoundTrip,
                                    observedPlayPackets = observed,
                                    observedConfigurationPackets =
                                        reconfiguration.observedPackets,
                                )
                            }
                        }
                    } catch (failure: Throwable) {
                        connection.close()
                        throw failure
                    }
                }
                error("Unreachable")
            }
        } finally {
            processWatcher.interrupt()
        }
    }

    private suspend fun exercisePlayPackets(
        connection: MinecraftServerConnection,
        playerEntityId: Int,
        entity: MinecraftEntitySnapshot,
        projectile: MinecraftEntitySnapshot,
        vehicle: MinecraftEntitySnapshot,
        horse: MinecraftEntitySnapshot,
        nextTeleportId: Int,
        observed: MutableList<String>,
    ): PlayProbeOutcome {
        val bossBarId = Uuid(0, 3)
        val playerListProfileId = Uuid(0, 5)
        val waypointId = WaypointIdentifier.Named("headless-e2e")
        val waypointIcon = WaypointIcon(
            style = Identifier("default"),
            color = 0x33AAFF,
        )
        val closedRecipeBook =
            RecipeBookTypeSettings(open = false, filtering = false)
        val sound = SoundEventHolder.Direct(
            Identifier("entity.experience_orb.pickup"),
        )
        val simpleParticle =
            ParticleOptions.Simple(ParticleType.FLAME)
        val blockTypeId = VanillaStaticData
            .requireRegistry(Identifier("block"))
            .requireProtocolId(Identifier("grass_block"))
        val blockEntityTypeId = VanillaStaticData
            .requireRegistry(Identifier("block_entity_type"))
            .requireProtocolId(Identifier("furnace"))
        val genericContainerTypeId = VanillaStaticData
            .requireRegistry(Identifier("menu"))
            .requireProtocolId(Identifier("generic_9x1"))
        val merchantContainerTypeId = VanillaStaticData
            .requireRegistry(Identifier("menu"))
            .requireProtocolId(Identifier("merchant"))
        val furnaceContainerTypeId = VanillaStaticData
            .requireRegistry(Identifier("menu"))
            .requireProtocolId(Identifier("furnace"))
        val emptyLight = LightUpdateData(
            skyYMask = BitSet(longArrayOf()),
            blockYMask = BitSet(longArrayOf()),
            emptySkyYMask = BitSet(longArrayOf()),
            emptyBlockYMask = BitSet(longArrayOf()),
            skyUpdates = emptyList(),
            blockUpdates = emptyList(),
        )
        val packets = listOf(
            SetExperiencePacket(
                experienceBar = 0.5f,
                level = 5,
                totalExperience = 10,
            ),
            SetHealthPacket(
                health = 20.0f,
                food = 20,
                saturation = 5.0f,
            ),
            ClientboundSetHeldItemPacket(slot = 1),
            UpdateTimePacket(
                gameTime = 6_000,
                clocks = mapOf(
                    0 to ClockNetworkState(
                        totalTicks = 6_000,
                        partialTick = 0.25f,
                        rate = 1.0f,
                    ),
                ),
            ),
            SetTitleAnimationTimesPacket(
                fadeInTicks = 1,
                stayTicks = 5,
                fadeOutTicks = 1,
            ),
            SetTitleTextPacket(
                TextComponent.literal("minecraft-protocol E2E"),
            ),
            SetSubtitleTextPacket(
                TextComponent.literal(
                    "official ${MinecraftProtocol.MINECRAFT_VERSION} client",
                ),
            ),
            SystemChatMessagePacket(
                content = TextComponent.literal(
                    "Protocol clientbound packets accepted",
                ),
                overlay = false,
            ),
            SetTabListHeaderAndFooterPacket(
                header = TextComponent.literal("minecraft-protocol"),
                footer = TextComponent.literal("headless E2E"),
            ),
            GameRuleValuesPacket(emptyMap()),
            SetEntityVelocityPacket(
                entityId = entity.entityId,
                velocity = Vector3d(0.01, 0.0, -0.01),
            ),
            UpdateEntityPositionPacket(
                entityId = entity.entityId,
                deltaX = 64,
                deltaY = 0,
                deltaZ = -64,
                onGround = true,
            ),
            TeleportEntityPacket(
                entityId = entity.entityId,
                values = PositionMoveRotation(
                    position = Vector3d(3.75, 65.0, 3.25),
                    deltaMovement = Vector3d(0.0, 0.0, 0.0),
                    yaw = 30.0f,
                    pitch = 0.0f,
                ),
                onGround = true,
            ),
            PlayCustomReportDetailsPacket(
                listOf(
                    ReportDetail(
                        title = "E2E",
                        description = "official headless client",
                    ),
                ),
            ),
            PlayServerLinksPacket(emptyList()),
            ClearDialogPacket,
            EntityAnimationPacket(
                entityId = entity.entityId,
                animationId = 0,
            ),
            AwardStatisticsPacket(emptyList()),
            AcknowledgeBlockChangePacket(sequenceId = 0),
            SetBlockDestroyStagePacket(
                entityId = entity.entityId,
                location = BlockPosition(0, 64, 0),
                destroyStage = 0,
            ),
            SetBlockDestroyStagePacket(
                entityId = entity.entityId,
                location = BlockPosition(0, 64, 0),
                destroyStage = 255,
            ),
            BlockUpdatePacket(
                location = BlockPosition(0, 65, 0),
                blockStateId = 0,
            ),
            BlockActionPacket(
                location = BlockPosition(0, 64, 0),
                actionId = 0,
                actionParameter = 0,
                blockTypeId = blockTypeId,
            ),
            BlockEntityDataPacket(
                location = BlockPosition(0, 65, 0),
                typeId = blockEntityTypeId,
                data = NbtCompound(emptyMap()),
            ),
            BossBarPacket(
                uuid = bossBarId,
                action = BossBarAction.Add(
                    title = TextComponent.literal("Headless E2E"),
                    health = 1.0f,
                    color = BossBarColor.GREEN,
                    division = BossBarDivision.TEN_NOTCHES,
                    flags = 0,
                ),
            ),
            BossBarPacket(
                uuid = bossBarId,
                action = BossBarAction.UpdateHealth(0.5f),
            ),
            BossBarPacket(
                uuid = bossBarId,
                action = BossBarAction.UpdateTitle(
                    TextComponent.literal("Protocol probe"),
                ),
            ),
            BossBarPacket(
                uuid = bossBarId,
                action = BossBarAction.UpdateStyle(
                    color = BossBarColor.BLUE,
                    division = BossBarDivision.SIX_NOTCHES,
                ),
            ),
            BossBarPacket(
                uuid = bossBarId,
                action = BossBarAction.UpdateFlags(0),
            ),
            BossBarPacket(
                uuid = bossBarId,
                action = BossBarAction.Remove,
            ),
            ChunkBiomesPacket(emptyList()),
            ClearTitlesPacket(reset = true),
            ClientboundCloseContainerPacket(containerId = 0),
            SetCooldownPacket(
                cooldownGroup = Identifier("minecraft-protocol:e2e"),
                cooldownTicks = 0,
            ),
            ChatSuggestionsPacket(
                action = ChatSuggestionsAction.SET,
                entries = listOf("minecraft-protocol-e2e"),
            ),
            CommandSuggestionsResponsePacket(
                id = 0,
                start = 0,
                length = 0,
                matches = emptyList(),
            ),
            CommandsPacket(
                nodes = listOf(
                    CommandNode.Root(children = listOf(1)),
                    CommandNode.Literal(
                        name = "minecraft-protocol-e2e",
                        children = emptyList(),
                        executable = true,
                    ),
                ),
                rootIndex = 0,
            ),
            PlayClientboundPluginMessagePacket(
                CustomPayload.Brand("minecraft-protocol"),
            ),
            DamageEventPacket(
                entityId = entity.entityId,
                sourceTypeId = 0,
                sourceCauseEntityId = null,
                sourceDirectEntityId = null,
                sourcePosition = null,
            ),
            DebugBlockValuePacket(
                location = BlockPosition(0, 64, 0),
                update = DebugSubscriptionUpdate(
                    type = DebugSubscriptionType.BEE_HIVE,
                    data = null,
                ),
            ),
            DebugChunkValuePacket(
                chunkX = 0,
                chunkZ = 0,
                update = DebugSubscriptionUpdate(
                    type = DebugSubscriptionType.VILLAGE_SECTION,
                    data = null,
                ),
            ),
            DebugEntityValuePacket(
                entityId = entity.entityId,
                update = DebugSubscriptionUpdate(
                    type = DebugSubscriptionType.BEE,
                    data = null,
                ),
            ),
            DebugEventPacket(
                DebugSubscriptionEvent(
                    DebugSubscriptionData.Raid(emptyList()),
                ),
            ),
            DebugSamplePacket(
                sample = listOf(1),
                type = DebugSampleType.TICK_TIME,
            ),
            DisguisedChatPacket(
                message = TextComponent.literal(
                    "Official client accepted disguised chat",
                ),
                chatType = BoundChatType(
                    chatType = ChatTypeHolder.Reference(0),
                    name = TextComponent.literal("Server"),
                    targetName = null,
                ),
            ),
            EntityEventPacket(
                entityId = entity.entityId,
                eventId = 2,
            ),
            ExplosionPacket(
                center = Vector3d(0.5, 65.0, 0.5),
                radius = 0.0f,
                blockCount = 0,
                playerKnockback = null,
                explosionParticle =
                    ParticleOptions.Simple(ParticleType.EXPLOSION),
                explosionSound = SoundEventHolder.Direct(
                    Identifier("entity.generic.explode"),
                ),
                blockParticles = emptyList(),
            ),
            GameTestHighlightPositionPacket(
                absolutePosition = BlockPosition(0, 65, 0),
                relativePosition = BlockPosition(0, 0, 0),
            ),
            HurtAnimationPacket(
                entityId = entity.entityId,
                yaw = 15.0f,
            ),
            InitializeWorldBorderPacket(
                centerX = 0.0,
                centerZ = 0.0,
                oldDiameter = 128.0,
                newDiameter = 128.0,
                speedMilliseconds = 0,
                portalTeleportBoundary = 29_999_984,
                warningBlocks = 5,
                warningTimeSeconds = 15,
            ),
            LightUpdatePacket(
                chunkX = 0,
                chunkZ = 0,
                data = emptyLight,
            ),
            LowDiskSpaceWarningPacket,
            WorldEventPacket(
                eventId = 1000,
                location = BlockPosition(0, 65, 0),
                data = 0,
                disableRelativeVolume = false,
            ),
            ParticlePacket(
                overrideLimiter = false,
                alwaysShow = true,
                x = 0.5,
                y = 66.0,
                z = 0.5,
                offsetX = 0.0f,
                offsetY = 0.0f,
                offsetZ = 0.0f,
                maxSpeed = 0.0f,
                count = 1,
                particle = simpleParticle,
            ),
            UpdateEntityPositionAndRotationPacket(
                entityId = entity.entityId,
                deltaX = 16,
                deltaY = 0,
                deltaZ = 16,
                yaw = Angle.fromDegrees(45.0f),
                pitch = Angle.fromDegrees(5.0f),
                onGround = true,
            ),
            UpdateEntityRotationPacket(
                entityId = entity.entityId,
                yaw = Angle.fromDegrees(60.0f),
                pitch = Angle.fromDegrees(0.0f),
                onGround = true,
            ),
            ClientboundMoveVehiclePacket(
                position = Vector3d(0.5, 65.0, 0.5),
                yaw = 0.0f,
                pitch = 0.0f,
            ),
            MoveMinecartAlongTrackPacket(
                entityId = vehicle.entityId,
                steps = listOf(
                    MinecartStep(
                        position = Vector3d(4.75, 65.0, 4.5),
                        velocity = Vector3d(0.05, 0.0, 0.0),
                        yaw = Angle.fromDegrees(90.0f),
                        pitch = Angle.fromDegrees(0.0f),
                        weight = 1.0f,
                    ),
                ),
            ),
            SynchronizeVehiclePositionPacket(
                entityId = vehicle.entityId,
                change = PositionMoveRotation(
                    position = Vector3d(4.75, 65.0, 4.5),
                    deltaMovement = Vector3d(0.05, 0.0, 0.0),
                    yaw = 90.0f,
                    pitch = 0.0f,
                ),
                relatives = RelativeMovements(emptySet()),
                onGround = true,
            ),
            PongResponsePacket(timestamp = 1),
            UnloadChunkPacket(chunkX = 2, chunkZ = 2),
            EnterCombatPacket,
            EndCombatPacket(durationTicks = 1),
            PlayerInfoUpdatePacket(
                PlayerInfoUpdatePayload(
                    actions = PlayerInfoAction.entries.toSet(),
                    entries = listOf(
                        PlayerInfoEntry(
                            profileId = playerListProfileId,
                            profile = PlayerListProfile(
                                name = "E2EProbe",
                                properties = emptyList(),
                            ),
                            chatSession = null,
                            gameMode =
                                com.hiczp.minecraft.protocol.model.type
                                    .GameMode.CREATIVE,
                            listed = true,
                            latency = 1,
                            displayName =
                                TextComponent.literal("Headless E2E"),
                            listOrder = 1,
                            showHat = true,
                        ),
                    ),
                ),
            ),
            PlayerChatMessagePacket(
                globalIndex = 0,
                sender = playerListProfileId,
                index = 0,
                signature = null,
                body = PackedSignedMessageBody(
                    content = "Official client accepted player chat",
                    timestampEpochMillis = 1,
                    salt = 2,
                    lastSeen = emptyList(),
                ),
                unsignedContent = TextComponent.literal(
                    "Official client accepted player chat",
                ),
                filterMask = FilterMask.PassThrough,
                chatType = BoundChatType(
                    chatType = ChatTypeHolder.Reference(0),
                    name = TextComponent.literal("E2EProbe"),
                    targetName = null,
                ),
            ),
            PlayerInfoRemovePacket(listOf(playerListProfileId)),
            LookAtPacket(
                fromAnchor = EntityAnchor.EYES,
                target = LookTarget.Entity(
                    fallbackPosition = entity.position,
                    entityId = entity.entityId,
                    anchor = EntityAnchor.EYES,
                ),
            ),
            PlayerRotationPacket(
                yaw = 0.0f,
                relativeYaw = false,
                pitch = 0.0f,
                relativePitch = false,
            ),
            RecipeBookRemovePacket(emptyList()),
            RecipeBookSettingsPacket(
                RecipeBookSettings(
                    crafting = closedRecipeBook,
                    furnace = closedRecipeBook,
                    blastFurnace = closedRecipeBook,
                    smoker = closedRecipeBook,
                ),
            ),
            RemoveEntitiesPacket(emptyList()),
            RemoveEntityEffectPacket(
                entityId = entity.entityId,
                effectTypeId = 0,
            ),
            PlayRemoveResourcePackPacket(id = null),
            SetHeadRotationPacket(
                entityId = entity.entityId,
                headYaw = Angle.fromDegrees(75.0f),
            ),
            UpdateSectionBlocksPacket(
                sectionPosition = SectionPosition(0, 4, 0),
                blocks = emptyList(),
            ),
            SelectAdvancementsTabPacket(tab = null),
            ServerDataPacket(
                motd = TextComponent.literal("minecraft-protocol E2E"),
                iconPng = null,
            ),
            SetActionBarTextPacket(
                TextComponent.literal("Headless client packet probes"),
            ),
            SetBorderCenterPacket(x = 0.0, z = 0.0),
            SetBorderLerpSizePacket(
                oldDiameter = 128.0,
                newDiameter = 96.0,
                speedMilliseconds = 1,
            ),
            SetBorderSizePacket(diameter = 128.0),
            SetBorderWarningDelayPacket(warningTimeSeconds = 15),
            SetBorderWarningDistancePacket(warningBlocks = 5),
            SetCameraPacket(cameraEntityId = playerEntityId),
            SetEntityMetadataPacket(
                entityId = entity.entityId,
                metadata = EntityMetadata(emptyList()),
            ),
            LinkEntitiesPacket(
                attachedEntityId = entity.entityId,
                holdingEntityId = 0,
            ),
            SetPassengersPacket(
                vehicleEntityId = vehicle.entityId,
                passengerEntityIds = listOf(entity.entityId),
            ),
            SetPassengersPacket(
                vehicleEntityId = vehicle.entityId,
                passengerEntityIds = emptyList(),
            ),
            OpenBookPacket(InteractionHand.MAIN_HAND),
            OpenScreenPacket(
                containerId = 1,
                menuTypeId = genericContainerTypeId,
                title = TextComponent.literal("Headless E2E"),
            ),
            SetContainerContentPacket(
                containerId = 1,
                stateId = 0,
                items = List(45) { ItemStack.Empty },
                carriedItem = ItemStack.Empty,
            ),
            SetContainerSlotPacket(
                containerId = 1,
                stateId = 1,
                slot = 0,
                item = ItemStack.Empty,
            ),
            ClientboundCloseContainerPacket(containerId = 1),
            OpenScreenPacket(
                containerId = 2,
                menuTypeId = merchantContainerTypeId,
                title = TextComponent.literal("Merchant E2E"),
            ),
            MerchantOffersPacket(
                containerId = 2,
                offers = emptyList(),
                villagerLevel = 1,
                villagerExperience = 0,
                showProgress = false,
                canRestock = false,
            ),
            ClientboundCloseContainerPacket(containerId = 2),
            OpenHorseScreenPacket(
                containerId = 3,
                inventoryColumns = 2,
                entityId = horse.entityId,
            ),
            ClientboundCloseContainerPacket(containerId = 3),
            OpenScreenPacket(
                containerId = 4,
                menuTypeId = furnaceContainerTypeId,
                title = TextComponent.literal("Furnace E2E"),
            ),
            SetContainerPropertyPacket(
                containerId = 4,
                property = 0,
                value = 0,
            ),
            ClientboundCloseContainerPacket(containerId = 4),
            SetContainerSlotPacket(
                containerId = -2,
                stateId = 0,
                slot = 0,
                item = ItemStack.Empty,
            ),
            SetCursorItemPacket(ItemStack.Empty),
            SetPlayerInventorySlotPacket(
                slot = 0,
                contents = ItemStack.Empty,
            ),
            SetEquipmentPacket(
                entityId = entity.entityId,
                updates = EquipmentUpdates(
                    listOf(
                        EquipmentUpdate(
                            slot = EquipmentSlot.MAINHAND,
                            item = ItemStack.Empty,
                        ),
                    ),
                ),
            ),
            EntitySoundEffectPacket(
                sound = sound,
                source = SoundSource.NEUTRAL,
                entityId = entity.entityId,
                volume = 0.1f,
                pitch = 1.0f,
                seed = 1,
            ),
            SoundEffectPacket.fromPosition(
                sound = sound,
                source = SoundSource.MASTER,
                x = 0.5,
                y = 65.0,
                z = 0.5,
                volume = 0.1f,
                pitch = 1.0f,
                seed = 2,
            ),
            StopSoundPacket(StopSound(source = null, sound = null)),
            TagQueryResponsePacket(transactionId = 0, data = null),
            TestInstanceBlockStatusPacket(
                status = TextComponent.literal("E2E"),
                size = null,
            ),
            SetTickingStatePacket(
                tickRate = 20.0f,
                frozen = false,
            ),
            StepTickPacket(tickSteps = 0),
            MapDataPacket(
                mapId = 0,
                scale = 0,
                locked = false,
                decorations = null,
                colorPatch = null,
            ),
            RecipeBookAddPacket(
                entries = emptyList(),
                replace = false,
            ),
            UpdateAdvancementsPacket(
                reset = false,
                added = emptyList(),
                removed = emptySet(),
                progress = emptyMap(),
                showAdvancements = false,
            ),
            UpdateRecipesPacket(
                itemSets = emptyMap(),
                stonecutterRecipes = emptyList(),
            ),
            UpdateAttributesPacket(
                entityId = entity.entityId,
                attributes = emptyList(),
            ),
            ProjectilePowerPacket(
                entityId = projectile.entityId,
                power = 1.0,
            ),
            EntityEffectPacket(
                entityId = entity.entityId,
                effectTypeId = 0,
                amplifier = 0,
                durationTicks = 20,
                flags = MobEffectFlags(0),
            ),
            RemoveEntityEffectPacket(
                entityId = entity.entityId,
                effectTypeId = 0,
            ),
            PlayUpdateTagsPacket(
                connection.protocol.configuration.protocolData.tags.registries
                    .associate { it.registry to it.tags },
            ),
            SetObjectivePacket(
                objectiveName = "headless-e2e",
                update = ObjectiveUpdate.Add(
                    displayName = TextComponent.literal("Headless E2E"),
                    renderType = ObjectiveRenderType.INTEGER,
                    numberFormat = null,
                ),
            ),
            SetObjectivePacket(
                objectiveName = "headless-e2e",
                update = ObjectiveUpdate.Change(
                    displayName = TextComponent.literal("Protocol probes"),
                    renderType = ObjectiveRenderType.INTEGER,
                    numberFormat = NumberFormat.Blank,
                ),
            ),
            DisplayObjectivePacket(
                slot = DisplaySlot.SIDEBAR,
                objectiveName = "headless-e2e",
            ),
            SetScorePacket(
                owner = "E2EProbe",
                objectiveName = "headless-e2e",
                score = 1,
                display = TextComponent.literal("Probe"),
                numberFormat = NumberFormat.Fixed(
                    TextComponent.literal("1"),
                ),
            ),
            ResetScorePacket(
                owner = "E2EProbe",
                objectiveName = "headless-e2e",
            ),
            DisplayObjectivePacket(
                slot = DisplaySlot.SIDEBAR,
                objectiveName = "",
            ),
            SetObjectivePacket(
                objectiveName = "headless-e2e",
                update = ObjectiveUpdate.Remove,
            ),
            SetPlayerTeamPacket(
                teamName = "headless-e2e",
                update = TeamUpdate.Add(
                    parameters = TeamParameters(
                        displayName = TextComponent.literal("Headless E2E"),
                        playerPrefix = TextComponent.literal("[E2E] "),
                        playerSuffix = TextComponent.literal(""),
                        nameTagVisibility = TeamVisibility.ALWAYS,
                        collisionRule = TeamCollisionRule.ALWAYS,
                        color = TeamColor.AQUA,
                        options = 0,
                    ),
                    players = listOf("E2EProbe"),
                ),
            ),
            SetPlayerTeamPacket(
                teamName = "headless-e2e",
                update = TeamUpdate.Change(
                    TeamParameters(
                        displayName = TextComponent.literal("Protocol probes"),
                        playerPrefix = TextComponent.literal(""),
                        playerSuffix = TextComponent.literal(" [E2E]"),
                        nameTagVisibility = TeamVisibility.ALWAYS,
                        collisionRule = TeamCollisionRule.NEVER,
                        color = TeamColor.BLUE,
                        options = 0,
                    ),
                ),
            ),
            SetPlayerTeamPacket(
                teamName = "headless-e2e",
                update = TeamUpdate.Join(listOf("SecondProbe")),
            ),
            SetPlayerTeamPacket(
                teamName = "headless-e2e",
                update = TeamUpdate.Leave(listOf("SecondProbe")),
            ),
            SetPlayerTeamPacket(
                teamName = "headless-e2e",
                update = TeamUpdate.Remove,
            ),
            WaypointPacket(
                operation = WaypointOperation.TRACK,
                waypoint = TrackedWaypoint.Position(
                    identifier = waypointId,
                    icon = waypointIcon,
                    x = 0,
                    y = 65,
                    z = 0,
                ),
            ),
            WaypointPacket(
                operation = WaypointOperation.UPDATE,
                waypoint = TrackedWaypoint.Azimuth(
                    identifier = waypointId,
                    icon = waypointIcon,
                    angle = 90.0f,
                ),
            ),
            WaypointPacket(
                operation = WaypointOperation.UNTRACK,
                waypoint = TrackedWaypoint.Empty(
                    identifier = waypointId,
                    icon = waypointIcon,
                ),
            ),
            RemoveEntitiesPacket(
                listOf(projectile.entityId, vehicle.entityId),
            ),
        )
        packets.forEachIndexed { index, packet ->
            connection.session.send(packet)
            awaitPlayBarrier(
                connection = connection,
                label = packet::class.simpleName ?: "clientbound packet $index",
                pingId = PLAY_PING_ID + index,
                keepAliveId = PLAY_PROBE_KEEP_ALIVE_ID + index,
                observed = observed,
            )
        }

        connection.session.send(
            PlayStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        connection.session.send(PlayCookieRequestPacket(COOKIE_KEY))
        var cookieRoundTrip = false
        awaitPlayBarrier(
            connection = connection,
            label = "Play cookie store/request",
            pingId = PLAY_PING_ID + packets.size,
            keepAliveId = PLAY_PROBE_KEEP_ALIVE_ID + packets.size,
            observed = observed,
            additionalComplete = { cookieRoundTrip },
            onPacket = { packet ->
                if (
                    packet is PlayCookieResponsePacket &&
                    packet.key == COOKIE_KEY
                ) {
                    check(packet.payload == COOKIE_PAYLOAD) {
                        "Official client returned the wrong Play cookie"
                    }
                    cookieRoundTrip = true
                }
            },
        )

        connection.session.send(
            SynchronizePlayerPositionPacket(
                teleportId = nextTeleportId,
                change = PositionMoveRotation(
                    position = Vector3d(1.5, 65.0, 1.5),
                    deltaMovement = Vector3d(0.0, 0.0, 0.0),
                    yaw = 15.0f,
                    pitch = 5.0f,
                ),
                relatives = RelativeMovements(emptySet()),
            ),
        )
        var teleportAcknowledged = false
        awaitPlayBarrier(
            connection = connection,
            label = "second player-position synchronization",
            pingId = PLAY_PING_ID + packets.size + 1,
            keepAliveId = PLAY_PROBE_KEEP_ALIVE_ID + packets.size + 1,
            observed = observed,
            additionalComplete = { teleportAcknowledged },
            onPacket = { packet ->
                if (
                    packet is ConfirmTeleportationPacket &&
                    packet.teleportId == nextTeleportId
                ) {
                    teleportAcknowledged = true
                }
            },
        )
        check(playerEntityId != entity.entityId) {
            "Play probe entity unexpectedly reused the player entity ID"
        }
        val probePacketTypes = buildList {
            addAll(
                packets.map { packet ->
                    checkNotNull(packet::class.simpleName) {
                        "A Play probe packet has no runtime class name"
                    }
                },
            )
            add(PlayStoreCookiePacket::class.simpleName!!)
            add(PlayCookieRequestPacket::class.simpleName!!)
            add(SynchronizePlayerPositionPacket::class.simpleName!!)
            add(ClientboundPingPacket::class.simpleName!!)
            add(PlayClientboundKeepAlivePacket::class.simpleName!!)
        }.distinct().sorted()
        return PlayProbeOutcome(
            probePacketTransmissions = packets.size + 3,
            barrierPacketTransmissions = packets.size * 2 + 4,
            clientboundPacketTypes = probePacketTypes,
            cookieRoundTrip = true,
            pingRoundTrip = true,
            teleportAcknowledged = true,
        )
    }

    private suspend fun exerciseRespawn(
        connection: MinecraftServerConnection,
        world: MinecraftInitialWorld,
        observed: MutableList<String>,
    ): RespawnOutcome {
        val login = checkNotNull(connection.protocol.negotiatedPlayLogin) {
            "Respawn requires the negotiated Play Login"
        }
        connection.session.send(
            RespawnPacket(
                spawnInfo = login.spawnInfo,
                dataToKeep = RespawnPacket.KEEP_ALL_DATA.toByte(),
            ),
        )
        val synchronization = connection.synchronizeInitialWorld(world)
        connection.session.send(
            PlayClientboundKeepAlivePacket(RESPAWN_KEEP_ALIVE_ID),
        )
        connection.session.send(ClientboundPingPacket(RESPAWN_PING_ID))

        var keepAlive = false
        var ping = false
        var tick = false
        var teleport = false
        var chunkBatch = false
        var playerLoaded = false
        var packetBudget =
            connection.protocol.configuration.maximumPacketsPerPhase
        while (
            packetBudget-- > 0 &&
            !(
                    keepAlive &&
                            ping &&
                            tick &&
                            teleport &&
                            chunkBatch &&
                            playerLoaded
                    )
        ) {
            val packet = receiveForStage(
                connection,
                "waiting for post-Respawn Play probes",
            )
            observed += packet::class.simpleName ?: "<anonymous>"
            when (packet) {
                is PlayServerboundKeepAlivePacket ->
                    if (packet.id == RESPAWN_KEEP_ALIVE_ID) keepAlive = true

                is PlayPongPacket ->
                    if (packet.id == RESPAWN_PING_ID) ping = true

                is ConfirmTeleportationPacket ->
                    if (packet.teleportId == synchronization.teleportId) {
                        teleport = true
                    }

                is ChunkBatchReceivedPacket -> chunkBatch = true
                PlayerLoadedPacket -> playerLoaded = true
                is ClientTickEndPacket -> tick = true
                else -> Unit
            }
        }
        check(
            keepAlive &&
                    ping &&
                    tick &&
                    teleport &&
                    chunkBatch &&
                    playerLoaded,
        ) {
            "Official client did not complete Respawn; keepAlive=$keepAlive, " +
                    "ping=$ping, tick=$tick, teleport=$teleport, " +
                    "chunkBatch=$chunkBatch, playerLoaded=$playerLoaded"
        }
        return RespawnOutcome(
            synchronizedChunks = synchronization.chunkCount,
            synchronizedEntities = synchronization.entityCount,
            teleportAcknowledged = true,
            chunkBatchAcknowledged = true,
            playerLoaded = true,
            playRoundTrip = true,
        )
    }

    private suspend fun awaitPlayBarrier(
        connection: MinecraftServerConnection,
        label: String,
        pingId: Int,
        keepAliveId: Long,
        observed: MutableList<String>,
        additionalComplete: () -> Boolean = { true },
        onPacket: (Packet) -> Unit = {},
    ) {
        connection.session.send(ClientboundPingPacket(pingId))
        connection.session.send(
            PlayClientboundKeepAlivePacket(keepAliveId),
        )
        var pingRoundTrip = false
        var keepAliveRoundTrip = false
        var tickObserved = false
        var packetBudget =
            connection.protocol.configuration.maximumPacketsPerPhase
        try {
            while (
                packetBudget-- > 0 &&
                !(
                        pingRoundTrip &&
                                keepAliveRoundTrip &&
                                tickObserved &&
                                additionalComplete()
                        )
            ) {
                val packet = connection.session.receive()
                observed += packet::class.simpleName ?: "<anonymous>"
                when (packet) {
                    is PlayPongPacket ->
                        if (packet.id == pingId) pingRoundTrip = true

                    is PlayServerboundKeepAlivePacket ->
                        if (packet.id == keepAliveId) {
                            keepAliveRoundTrip = true
                        }

                    is ClientTickEndPacket -> tickObserved = true
                    else -> Unit
                }
                onPacket(packet)
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Official client disconnected while processing $label; " +
                        "ping=$pingRoundTrip, keepAlive=$keepAliveRoundTrip, " +
                        "tick=$tickObserved, additional=${additionalComplete()}",
                failure,
            )
        }
        check(
            pingRoundTrip &&
                    keepAliveRoundTrip &&
                    tickObserved &&
                    additionalComplete(),
        ) {
            "Official client did not pass the $label barrier; ping=" +
                    "$pingRoundTrip, keepAlive=$keepAliveRoundTrip, " +
                    "tick=$tickObserved, additional=${additionalComplete()}"
        }
    }

    private suspend fun exerciseReconfiguration(
        connection: MinecraftServerConnection,
        world: MinecraftInitialWorld,
        observedPlayPackets: MutableList<String>,
    ): ReconfigurationOutcome {
        var playerLoaded =
            observedPlayPackets.any { it == "PlayerLoadedPacket" }
        if (!playerLoaded) {
            awaitPlayBarrier(
                connection = connection,
                label = "Player Loaded readiness",
                pingId = PRE_CONFIGURATION_PING_ID,
                keepAliveId = PRE_CONFIGURATION_KEEP_ALIVE_ID,
                observed = observedPlayPackets,
                additionalComplete = { playerLoaded },
                onPacket = { packet ->
                    if (packet == PlayerLoadedPacket) playerLoaded = true
                },
            )
        }
        connection.session.send(StartConfigurationPacket)
        var acknowledged = false
        var packetBudget =
            connection.protocol.configuration.maximumPacketsPerPhase
        while (packetBudget-- > 0 && !acknowledged) {
            val packet = receiveForStage(
                connection,
                "waiting for the Play reconfiguration acknowledgement",
            )
            observedPlayPackets +=
                packet::class.simpleName ?: "<anonymous>"
            acknowledged = packet == AcknowledgeConfigurationPacket
        }
        check(acknowledged) {
            "Official client did not acknowledge reconfiguration"
        }
        check(connection.session.state == ConnectionState.CONFIGURATION) {
            "Server session did not enter Configuration after acknowledgement"
        }

        val observed = mutableListOf<String>()
        var clientInformation = false
        connection.session.send(
            ConfigurationStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        connection.session.send(ConfigurationCookieRequestPacket(COOKIE_KEY))
        connection.session.send(
            ConfigurationClientboundKeepAlivePacket(
                CONFIGURATION_KEEP_ALIVE_ID,
            ),
        )
        connection.session.send(
            ConfigurationPingPacket(CONFIGURATION_PING_ID),
        )
        connection.session.send(
            ConfigurationClientboundPluginMessagePacket(
                CustomPayload.Brand("minecraft-protocol"),
            ),
        )
        connection.session.send(ConfigurationRemoveResourcePackPacket(null))
        connection.session.send(ResetChatPacket)
        connection.session.send(
            ConfigurationCustomReportDetailsPacket(
                listOf(
                    ReportDetail(
                        title = "E2E",
                        description = "reconfiguration",
                    ),
                ),
            ),
        )
        connection.session.send(ConfigurationServerLinksPacket(emptyList()))
        connection.session.send(ConfigurationClearDialogPacket)
        connection.session.send(
            connection.protocol.configuration.protocolData.featureFlags,
        )
        connection.session.send(
            ConfigurationClientboundKnownPacksPacket(
                connection.protocol.configuration.protocolData.knownPacks,
            ),
        )

        var cookieRoundTrip = false
        var keepAliveRoundTrip = false
        var pingRoundTrip = false
        var knownPacks: ConfigurationServerboundKnownPacksPacket? = null
        packetBudget =
            connection.protocol.configuration.maximumPacketsPerPhase
        while (
            packetBudget-- > 0 &&
            !(
                    cookieRoundTrip &&
                            keepAliveRoundTrip &&
                            pingRoundTrip &&
                            knownPacks != null
                    )
        ) {
            val packet = receiveForStage(
                connection,
                "waiting for Configuration cookie/keepalive/ping/Known Packs",
            )
            observed += packet::class.simpleName ?: "<anonymous>"
            when (packet) {
                is ConfigurationClientInformationPacket ->
                    clientInformation = true

                is ConfigurationCookieResponsePacket ->
                    if (packet.key == COOKIE_KEY) {
                        check(packet.payload == COOKIE_PAYLOAD) {
                            "Official client returned the wrong " +
                                    "Configuration cookie"
                        }
                        cookieRoundTrip = true
                    }

                is ConfigurationServerboundKeepAlivePacket ->
                    if (packet.id == CONFIGURATION_KEEP_ALIVE_ID) {
                        keepAliveRoundTrip = true
                    }

                is ConfigurationPongPacket ->
                    if (packet.id == CONFIGURATION_PING_ID) {
                        pingRoundTrip = true
                    }

                is ConfigurationServerboundKnownPacksPacket ->
                    knownPacks = packet

                else -> Unit
            }
        }
        check(
            cookieRoundTrip &&
                    keepAliveRoundTrip &&
                    pingRoundTrip &&
                    knownPacks != null,
        ) {
            "Official client did not complete Configuration probes; cookie=" +
                    "$cookieRoundTrip, keepAlive=$keepAliveRoundTrip, ping=" +
                    "$pingRoundTrip, knownPacks=${knownPacks != null}"
        }
        val acceptedKnownPacks = checkNotNull(knownPacks).knownPacks
        connection.protocol.configuration.protocolData
            .registryPackets(acceptedKnownPacks)
            .forEach { connection.session.send(it) }
        connection.session.send(
            connection.protocol.configuration.protocolData.tags,
        )
        connection.session.send(FinishConfigurationPacket)

        var completed = false
        packetBudget =
            connection.protocol.configuration.maximumPacketsPerPhase
        while (packetBudget-- > 0 && !completed) {
            val packet = receiveForStage(
                connection,
                "waiting for Finish Configuration acknowledgement",
            )
            observed += packet::class.simpleName ?: "<anonymous>"
            completed = packet == AcknowledgeFinishConfigurationPacket
        }
        check(completed) {
            "Official client did not finish reconfiguration"
        }
        check(connection.session.state == ConnectionState.PLAY) {
            "Server session did not return to Play after reconfiguration"
        }

        connection.session.send(
            checkNotNull(connection.protocol.negotiatedPlayLogin) {
                "Reconfiguration requires the negotiated Play Login"
            },
        )
        val reconfiguredWorld = world.copy(
            teleportId = world.teleportId + 3,
        )
        val synchronization =
            connection.synchronizeInitialWorld(reconfiguredWorld)
        connection.session.send(
            PlayClientboundKeepAlivePacket(
                POST_CONFIGURATION_KEEP_ALIVE_ID,
            ),
        )
        connection.session.send(
            ClientboundPingPacket(POST_CONFIGURATION_PING_ID),
        )
        var postKeepAlive = false
        var postPing = false
        var postTick = false
        var postTeleport = false
        var postChunkBatch = false
        var postPlayerLoaded = false
        packetBudget =
            connection.protocol.configuration.maximumPacketsPerPhase
        while (
            packetBudget-- > 0 &&
            !(
                    postKeepAlive &&
                            postPing &&
                            postTick &&
                            postTeleport &&
                            postChunkBatch &&
                            postPlayerLoaded
                    )
        ) {
            val packet = receiveForStage(
                connection,
                "waiting for post-Configuration Play probes",
            )
            observedPlayPackets +=
                packet::class.simpleName ?: "<anonymous>"
            when (packet) {
                is PlayServerboundKeepAlivePacket ->
                    if (packet.id == POST_CONFIGURATION_KEEP_ALIVE_ID) {
                        postKeepAlive = true
                    }

                is PlayPongPacket ->
                    if (packet.id == POST_CONFIGURATION_PING_ID) {
                        postPing = true
                    }

                is ConfirmTeleportationPacket ->
                    if (packet.teleportId == synchronization.teleportId) {
                        postTeleport = true
                    }

                is ChunkBatchReceivedPacket ->
                    postChunkBatch = true

                PlayerLoadedPacket -> postPlayerLoaded = true
                is ClientTickEndPacket -> postTick = true
                else -> Unit
            }
        }
        check(
            postKeepAlive &&
                    postPing &&
                    postTick &&
                    postTeleport &&
                    postChunkBatch &&
                    postPlayerLoaded,
        ) {
            "Official client did not resume Play after reconfiguration; " +
                    "keepAlive=$postKeepAlive, ping=$postPing, tick=$postTick, " +
                    "teleport=$postTeleport, chunkBatch=$postChunkBatch, " +
                    "playerLoaded=$postPlayerLoaded"
        }
        return ReconfigurationOutcome(
            acknowledged = true,
            playerLoaded = true,
            clientInformation = clientInformation,
            knownPacks = acceptedKnownPacks.size,
            cookieRoundTrip = true,
            keepAliveRoundTrip = true,
            pingRoundTrip = true,
            completed = true,
            synchronizedChunks = synchronization.chunkCount,
            synchronizedEntities = synchronization.entityCount,
            teleportAcknowledged = true,
            chunkBatchAcknowledged = true,
            playerLoadedAfterward = true,
            postPlayRoundTrip = true,
            observedPackets = observed,
        )
    }

    private suspend fun receiveForStage(
        connection: MinecraftServerConnection,
        stage: String,
    ): Packet =
        try {
            connection.session.receive()
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "Official client disconnected while $stage " +
                        "(server state ${connection.session.state})",
                failure,
            )
        }

    private fun launchHeadlessClient(
        javaExecutable: Path,
        minecraftDirectory: Path,
        installation: ClientInstallation,
        gameDirectory: Path,
        launcher: Path,
        port: Int,
    ): Process {
        val playerUuid = offlineUuid(PLAYER_NAME).toUndashedString()
        val minecraftJvmArguments = listOf(
            "-Xms256M",
            "-Xmx1G",
            "-Djava.awt.headless=true",
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
        ).joinToString(" ")
        val command = listOf(
            javaExecutable.absolutePathString(),
            "-Xms128M",
            "-Xmx512M",
            "-Dhmc.mcdir=${minecraftDirectory.absolutePathString()}",
            "-Dhmc.gamedir=${gameDirectory.absolutePathString()}",
            "-Dhmc.java.versions=${javaExecutable.absolutePathString()}",
            "-Dhmc.no.auto.config=true",
            "-Dhmc.java.use.current=false",
            "-Dhmc.java.require.exact=true",
            "-Dhmc.auto.download.java=false",
            "-Dhmc.auto.download.versions=false",
            "-Dhmc.account.refresh.on.game.launch=false",
            "-Dhmc.account.refresh.on.launch=false",
            "-Dhmc.store.accounts=false",
            "-Dhmc.offline=true",
            "-Dhmc.offline.username=$PLAYER_NAME",
            "-Dhmc.offline.uuid=$playerUuid",
            "-Dhmc.offline.token=0",
            "-Dhmc.jvmargs=$minecraftJvmArguments",
            "-Dhmc.gameargs=--quickPlayMultiplayer 127.0.0.1:$port",
            "-Dhmc.jline.enabled=false",
            "-Dhmc.filehandler.enabled=false",
            "-Dhmc.rethrow.launch.exceptions=true",
            "-Dhmc.exit.on.failed.command=true",
            "-Dhmc.crash.report.watcher=true",
            "-Dhmc.check.xvfb=false",
            "-jar",
            launcher.absolutePathString(),
            "--command",
            "launch",
            installation.version,
            "-lwjgl",
            "-offline",
        )
        return ProcessBuilder(command)
            .directory(gameDirectory.parent.toFile())
            .redirectErrorStream(true)
            .start()
    }

    private fun captureLog(
        process: Process,
        clientLog: StringBuilder,
        output: Path,
    ): Thread =
        Thread.ofVirtual().name("official-client-e2e-log").start {
            try {
                Files.newBufferedWriter(output).use { writer ->
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            writer.appendLine(line)
                            writer.flush()
                            synchronized(clientLog) {
                                clientLog.appendLine(line)
                                if (clientLog.length > 300_000) {
                                    clientLog.delete(
                                        0,
                                        clientLog.length - 200_000,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (failure: IOException) {
                if (failure.message != "Stream closed") throw failure
            }
        }

    private fun stopProcess(process: Process) {
        val descendants = process.toHandle().descendants().toList()
        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) handle.destroy()
        }
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            descendants.asReversed().forEach { handle ->
                if (handle.isAlive) handle.destroyForcibly()
            }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private fun writeClientOptions(gameDirectory: Path) {
        Files.writeString(
            gameDirectory.resolve("options.txt"),
            """
            |autoSuggestions:false
            |enableVsync:false
            |maxFps:30
            |narrator:0
            |pauseOnLostFocus:false
            |renderDistance:2
            |simulationDistance:5
            """.trimMargin() + "\n",
        )
    }

    private fun writeReport(
        output: Path,
        installation: ClientInstallation,
        outcome: EndToEndOutcome,
    ) {
        Files.writeString(
            output,
            """
            |{
            |  "schema_version": 4,
            |  "minecraft_version": "${installation.version}",
            |  "protocol_version": ${MinecraftProtocol.PROTOCOL_VERSION},
            |  "official_client_sha1": "${installation.clientSha1}",
            |  "client": "official client with HeadlessMC LWJGL stubs",
            |  "headless": true,
            |  "server_stack": "protocol-server -> protocol-session -> protocol-transport",
            |  "online_mode": false,
            |  "status_connections": ${outcome.statusConnections},
            |  "player_name": "${outcome.playerName}",
            |  "accepted_known_packs": ${outcome.acceptedKnownPacks},
            |  "login_completed": true,
            |  "configuration_completed": true,
            |  "play_login_processed": true,
            |  "play_keep_alive_round_trip": true,
            |  "synchronized_chunks": ${outcome.synchronizedChunks},
            |  "synchronized_entities": ${outcome.synchronizedEntities},
            |  "entity_type": "${outcome.entityType}",
            |  "entity_type_id": ${outcome.entityTypeId},
            |  "teleport_acknowledged": ${outcome.teleportAcknowledged},
            |  "chunk_batch_acknowledged": ${outcome.chunkBatchAcknowledged},
            |  "client_tick_observed": ${outcome.clientTickObserved},
            |  "play_probe_packet_transmissions": ${outcome.playProbePacketTransmissions},
            |  "play_barrier_packet_transmissions": ${outcome.playBarrierPacketTransmissions},
            |  "play_clientbound_total_transmissions": ${
                outcome.playProbePacketTransmissions +
                        outcome.playBarrierPacketTransmissions
            },
            |  "play_clientbound_packet_type_count": ${outcome.playClientboundPacketTypes.size},
            |  "play_clientbound_packet_types": [
            |${
                outcome.playClientboundPacketTypes.joinToString(",\n") {
                    "    \"$it\""
                }
            }
            |  ],
            |  "play_cookie_round_trip": ${outcome.playCookieRoundTrip},
            |  "play_ping_round_trip": ${outcome.playPingRoundTrip},
            |  "second_teleport_acknowledged": ${outcome.secondTeleportAcknowledged},
            |  "respawn_synchronized_chunks": ${outcome.respawnSynchronizedChunks},
            |  "respawn_synchronized_entities": ${outcome.respawnSynchronizedEntities},
            |  "respawn_teleport_acknowledged": ${outcome.respawnTeleportAcknowledged},
            |  "respawn_chunk_batch_acknowledged": ${outcome.respawnChunkBatchAcknowledged},
            |  "respawn_player_loaded": ${outcome.respawnPlayerLoaded},
            |  "respawn_play_round_trip": ${outcome.respawnPlayRoundTrip},
            |  "player_loaded_before_reconfiguration": ${outcome.playerLoadedBeforeReconfiguration},
            |  "reconfiguration_acknowledged": ${outcome.reconfigurationAcknowledged},
            |  "reconfiguration_client_information_observed": ${outcome.reconfigurationClientInformationObserved},
            |  "reconfiguration_known_packs": ${outcome.reconfigurationKnownPacks},
            |  "configuration_cookie_round_trip": ${outcome.configurationCookieRoundTrip},
            |  "configuration_keep_alive_round_trip": ${outcome.configurationKeepAliveRoundTrip},
            |  "configuration_ping_round_trip": ${outcome.configurationPingRoundTrip},
            |  "configuration_brand_payload_accepted": true,
            |  "configuration_resource_packs_cleared": true,
            |  "reconfiguration_completed": ${outcome.reconfigurationCompleted},
            |  "reconfiguration_synchronized_chunks": ${outcome.reconfigurationSynchronizedChunks},
            |  "reconfiguration_synchronized_entities": ${outcome.reconfigurationSynchronizedEntities},
            |  "reconfiguration_teleport_acknowledged": ${outcome.reconfigurationTeleportAcknowledged},
            |  "reconfiguration_chunk_batch_acknowledged": ${outcome.reconfigurationChunkBatchAcknowledged},
            |  "reconfiguration_player_loaded": ${outcome.reconfigurationPlayerLoaded},
            |  "post_configuration_play_round_trip": ${outcome.postConfigurationPlayRoundTrip},
            |  "client_remained_connected": ${outcome.clientRemainedConnected},
            |  "observed_play_packets": [
            |${
                outcome.observedPlayPackets.joinToString(",\n") {
                    "    \"$it\""
                }
            }
            |  ],
            |  "observed_reconfiguration_packets": [
            |${
                outcome.observedConfigurationPackets.joinToString(",\n") {
                    "    \"$it\""
                }
            }
            |  ]
            |}
            """.trimMargin() + "\n",
        )
    }
}

private data class EndToEndOutcome(
    val statusConnections: Int,
    val playerName: String,
    val acceptedKnownPacks: Int,
    val synchronizedChunks: Int,
    val synchronizedEntities: Int,
    val entityType: String,
    val entityTypeId: Int,
    val teleportAcknowledged: Boolean,
    val chunkBatchAcknowledged: Boolean,
    val clientTickObserved: Boolean,
    val clientRemainedConnected: Boolean,
    val playProbePacketTransmissions: Int,
    val playBarrierPacketTransmissions: Int,
    val playClientboundPacketTypes: List<String>,
    val playCookieRoundTrip: Boolean,
    val playPingRoundTrip: Boolean,
    val secondTeleportAcknowledged: Boolean,
    val respawnSynchronizedChunks: Int,
    val respawnSynchronizedEntities: Int,
    val respawnTeleportAcknowledged: Boolean,
    val respawnChunkBatchAcknowledged: Boolean,
    val respawnPlayerLoaded: Boolean,
    val respawnPlayRoundTrip: Boolean,
    val playerLoadedBeforeReconfiguration: Boolean,
    val reconfigurationAcknowledged: Boolean,
    val reconfigurationClientInformationObserved: Boolean,
    val reconfigurationKnownPacks: Int,
    val configurationCookieRoundTrip: Boolean,
    val configurationKeepAliveRoundTrip: Boolean,
    val configurationPingRoundTrip: Boolean,
    val reconfigurationCompleted: Boolean,
    val reconfigurationSynchronizedChunks: Int,
    val reconfigurationSynchronizedEntities: Int,
    val reconfigurationTeleportAcknowledged: Boolean,
    val reconfigurationChunkBatchAcknowledged: Boolean,
    val reconfigurationPlayerLoaded: Boolean,
    val postConfigurationPlayRoundTrip: Boolean,
    val observedPlayPackets: List<String>,
    val observedConfigurationPackets: List<String>,
)

private data class PlayProbeOutcome(
    val probePacketTransmissions: Int,
    val barrierPacketTransmissions: Int,
    val clientboundPacketTypes: List<String>,
    val cookieRoundTrip: Boolean,
    val pingRoundTrip: Boolean,
    val teleportAcknowledged: Boolean,
)

private data class RespawnOutcome(
    val synchronizedChunks: Int,
    val synchronizedEntities: Int,
    val teleportAcknowledged: Boolean,
    val chunkBatchAcknowledged: Boolean,
    val playerLoaded: Boolean,
    val playRoundTrip: Boolean,
)

private data class ReconfigurationOutcome(
    val acknowledged: Boolean,
    val playerLoaded: Boolean,
    val clientInformation: Boolean,
    val knownPacks: Int,
    val cookieRoundTrip: Boolean,
    val keepAliveRoundTrip: Boolean,
    val pingRoundTrip: Boolean,
    val completed: Boolean,
    val synchronizedChunks: Int,
    val synchronizedEntities: Int,
    val teleportAcknowledged: Boolean,
    val chunkBatchAcknowledged: Boolean,
    val playerLoadedAfterward: Boolean,
    val postPlayRoundTrip: Boolean,
    val observedPackets: List<String>,
)

private data class ClientInstallation(
    val version: String,
    val javaMajorVersion: Int,
    val clientSha1: String,
) {
    companion object {
        fun load(
            minecraftDirectory: Path,
            version: String,
        ): ClientInstallation {
            val versionDirectory =
                minecraftDirectory.resolve("versions").resolve(version)
            val jsonPath = versionDirectory.resolve("$version.json")
            val clientJar = versionDirectory.resolve("$version.jar")
            require(Files.isRegularFile(jsonPath)) {
                "Official client metadata does not exist: $jsonPath"
            }
            require(Files.isRegularFile(clientJar)) {
                "Official client JAR does not exist: $clientJar"
            }
            val root = Json.parseToJsonElement(
                Files.readString(jsonPath),
            ).jsonObject
            val expectedClientSha1 = root.requiredObject("downloads")
                .requiredObject("client")
                .requiredString("sha1")
            require(sha1(clientJar) == expectedClientSha1) {
                "Official client JAR failed its Mojang SHA-1"
            }
            return ClientInstallation(
                version = version,
                javaMajorVersion = root.requiredObject("javaVersion")
                    .requiredInt("majorVersion"),
                clientSha1 = expectedClientSha1,
            )
        }
    }
}

private fun javaMajorVersion(javaExecutable: Path): Int {
    val process = ProcessBuilder(
        javaExecutable.absolutePathString(),
        "-version",
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(15, TimeUnit.SECONDS)) {
        "Timed out querying $javaExecutable"
    }
    check(process.exitValue() == 0) {
        "Could not query $javaExecutable: $output"
    }
    return Regex("""version "(\d+)""")
        .find(output)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: error("Could not parse Java version from: $output")
}

private fun sha1(path: Path): String =
    MessageDigest.getInstance("SHA-1").run {
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                update(buffer, 0, read)
            }
        }
        digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

private fun JsonObject.requiredObject(name: String): JsonObject =
    getValue(name).jsonObject

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int =
    requiredString(name).toInt()

private const val CONNECTION_STABILITY_DELAY_MILLIS: Long = 1_500
