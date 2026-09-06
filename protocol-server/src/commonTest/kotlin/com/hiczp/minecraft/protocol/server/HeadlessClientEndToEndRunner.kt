package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaRegistryData
import com.hiczp.minecraft.protocol.configuration.vanilla.toVanillaConfigurationData
import com.hiczp.minecraft.protocol.configuration.vanilla.vanillaDataPackRegistryProjectors
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.test.*
import com.hiczp.minecraft.world.format.EntityVector3d
import com.hiczp.minecraft.world.format.datapack.DataPack
import com.hiczp.minecraft.world.format.datapack.DataPackId
import com.hiczp.minecraft.world.format.datapack.DataPackStack
import com.hiczp.minecraft.world.format.datapack.vanilla.VanillaDataPacks
import io.ktor.network.selector.*
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Black-box interoperability runner for the matching official client through
 * HeadlessMC's LWJGL stubs. No display server or GUI path is involved.
 */
internal object HeadlessClientEndToEndRunner {
    private const val MAXIMUM_PACKETS_PER_STAGE = 2_048
    private const val PLAYER_NAME = "KmpE2EClient"
    private const val PLAY_PING_ID = 0x1020_3040
    private const val CONFIGURATION_PING_ID = 0x5060_7080
    private const val PRE_CONFIGURATION_PING_ID = 0x5566_7788
    private const val POST_CONFIGURATION_PING_ID = 0x1122_3344
    private const val RESPAWN_PING_ID = 0x2435_4657
    private val COOKIE_KEY = Identifier("minecraft-protocol:e2e")
    private val COOKIE_PAYLOAD = ByteString(
        "official-client-cookie".encodeToByteArray(),
    )
    private val OPTIONS = MinecraftServerNegotiationOptions(
        configurationData = projectedVanillaConfigurationData(),
        compressionThreshold = 64,
        viewDistance = 2,
        simulationDistance = 5,
        statusDescription = "minecraft-protocol official client E2E",
    )

    /** Exercises every release-matched default disk-JSON to network-NBT registry projector. */
    private fun projectedVanillaConfigurationData() = VanillaDataPacks.coreDataPackStack.resolve(
        VanillaDataPacks.dataPackFormatVersion,
    ).let { resolvedCoreDataPackStack ->
        val projectedDataPack = DataPack(
            dataPackId = DataPackId("official-client-default-projectors"),
            dataPackMetadata = null,
            dataPackFileContentsByPath = buildMap {
                vanillaDataPackRegistryProjectors.forEach { dataPackRegistryProjector ->
                    resolvedCoreDataPackStack.resources(dataPackRegistryProjector.dataPackResourceType)
                        .values.forEach { resolvedDataPackResource ->
                            put(
                                resolvedDataPackResource.sourceDataPackFilePath,
                                resolvedDataPackResource.dataPackFileContent,
                            )
                        }
                }
            },
        )
        DataPackStack(projectedDataPack).toVanillaConfigurationData()
    }

    suspend fun run() {
        var headlessMinecraftClient: HeadlessMinecraftClient? = null
        var primaryFailure: Throwable? = null
        try {
            SelectorManager(Dispatchers.Default).use { selectorManager ->
                val launched = MinecraftTestSupport.newHeadlessClient(
                    headlessMinecraftClientConfiguration = HeadlessMinecraftClientConfiguration(
                        playerName = PLAYER_NAME,
                    ),
                )
                headlessMinecraftClient = launched
                val connectedOfficialClient = connectOfficialClient(selectorManager, launched)
                connectedOfficialClient.minecraftServer.use {
                    awaitPlayRoundTrip(connectedOfficialClient.minecraftServerConnection, launched)
                }
            }
        } catch (failure: CancellationException) {
            primaryFailure = failure
            throw failure
        } catch (failure: Throwable) {
            val clientLog = try {
                headlessMinecraftClient?.let { MinecraftTestSupport.logText(it) }.orEmpty()
            } catch (logFailure: CancellationException) {
                logFailure.addSuppressed(failure)
                primaryFailure = logFailure
                throw logFailure
            } catch (logFailure: Throwable) {
                failure.addSuppressed(logFailure)
                "<official client log unavailable>"
            }
            val wrapped = AssertionError(
                """
                |Official client -> production initial-world E2E failed: $failure
                |--- official client log ---
                |$clientLog
                """.trimMargin(),
                failure,
            )
            primaryFailure = wrapped
            throw wrapped
        } finally {
            withContext(NonCancellable) {
                try {
                    headlessMinecraftClient?.let { launched ->
                        check(MinecraftTestSupport.closeAndAwait(launched) == 0) {
                            "Official client did not stop cleanly"
                        }
                    }
                } catch (closeFailure: Throwable) {
                    primaryFailure?.addSuppressed(closeFailure)
                        ?: throw closeFailure
                }
            }
        }
    }

    private suspend fun connectOfficialClient(
        selectorManager: SelectorManager,
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): ConnectedOfficialClient {
        val failures = mutableListOf<String>()
        repeat(MAXIMUM_CONNECTION_ATTEMPTS) { index ->
            val attempt = index + 1
            val minecraftServer = MinecraftServer.bind(
                selectorManager = selectorManager,
                host = LOOPBACK,
                port = 0,
            )
            var accepted = false
            try {
                val minecraftTestEndpoint = MinecraftTestEndpoint(
                    host = LOOPBACK,
                    port = minecraftServer.port,
                )
                val commandState = MinecraftTestSupport.connectHeadlessClient(
                    headlessMinecraftClient = headlessMinecraftClient,
                    minecraftTestEndpoint = minecraftTestEndpoint,
                )
                if (commandState.isTerminalConnectionScreen()) {
                    failures += "attempt $attempt: connect command completed in ${commandState.description()}"
                } else {
                    when (
                        val deadlineResult = awaitConnectionWithin(
                            minecraftServer = minecraftServer,
                            headlessMinecraftClient = headlessMinecraftClient,
                        )
                    ) {
                        is DeadlineResult.Completed -> {
                            accepted = true
                            return ConnectedOfficialClient(
                                minecraftServer = minecraftServer,
                                minecraftServerConnection = deadlineResult.value,
                            )
                        }

                        DeadlineResult.TimedOut -> {
                            val finalState = MinecraftTestSupport.headlessClientState(headlessMinecraftClient)
                            val stateChange = "${commandState.description()} -> ${finalState.description()}"
                            failures += "attempt $attempt: no TCP in $CONNECTION_ATTEMPT_TIMEOUT; GUI $stateChange"
                        }
                    }
                }
            } finally {
                if (!accepted) minecraftServer.close()
            }
            if (attempt < MAXIMUM_CONNECTION_ATTEMPTS) {
                MinecraftTestSupport.disconnectHeadlessClient(headlessMinecraftClient)
            }
        }
        val details = failures.joinToString()
        error("Official client made no TCP connection after $MAXIMUM_CONNECTION_ATTEMPTS attempts: $details")
    }

    private suspend fun awaitConnectionWithin(
        minecraftServer: MinecraftServer,
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): DeadlineResult<MinecraftServerConnection> = try {
        awaitExternal(CONNECTION_ATTEMPT_TIMEOUT) { minecraftServer.accept() }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        if (!MinecraftTestSupport.isAlive(headlessMinecraftClient)) {
            error(
                "Official client exited with ${MinecraftTestSupport.exitCode(headlessMinecraftClient)} while connecting",
            )
        }
        throw failure
    }

    private suspend fun awaitPlayRoundTrip(
        minecraftServerConnection: MinecraftServerConnection,
        headlessMinecraftClient: HeadlessMinecraftClient,
    ) = coroutineScope {
        check(MinecraftTestSupport.isAlive(headlessMinecraftClient)) {
            "Official client exited with ${MinecraftTestSupport.exitCode(headlessMinecraftClient)}"
        }
        val ready = when (
            val deadlineResult = awaitExternal(PROTOCOL_STAGE_TIMEOUT) {
                minecraftServerConnection.negotiate(minecraftServerNegotiationOptions = OPTIONS)
            }
        ) {
            is DeadlineResult.Completed -> deadlineResult.value
            DeadlineResult.TimedOut -> error(
                "Official client did not complete protocol negotiation within $PROTOCOL_STAGE_TIMEOUT",
            )
        }
        checkNotNull(ready) {
            "Official client connection completed Status instead of entering Play"
        }
        try {
            val pig = testEntity(2, "pig", EntityVector3d(3.5, 65.0, 3.5))
            val arrow = testEntity(3, "arrow", EntityVector3d(2.5, 66.0, 2.5), EntityVector3d(0.05, 0.0, 0.0))
            val minecart = testEntity(4, "minecart", EntityVector3d(4.5, 65.0, 4.5))
            val horse = testEntity(5, "horse", EntityVector3d(5.5, 65.0, 5.5))
            val batch = testEntityBatch(
                minecraftServerConnection.packetCodecContext, listOf(pig, arrow, minecart, horse),
                mapOf(pig.uuid to 2, arrow.uuid to 3, minecart.uuid to 4, horse.uuid to 5),
            )
            val minecraftInitialWorld = testInitialWorld(
                minecraftServerNegotiationResult = ready, entityBatches = listOf(batch),
            )
            val initialWorldSync = launch {
                runProtocolStage("initial-world synchronization") {
                    minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
                    minecraftServerConnection.requestFlush()
                }
            }
            val observed = mutableListOf<String>()
            var teleportAcknowledged = false
            var chunkBatchAcknowledged = false
            var clientTickObserved = false
            var initialPacketBudget = MAXIMUM_PACKETS_PER_STAGE
            while (
                initialPacketBudget-- > 0 &&
                !(
                        teleportAcknowledged &&
                                chunkBatchAcknowledged &&
                                clientTickObserved
                        )
            ) {
                val packet = receiveForStage(
                    minecraftServerConnection,
                    "waiting for initial-world acknowledgements",
                )
                observed += packet::class.simpleName ?: "<anonymous>"
                when (packet) {
                    is ServerboundAcceptTeleportationPacket ->
                        teleportAcknowledged =
                            packet.id == minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId

                    is ServerboundChunkBatchReceivedPacket -> chunkBatchAcknowledged = true

                    is ServerboundClientTickEndPacket -> clientTickObserved = true

                    else -> Unit
                }
            }
            val initialState = listOf(
                "teleport=$teleportAcknowledged",
                "chunk=$chunkBatchAcknowledged",
                "tick=$clientTickObserved",
            ).joinToString()
            check(
                teleportAcknowledged &&
                        chunkBatchAcknowledged &&
                        clientTickObserved,
            ) {
                "Initial acknowledgements incomplete: $initialState; packets=${observed.joinToString()}"
            }
            initialWorldSync.join()
            // Start the short observation probe only after the client acknowledges its initial world.
            // Production negotiation keeps its normal KeepAlive active throughout initial synchronization.
            val recordingKeepAlive = minecraftServerConnection.enableRecordingPlayKeepAlive(5.seconds)
            runProtocolStage("managed Play KeepAlive round trip") {
                coroutineScope {
                    // Keep draining ordinary client ticks so backpressure cannot hide the managed reply.
                    val incoming = launch {
                        while (isActive) {
                            val packet = receiveForStage(minecraftServerConnection, "waiting for managed KeepAlive")
                            observed += packet::class.simpleName ?: "<anonymous>"
                        }
                    }
                    try {
                        recordingKeepAlive.roundTrip.await()
                    } finally {
                        incoming.cancelAndJoin()
                    }
                }
            }

            runProtocolStage("Play packet coverage") {
                exercisePlayPackets(
                    minecraftServerConnection = minecraftServerConnection,
                    playerEntityId = ready.clientboundLoginPacket.playerId,
                    pigEntityId = 2,
                    pigPosition = Vector3d(pig.position.x, pig.position.y, pig.position.z),
                    projectileEntityId = 3,
                    vehicleEntityId = 4,
                    horseEntityId = 5,
                    nextTeleportId = minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId + 1,
                    observed = observed,
                )
            }
            runProtocolStage("Respawn coverage") {
                exerciseRespawn(
                    minecraftServerConnection = minecraftServerConnection,
                    clientboundLoginPacket = ready.clientboundLoginPacket,
                    minecraftInitialWorld = minecraftInitialWorld.copy(
                        minecraftInitialWorldBootstrap = minecraftInitialWorld.minecraftInitialWorldBootstrap.copy(
                            teleportId = minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId + 2,
                        ),
                    ),
                    observed = observed,
                )
            }
            runProtocolStage("reconfiguration coverage") {
                exerciseReconfiguration(
                    minecraftServerConnection = minecraftServerConnection,
                    clientboundLoginPacket = ready.clientboundLoginPacket,
                    minecraftInitialWorld = minecraftInitialWorld,
                    observedPlayPackets = observed,
                )
            }
            check(MinecraftTestSupport.isAlive(headlessMinecraftClient)) {
                "Official client exited after protocol round-trip probes"
            }
        } finally {
            minecraftServerConnection.close()
        }
    }

    private suspend fun exercisePlayPackets(
        minecraftServerConnection: MinecraftServerConnection,
        playerEntityId: Int,
        pigEntityId: Int,
        pigPosition: Vector3d,
        projectileEntityId: Int,
        vehicleEntityId: Int,
        horseEntityId: Int,
        nextTeleportId: Int,
        observed: MutableList<String>,
    ) {
        val bossBarId = Uuid.fromLongs(0, 3)
        val playerListProfileId = Uuid.fromLongs(0, 5)
        val waypointId = WaypointIdentifier.Named("headless-e2e")
        val waypointIcon = WaypointIcon(
            style = Identifier("default"),
            color = 0x33AAFF,
        )
        val closedRecipeBook = RecipeBookTypeSettings(open = false, filtering = false)
        val sound = SoundEventHolder.Direct(
            Identifier("entity.experience_orb.pickup"),
        )
        val simpleParticle = ParticleOptions.Simple(ParticleType.FLAME)
        val blockTypeId = VanillaRegistryData
            .requireRegistry(Identifier("block"))
            .requireRawId(Identifier("grass_block"))
        val blockEntityTypeId = VanillaRegistryData
            .requireRegistry(Identifier("block_entity_type"))
            .requireRawId(Identifier("furnace"))
        val genericContainerTypeId = VanillaRegistryData
            .requireRegistry(Identifier("menu"))
            .requireRawId(Identifier("generic_9x1"))
        val merchantContainerTypeId = VanillaRegistryData
            .requireRegistry(Identifier("menu"))
            .requireRawId(Identifier("merchant"))
        val furnaceContainerTypeId = VanillaRegistryData
            .requireRegistry(Identifier("menu"))
            .requireRawId(Identifier("furnace"))
        val emptyLight = ClientboundLightUpdatePacketData(
            skyYMask = BitSet(longArrayOf()),
            blockYMask = BitSet(longArrayOf()),
            emptySkyYMask = BitSet(longArrayOf()),
            emptyBlockYMask = BitSet(longArrayOf()),
            skyUpdates = emptyList(),
            blockUpdates = emptyList(),
        )
        val packets = listOf(
            ClientboundSetExperiencePacket(
                experienceProgress = 0.5f,
                experienceLevel = 5,
                totalExperience = 10,
            ),
            ClientboundSetHealthPacket(
                health = 20.0f,
                food = 20,
                saturation = 5.0f,
            ),
            ClientboundSetHeldSlotPacket(slot = 1),
            ClientboundSetTimePacket(
                gameTime = 6_000,
                clockUpdates = mapOf(
                    0 to ClockNetworkState(
                        totalTicks = 6_000,
                        partialTick = 0.25f,
                        rate = 1.0f,
                    ),
                ),
            ),
            ClientboundSetTitlesAnimationPacket(
                fadeIn = 1,
                stay = 5,
                fadeOut = 1,
            ),
            ClientboundSetTitleTextPacket(
                TextComponent.literal("minecraft-protocol E2E"),
            ),
            ClientboundSetSubtitleTextPacket(
                TextComponent.literal(
                    "official ${MinecraftProtocol.MINECRAFT_VERSION} client",
                ),
            ),
            ClientboundSystemChatPacket(
                content = TextComponent.literal(
                    "Protocol clientbound packets accepted",
                ),
                overlay = false,
            ),
            ClientboundTabListPacket(
                header = TextComponent.literal("minecraft-protocol"),
                footer = TextComponent.literal("headless E2E"),
            ),
            ClientboundGameRuleValuesPacket(emptyMap()),
            ClientboundSetEntityMotionPacket(
                id = pigEntityId,
                movement = Vector3d(0.01, 0.0, -0.01),
            ),
            ClientboundMoveEntityPacket.Pos(
                entityId = pigEntityId,
                xa = 64,
                ya = 0,
                za = -64,
                onGround = true,
            ),
            ClientboundEntityPositionSyncPacket(
                id = pigEntityId,
                values = PositionMoveRotation(
                    position = Vector3d(3.75, 65.0, 3.25),
                    deltaMovement = Vector3d(0.0, 0.0, 0.0),
                    yaw = 30.0f,
                    pitch = 0.0f,
                ),
                onGround = true,
            ),
            ClientboundCustomReportDetailsPacket(
                listOf(
                    ReportDetail(
                        title = "E2E",
                        description = "official headless client",
                    ),
                ),
            ),
            ClientboundServerLinksPacket(emptyList()),
            ClientboundClearDialogPacket,
            ClientboundAnimatePacket(
                id = pigEntityId,
                action = 0,
            ),
            ClientboundAwardStatsPacket(emptyList()),
            ClientboundBlockChangedAckPacket(sequence = 0),
            ClientboundBlockDestructionPacket(
                id = pigEntityId,
                pos = BlockPosition(0, 64, 0),
                progress = 0,
            ),
            ClientboundBlockDestructionPacket(
                id = pigEntityId,
                pos = BlockPosition(0, 64, 0),
                progress = 255,
            ),
            ClientboundBlockUpdatePacket(
                pos = BlockPosition(0, 65, 0),
                blockState = 0,
            ),
            ClientboundBlockEventPacket(
                pos = BlockPosition(0, 64, 0),
                b0 = 0,
                b1 = 0,
                block = blockTypeId,
            ),
            ClientboundBlockEntityDataPacket(
                pos = BlockPosition(0, 65, 0),
                type = blockEntityTypeId,
                tag = NbtCompound(emptyMap()),
            ),
            ClientboundBossEventPacket(
                id = bossBarId,
                operation = BossBarAction.Add(
                    title = TextComponent.literal("Headless E2E"),
                    health = 1.0f,
                    color = BossBarColor.GREEN,
                    division = BossBarDivision.TEN_NOTCHES,
                    flags = 0,
                ),
            ),
            ClientboundBossEventPacket(
                id = bossBarId,
                operation = BossBarAction.UpdateHealth(0.5f),
            ),
            ClientboundBossEventPacket(
                id = bossBarId,
                operation = BossBarAction.UpdateTitle(
                    TextComponent.literal("Protocol probe"),
                ),
            ),
            ClientboundBossEventPacket(
                id = bossBarId,
                operation = BossBarAction.UpdateStyle(
                    color = BossBarColor.BLUE,
                    division = BossBarDivision.SIX_NOTCHES,
                ),
            ),
            ClientboundBossEventPacket(
                id = bossBarId,
                operation = BossBarAction.UpdateFlags(0),
            ),
            ClientboundBossEventPacket(
                id = bossBarId,
                operation = BossBarAction.Remove,
            ),
            ClientboundChunksBiomesPacket(emptyList()),
            ClientboundClearTitlesPacket(resetTimes = true),
            ClientboundContainerClosePacket(containerId = 0),
            ClientboundCooldownPacket(
                cooldownGroup = Identifier("minecraft-protocol:e2e"),
                duration = 0,
            ),
            ClientboundCustomChatCompletionsPacket(
                action = ClientboundCustomChatCompletionsPacket.Action.SET,
                entries = listOf("minecraft-protocol-e2e"),
            ),
            ClientboundCommandSuggestionsPacket(
                id = 0,
                start = 0,
                length = 0,
                suggestions = emptyList(),
            ),
            ClientboundCommandsPacket(
                entries = listOf(
                    CommandNode.Root(children = listOf(1)),
                    CommandNode.Literal(
                        name = "minecraft-protocol-e2e",
                        children = emptyList(),
                        executable = true,
                    ),
                ),
                rootIndex = 0,
            ),
            ClientboundCustomPayloadPacket(
                CustomPayload.Brand("minecraft-protocol"),
            ),
            ClientboundDamageEventPacket(
                entityId = pigEntityId,
                sourceType = 0,
                sourceCauseId = null,
                sourceDirectId = null,
                sourcePosition = null,
            ),
            ClientboundDebugBlockValuePacket(
                blockPos = BlockPosition(0, 64, 0),
                update = DebugSubscriptionUpdate(
                    type = DebugSubscriptionType.BEE_HIVE,
                    data = null,
                ),
            ),
            ClientboundDebugChunkValuePacket(
                chunkPos = ChunkPos(0, 0),
                update = DebugSubscriptionUpdate(
                    type = DebugSubscriptionType.VILLAGE_SECTION,
                    data = null,
                ),
            ),
            ClientboundDebugEntityValuePacket(
                entityId = pigEntityId,
                update = DebugSubscriptionUpdate(
                    type = DebugSubscriptionType.BEE,
                    data = null,
                ),
            ),
            ClientboundDebugEventPacket(
                DebugSubscriptionEvent(
                    DebugSubscriptionData.Raid(emptyList()),
                ),
            ),
            ClientboundDebugSamplePacket(
                sample = listOf(1),
                debugSampleType = DebugSampleType.TICK_TIME,
            ),
            ClientboundDisguisedChatPacket(
                message = TextComponent.literal(
                    "Official client accepted disguised chat",
                ),
                chatType = BoundChatType(
                    chatType = ChatTypeHolder.Reference(0),
                    name = TextComponent.literal("Server"),
                    targetName = null,
                ),
            ),
            ClientboundEntityEventPacket(
                entityId = pigEntityId,
                eventId = 2,
            ),
            ClientboundExplodePacket(
                center = Vector3d(0.5, 65.0, 0.5),
                radius = 0.0f,
                blockCount = 0,
                playerKnockback = null,
                explosionParticle = ParticleOptions.Simple(ParticleType.EXPLOSION),
                explosionSound = SoundEventHolder.Direct(
                    Identifier("entity.generic.explode"),
                ),
                blockParticles = emptyList(),
            ),
            ClientboundGameTestHighlightPosPacket(
                absolutePos = BlockPosition(0, 65, 0),
                relativePos = BlockPosition(0, 0, 0),
            ),
            ClientboundHurtAnimationPacket(
                id = pigEntityId,
                yaw = 15.0f,
            ),
            ClientboundInitializeBorderPacket(
                newCenterX = 0.0,
                newCenterZ = 0.0,
                oldSize = 128.0,
                newSize = 128.0,
                lerpTime = 0,
                newAbsoluteMaxSize = 29_999_984,
                warningBlocks = 5,
                warningTime = 15,
            ),
            ClientboundLightUpdatePacket(
                x = 0,
                z = 0,
                lightData = emptyLight,
            ),
            ClientboundLowDiskSpaceWarningPacket,
            ClientboundLevelEventPacket(
                type = 1000,
                pos = BlockPosition(0, 65, 0),
                data = 0,
                globalEvent = false,
            ),
            ClientboundLevelParticlesPacket(
                overrideLimiter = false,
                alwaysShow = true,
                x = 0.5,
                y = 66.0,
                z = 0.5,
                xDist = 0.0f,
                yDist = 0.0f,
                zDist = 0.0f,
                maxSpeed = 0.0f,
                count = 1,
                particle = simpleParticle,
            ),
            ClientboundMoveEntityPacket.PosRot(
                entityId = pigEntityId,
                xa = 16,
                ya = 0,
                za = 16,
                yRot = Angle.fromDegrees(45.0f),
                xRot = Angle.fromDegrees(5.0f),
                onGround = true,
            ),
            ClientboundMoveEntityPacket.Rot(
                entityId = pigEntityId,
                yRot = Angle.fromDegrees(60.0f),
                xRot = Angle.fromDegrees(0.0f),
                onGround = true,
            ),
            ClientboundMoveVehiclePacket(
                position = Vector3d(0.5, 65.0, 0.5),
                yRot = 0.0f,
                xRot = 0.0f,
            ),
            ClientboundMoveMinecartPacket(
                entityId = vehicleEntityId,
                lerpSteps = listOf(
                    MinecartStep(
                        position = Vector3d(4.75, 65.0, 4.5),
                        velocity = Vector3d(0.05, 0.0, 0.0),
                        yaw = Angle.fromDegrees(90.0f),
                        pitch = Angle.fromDegrees(0.0f),
                        weight = 1.0f,
                    ),
                ),
            ),
            ClientboundTeleportEntityPacket(
                id = vehicleEntityId,
                change = PositionMoveRotation(
                    position = Vector3d(4.75, 65.0, 4.5),
                    deltaMovement = Vector3d(0.05, 0.0, 0.0),
                    yaw = 90.0f,
                    pitch = 0.0f,
                ),
                relatives = RelativeMovements(emptySet()),
                onGround = true,
            ),
            ClientboundPongResponsePacket(time = 1),
            ClientboundForgetLevelChunkPacket(ChunkPos(2, 2)),
            ClientboundPlayerCombatEnterPacket,
            ClientboundPlayerCombatEndPacket(duration = 1),
            ClientboundPlayerInfoUpdatePacket(
                PlayerInfoUpdatePayload(
                    actions = ClientboundPlayerInfoUpdatePacket.Action.entries.toSet(),
                    entries = listOf(
                        ClientboundPlayerInfoUpdatePacket.Entry(
                            profileId = playerListProfileId,
                            profile = PlayerListProfile(
                                name = "E2EProbe",
                                properties = emptyList(),
                            ),
                            chatSession = null,
                            gameMode = GameMode.CREATIVE,
                            listed = true,
                            latency = 1,
                            displayName = TextComponent.literal("Headless E2E"),
                            listOrder = 1,
                            showHat = true,
                        ),
                    ),
                ),
            ),
            ClientboundPlayerChatPacket(
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
            ClientboundPlayerInfoRemovePacket(listOf(playerListProfileId)),
            ClientboundPlayerLookAtPacket(
                fromAnchor = EntityAnchor.EYES,
                target = LookTarget.Entity(
                    fallbackPosition = pigPosition,
                    entityId = pigEntityId,
                    anchor = EntityAnchor.EYES,
                ),
            ),
            ClientboundPlayerRotationPacket(
                yRot = 0.0f,
                relativeY = false,
                xRot = 0.0f,
                relativeX = false,
            ),
            ClientboundRecipeBookRemovePacket(emptyList()),
            ClientboundRecipeBookSettingsPacket(
                RecipeBookSettings(
                    crafting = closedRecipeBook,
                    furnace = closedRecipeBook,
                    blastFurnace = closedRecipeBook,
                    smoker = closedRecipeBook,
                ),
            ),
            ClientboundRemoveEntitiesPacket(emptyList()),
            ClientboundRemoveMobEffectPacket(
                entityId = pigEntityId,
                effect = 0,
            ),
            ClientboundResourcePackPopPacket(id = null),
            ClientboundRotateHeadPacket(
                entityId = pigEntityId,
                yHeadRot = Angle.fromDegrees(75.0f),
            ),
            ClientboundSectionBlocksUpdatePacket(
                sectionPos = SectionPosition(0, 4, 0),
                blocks = emptyList(),
            ),
            ClientboundSelectAdvancementsTabPacket(tab = null),
            ClientboundServerDataPacket(
                motd = TextComponent.literal("minecraft-protocol E2E"),
                iconBytes = null,
            ),
            ClientboundSetActionBarTextPacket(
                TextComponent.literal("Headless client packet probes"),
            ),
            ClientboundSetBorderCenterPacket(newCenterX = 0.0, newCenterZ = 0.0),
            ClientboundSetBorderLerpSizePacket(
                oldSize = 128.0,
                newSize = 96.0,
                lerpTime = 1,
            ),
            ClientboundSetBorderSizePacket(size = 128.0),
            ClientboundSetBorderWarningDelayPacket(warningDelay = 15),
            ClientboundSetBorderWarningDistancePacket(warningBlocks = 5),
            ClientboundSetCameraPacket(cameraId = playerEntityId),
            ClientboundSetEntityDataPacket(
                id = pigEntityId,
                packedItems = EntityMetadata(emptyList()),
            ),
            ClientboundSetEntityLinkPacket(
                sourceId = pigEntityId,
                destId = 0,
            ),
            ClientboundSetPassengersPacket(
                vehicle = vehicleEntityId,
                passengers = listOf(pigEntityId),
            ),
            ClientboundSetPassengersPacket(
                vehicle = vehicleEntityId,
                passengers = emptyList(),
            ),
            ClientboundOpenBookPacket(InteractionHand.MAIN_HAND),
            ClientboundOpenScreenPacket(
                containerId = 1,
                type = genericContainerTypeId,
                title = TextComponent.literal("Headless E2E"),
            ),
            ClientboundContainerSetContentPacket(
                containerId = 1,
                stateId = 0,
                items = List(45) { ItemStack.Empty },
                carriedItem = ItemStack.Empty,
            ),
            ClientboundContainerSetSlotPacket(
                containerId = 1,
                stateId = 1,
                slot = 0,
                itemStack = ItemStack.Empty,
            ),
            ClientboundContainerClosePacket(containerId = 1),
            ClientboundOpenScreenPacket(
                containerId = 2,
                type = merchantContainerTypeId,
                title = TextComponent.literal("Merchant E2E"),
            ),
            ClientboundMerchantOffersPacket(
                containerId = 2,
                offers = emptyList(),
                villagerLevel = 1,
                villagerXp = 0,
                showProgress = false,
                canRestock = false,
            ),
            ClientboundContainerClosePacket(containerId = 2),
            ClientboundMountScreenOpenPacket(
                containerId = 3,
                inventoryColumns = 2,
                entityId = horseEntityId,
            ),
            ClientboundContainerClosePacket(containerId = 3),
            ClientboundOpenScreenPacket(
                containerId = 4,
                type = furnaceContainerTypeId,
                title = TextComponent.literal("Furnace E2E"),
            ),
            ClientboundContainerSetDataPacket(
                containerId = 4,
                id = 0,
                value = 0,
            ),
            ClientboundContainerClosePacket(containerId = 4),
            ClientboundContainerSetSlotPacket(
                containerId = -2,
                stateId = 0,
                slot = 0,
                itemStack = ItemStack.Empty,
            ),
            ClientboundSetCursorItemPacket(ItemStack.Empty),
            ClientboundSetPlayerInventoryPacket(
                slot = 0,
                contents = ItemStack.Empty,
            ),
            ClientboundSetEquipmentPacket(
                entity = pigEntityId,
                slots = EquipmentUpdates(
                    listOf(
                        EquipmentUpdate(
                            slot = EquipmentSlot.MAINHAND,
                            item = ItemStack.Empty,
                        ),
                    ),
                ),
            ),
            ClientboundSoundEntityPacket(
                sound = sound,
                source = SoundSource.NEUTRAL,
                id = pigEntityId,
                volume = 0.1f,
                pitch = 1.0f,
                seed = 1,
            ),
            ClientboundSoundPacket.fromPosition(
                soundEventHolder = sound,
                soundSource = SoundSource.MASTER,
                x = 0.5,
                y = 65.0,
                z = 0.5,
                volume = 0.1f,
                pitch = 1.0f,
                seed = 2,
            ),
            ClientboundStopSoundPacket(StopSound(source = null, sound = null)),
            ClientboundTagQueryPacket(transactionId = 0, tag = null),
            ClientboundTestInstanceBlockStatus(
                status = TextComponent.literal("E2E"),
                size = null,
            ),
            ClientboundTickingStatePacket(
                tickRate = 20.0f,
                isFrozen = false,
            ),
            ClientboundTickingStepPacket(tickSteps = 0),
            ClientboundMapItemDataPacket(
                mapId = 0,
                scale = 0,
                locked = false,
                decorations = null,
                colorPatch = null,
            ),
            ClientboundRecipeBookAddPacket(
                entries = emptyList(),
                replace = false,
            ),
            ClientboundUpdateAdvancementsPacket(
                reset = false,
                added = emptyList(),
                removed = emptySet(),
                progress = emptyMap(),
                showAdvancements = false,
            ),
            ClientboundUpdateRecipesPacket(
                itemSets = emptyMap(),
                stonecutterRecipes = emptyList(),
            ),
            ClientboundUpdateAttributesPacket(
                entityId = pigEntityId,
                attributes = emptyList(),
            ),
            ClientboundProjectilePowerPacket(
                id = projectileEntityId,
                accelerationPower = 1.0,
            ),
            ClientboundUpdateMobEffectPacket(
                entityId = pigEntityId,
                effect = 0,
                effectAmplifier = 0,
                effectDurationTicks = 20,
                flags = MobEffectFlags(0),
            ),
            ClientboundRemoveMobEffectPacket(
                entityId = pigEntityId,
                effect = 0,
            ),
            ClientboundUpdateTagsPacket(
                OPTIONS.configurationData.registryTags,
            ),
            ClientboundSetObjectivePacket(
                objectiveName = "headless-e2e",
                update = ObjectiveUpdate.Add(
                    displayName = TextComponent.literal("Headless E2E"),
                    renderType = ObjectiveRenderType.INTEGER,
                    numberFormat = null,
                ),
            ),
            ClientboundSetObjectivePacket(
                objectiveName = "headless-e2e",
                update = ObjectiveUpdate.Change(
                    displayName = TextComponent.literal("Protocol probes"),
                    renderType = ObjectiveRenderType.INTEGER,
                    numberFormat = NumberFormat.Blank,
                ),
            ),
            ClientboundSetDisplayObjectivePacket(
                slot = DisplaySlot.SIDEBAR,
                objectiveName = "headless-e2e",
            ),
            ClientboundSetScorePacket(
                owner = "E2EProbe",
                objectiveName = "headless-e2e",
                score = 1,
                display = TextComponent.literal("Probe"),
                numberFormat = NumberFormat.Fixed(
                    TextComponent.literal("1"),
                ),
            ),
            ClientboundResetScorePacket(
                owner = "E2EProbe",
                objectiveName = "headless-e2e",
            ),
            ClientboundSetDisplayObjectivePacket(
                slot = DisplaySlot.SIDEBAR,
                objectiveName = "",
            ),
            ClientboundSetObjectivePacket(
                objectiveName = "headless-e2e",
                update = ObjectiveUpdate.Remove,
            ),
            ClientboundSetPlayerTeamPacket(
                name = "headless-e2e",
                update = TeamUpdate.Add(
                    parameters = ClientboundSetPlayerTeamPacket.Parameters(
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
            ClientboundSetPlayerTeamPacket(
                name = "headless-e2e",
                update = TeamUpdate.Change(
                    ClientboundSetPlayerTeamPacket.Parameters(
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
            ClientboundSetPlayerTeamPacket(
                name = "headless-e2e",
                update = TeamUpdate.Join(listOf("SecondProbe")),
            ),
            ClientboundSetPlayerTeamPacket(
                name = "headless-e2e",
                update = TeamUpdate.Leave(listOf("SecondProbe")),
            ),
            ClientboundSetPlayerTeamPacket(
                name = "headless-e2e",
                update = TeamUpdate.Remove,
            ),
            ClientboundTrackedWaypointPacket(
                operation = ClientboundTrackedWaypointPacket.Operation.TRACK,
                waypoint = TrackedWaypoint.Position(
                    identifier = waypointId,
                    icon = waypointIcon,
                    x = 0,
                    y = 65,
                    z = 0,
                ),
            ),
            ClientboundTrackedWaypointPacket(
                operation = ClientboundTrackedWaypointPacket.Operation.UPDATE,
                waypoint = TrackedWaypoint.Position(
                    identifier = waypointId,
                    icon = waypointIcon,
                    x = 1,
                    y = 65,
                    z = 1,
                ),
            ),
            ClientboundTrackedWaypointPacket(
                operation = ClientboundTrackedWaypointPacket.Operation.UNTRACK,
                waypoint = TrackedWaypoint.Empty(
                    identifier = waypointId,
                    icon = waypointIcon,
                ),
            ),
            ClientboundRemoveEntitiesPacket(
                listOf(projectileEntityId, vehicleEntityId),
            ),
        )
        packets.forEachIndexed { index, packet ->
            minecraftServerConnection.outgoing.send(packet)
            awaitPlayBarrier(
                minecraftServerConnection = minecraftServerConnection,
                label = packet::class.simpleName ?: "clientbound packet $index",
                pingId = PLAY_PING_ID + index,
                observed = observed,
            )
        }

        minecraftServerConnection.outgoing.send(
            ClientboundStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        minecraftServerConnection.outgoing.send(ClientboundCookieRequestPacket(COOKIE_KEY))
        var cookieRoundTrip = false
        awaitPlayBarrier(
            minecraftServerConnection = minecraftServerConnection,
            label = "Play cookie store/request",
            pingId = PLAY_PING_ID + packets.size,
            observed = observed,
            additionalComplete = { cookieRoundTrip },
            onPacket = { packet ->
                if (
                    packet is ServerboundCookieResponsePacket &&
                    packet.key == COOKIE_KEY
                ) {
                    check(packet.payload == COOKIE_PAYLOAD) {
                        "Official client returned the wrong Play cookie"
                    }
                    cookieRoundTrip = true
                }
            },
        )

        minecraftServerConnection.outgoing.send(
            ClientboundPlayerPositionPacket(
                id = nextTeleportId,
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
            minecraftServerConnection = minecraftServerConnection,
            label = "second player-position synchronization",
            pingId = PLAY_PING_ID + packets.size + 1,
            observed = observed,
            additionalComplete = { teleportAcknowledged },
            onPacket = { packet ->
                if (
                    packet is ServerboundAcceptTeleportationPacket &&
                    packet.id == nextTeleportId
                ) {
                    teleportAcknowledged = true
                }
            },
        )
        check(playerEntityId != pigEntityId) {
            "Play probe entity unexpectedly reused the player entity ID"
        }
    }

    private suspend fun exerciseRespawn(
        minecraftServerConnection: MinecraftServerConnection,
        clientboundLoginPacket: ClientboundLoginPacket,
        minecraftInitialWorld: MinecraftInitialWorld,
        observed: MutableList<String>,
    ) = coroutineScope {
        // Send a finite world batch while the parent consumes acknowledgements and ordinary client ticks.
        launch {
            minecraftServerConnection.outgoing.send(
                ClientboundRespawnPacket(
                    commonPlayerSpawnInfo = clientboundLoginPacket.commonPlayerSpawnInfo,
                    dataToKeep = ClientboundRespawnPacket.KEEP_ALL_DATA.toByte(),
                ),
            )
            minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
            minecraftServerConnection.outgoing.send(ClientboundPingPacket(RESPAWN_PING_ID))
            minecraftServerConnection.requestFlush()
        }

        var ping = false
        var tick = false
        var teleport = false
        var chunkBatch = false
        var playerLoaded = false
        var packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (
            packetBudget-- > 0 &&
            !(
                    ping &&
                            tick &&
                            teleport &&
                            chunkBatch &&
                            playerLoaded
                    )
        ) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for post-Respawn Play probes",
            )
            observed += packet::class.simpleName ?: "<anonymous>"
            when (packet) {
                is ServerboundPongPacket ->
                    if (packet.id == RESPAWN_PING_ID) ping = true

                is ServerboundAcceptTeleportationPacket ->
                    if (packet.id == minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId) {
                        teleport = true
                    }

                is ServerboundChunkBatchReceivedPacket -> chunkBatch = true
                ServerboundPlayerLoadedPacket -> playerLoaded = true
                is ServerboundClientTickEndPacket -> tick = true
                else -> Unit
            }
        }
        val respawnState = listOf(
            "ping=$ping",
            "tick=$tick",
            "teleport=$teleport",
            "chunk=$chunkBatch",
            "loaded=$playerLoaded",
        ).joinToString()
        check(
            ping &&
                    tick &&
                    teleport &&
                    chunkBatch &&
                    playerLoaded,
        ) {
            "Respawn incomplete: $respawnState"
        }
    }

    private suspend fun awaitPlayBarrier(
        minecraftServerConnection: MinecraftServerConnection,
        label: String,
        pingId: Int,
        observed: MutableList<String>,
        additionalComplete: () -> Boolean = { true },
        onPacket: (Packet) -> Unit = {},
    ) {
        minecraftServerConnection.outgoing.send(ClientboundPingPacket(pingId))
        minecraftServerConnection.requestFlush()
        var pingRoundTrip = false
        var tickObserved = false
        var packetBudget = MAXIMUM_PACKETS_PER_STAGE
        fun barrierState(): String = listOf(
            "ping=$pingRoundTrip",
            "tick=$tickObserved",
            "additional=${additionalComplete()}",
        ).joinToString()
        try {
            while (
                packetBudget-- > 0 &&
                !(
                        pingRoundTrip &&
                                tickObserved &&
                                additionalComplete()
                        )
            ) {
                val packet = receiveForStage(
                    minecraftServerConnection,
                    "processing the $label barrier",
                )
                observed += packet::class.simpleName ?: "<anonymous>"
                when (packet) {
                    is ServerboundPongPacket ->
                        if (packet.id == pingId) pingRoundTrip = true

                    is ServerboundClientTickEndPacket -> tickObserved = true
                    else -> Unit
                }
                onPacket(packet)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw AssertionError(
                "Official client failed during $label: ${barrierState()}",
                failure,
            )
        }
        check(
            pingRoundTrip &&
                    tickObserved &&
                    additionalComplete(),
        ) {
            "Barrier $label incomplete: ${barrierState()}"
        }
    }

    private suspend fun exerciseReconfiguration(
        minecraftServerConnection: MinecraftServerConnection,
        clientboundLoginPacket: ClientboundLoginPacket,
        minecraftInitialWorld: MinecraftInitialWorld,
        observedPlayPackets: MutableList<String>,
    ) = coroutineScope {
        var playerLoaded = observedPlayPackets.any { it == "ServerboundPlayerLoadedPacket" }
        if (!playerLoaded) {
            awaitPlayBarrier(
                minecraftServerConnection = minecraftServerConnection,
                label = "Player Loaded readiness",
                pingId = PRE_CONFIGURATION_PING_ID,
                observed = observedPlayPackets,
                additionalComplete = { playerLoaded },
                onPacket = { packet ->
                    if (packet == ServerboundPlayerLoadedPacket) playerLoaded = true
                },
            )
        }
        minecraftServerConnection.outgoing.send(ClientboundStartConfigurationPacket)
        var acknowledged = false
        var packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (packetBudget-- > 0 && !acknowledged) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for the Play reconfiguration acknowledgement",
            )
            observedPlayPackets +=
                packet::class.simpleName ?: "<anonymous>"
            acknowledged = packet == ServerboundConfigurationAcknowledgedPacket
        }
        check(acknowledged) {
            "Official client did not acknowledge reconfiguration"
        }
        check(minecraftServerConnection.connectionState == ConnectionState.CONFIGURATION) {
            "Server session did not enter Configuration after acknowledgement"
        }
        minecraftServerConnection.disableKeepAlive()
        val configurationKeepAlive = minecraftServerConnection.enableRecordingConfigurationKeepAlive(5.seconds)
        configurationKeepAlive.requestCreated.await()

        minecraftServerConnection.outgoing.send(
            ClientboundStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        minecraftServerConnection.outgoing.send(ClientboundCookieRequestPacket(COOKIE_KEY))
        minecraftServerConnection.outgoing.send(
            ClientboundPingPacket(CONFIGURATION_PING_ID),
        )
        minecraftServerConnection.outgoing.send(
            ClientboundCustomPayloadPacket(
                CustomPayload.Brand("minecraft-protocol"),
            ),
        )
        minecraftServerConnection.outgoing.send(ClientboundResourcePackPopPacket(null))
        minecraftServerConnection.outgoing.send(ClientboundResetChatPacket)
        minecraftServerConnection.outgoing.send(
            ClientboundCustomReportDetailsPacket(
                listOf(
                    ReportDetail(
                        title = "E2E",
                        description = "reconfiguration",
                    ),
                ),
            ),
        )
        minecraftServerConnection.outgoing.send(ClientboundServerLinksPacket(emptyList()))
        minecraftServerConnection.outgoing.send(ClientboundClearDialogPacket)
        minecraftServerConnection.outgoing.send(
            ClientboundUpdateEnabledFeaturesPacket(OPTIONS.configurationData.enabledFeatureFlags),
        )
        minecraftServerConnection.outgoing.send(
            ClientboundSelectKnownPacks(
                OPTIONS.configurationData.offeredKnownPacks,
            ),
        )

        var cookieRoundTrip = false
        var pingRoundTrip = false
        var serverboundSelectKnownPacks: ServerboundSelectKnownPacks? = null
        packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (
            packetBudget-- > 0 &&
            !(
                    cookieRoundTrip &&
                            pingRoundTrip &&
                            serverboundSelectKnownPacks != null
                    )
        ) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for Configuration cookie/keepalive/ping/Known Packs",
            )
            when (packet) {
                is ServerboundCookieResponsePacket ->
                    if (packet.key == COOKIE_KEY) {
                        check(packet.payload == COOKIE_PAYLOAD) {
                            "Official client returned the wrong Configuration cookie"
                        }
                        cookieRoundTrip = true
                    }

                is ServerboundPongPacket ->
                    if (packet.id == CONFIGURATION_PING_ID) {
                        pingRoundTrip = true
                    }

                is ServerboundSelectKnownPacks ->
                    serverboundSelectKnownPacks = packet

                else -> Unit
            }
        }
        val configurationState = listOf(
            "cookie=$cookieRoundTrip",
            "ping=$pingRoundTrip",
            "knownPacks=${serverboundSelectKnownPacks != null}",
        ).joinToString()
        check(
            cookieRoundTrip &&
                    pingRoundTrip &&
                    serverboundSelectKnownPacks != null,
        ) {
            "Configuration probes incomplete: $configurationState"
        }
        configurationKeepAlive.roundTrip.await()
        val acceptedKnownPacks = serverboundSelectKnownPacks.knownPacks
        OPTIONS.configurationData
            .synchronizedRegistryPackets(acceptedKnownPacks)
            .forEach { clientboundRegistryDataPacket ->
                minecraftServerConnection.outgoing.send(
                    clientboundRegistryDataPacket
                )
            }
        minecraftServerConnection.outgoing.send(
            ClientboundUpdateTagsPacket(OPTIONS.configurationData.registryTags),
        )
        minecraftServerConnection.outgoing.send(ClientboundFinishConfigurationPacket)

        var completed = false
        packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (packetBudget-- > 0 && !completed) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for Finish Configuration acknowledgement",
            )
            completed = packet == ServerboundFinishConfigurationPacket
        }
        check(completed) {
            "Official client did not finish reconfiguration"
        }
        check(minecraftServerConnection.connectionState == ConnectionState.PLAY) {
            "Server session did not return to Play after reconfiguration"
        }
        minecraftServerConnection.disableKeepAlive()
        val playKeepAlive = minecraftServerConnection.enableRecordingPlayKeepAlive(5.seconds)
        playKeepAlive.requestCreated.await()

        val reconfiguredWorld = minecraftInitialWorld.copy(
            minecraftInitialWorldBootstrap = minecraftInitialWorld.minecraftInitialWorldBootstrap.copy(
                teleportId = minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId + 3,
            ),
        )
        launch {
            minecraftServerConnection.outgoing.send(clientboundLoginPacket)
            minecraftServerConnection.synchronizeInitialWorld(reconfiguredWorld)
            minecraftServerConnection.outgoing.send(ClientboundPingPacket(POST_CONFIGURATION_PING_ID))
            minecraftServerConnection.requestFlush()
        }
        var postPing = false
        var postTick = false
        var postTeleport = false
        var postChunkBatch = false
        var postPlayerLoaded = false
        packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (
            packetBudget-- > 0 &&
            !(
                    postPing &&
                            postTick &&
                            postTeleport &&
                            postChunkBatch &&
                            postPlayerLoaded
                    )
        ) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for post-Configuration Play probes",
            )
            observedPlayPackets +=
                packet::class.simpleName ?: "<anonymous>"
            when (packet) {
                is ServerboundPongPacket ->
                    if (packet.id == POST_CONFIGURATION_PING_ID) {
                        postPing = true
                    }

                is ServerboundAcceptTeleportationPacket ->
                    if (packet.id == reconfiguredWorld.minecraftInitialWorldBootstrap.teleportId) {
                        postTeleport = true
                    }

                is ServerboundChunkBatchReceivedPacket ->
                    postChunkBatch = true

                ServerboundPlayerLoadedPacket -> postPlayerLoaded = true
                is ServerboundClientTickEndPacket -> postTick = true
                else -> Unit
            }
        }
        val postConfigurationState = listOf(
            "ping=$postPing",
            "tick=$postTick",
            "teleport=$postTeleport",
            "chunk=$postChunkBatch",
            "loaded=$postPlayerLoaded",
        ).joinToString()
        check(
            postPing &&
                    postTick &&
                    postTeleport &&
                    postChunkBatch &&
                    postPlayerLoaded,
        ) {
            "Play did not resume: $postConfigurationState"
        }
        playKeepAlive.roundTrip.await()
    }

    private suspend fun receiveForStage(
        minecraftServerConnection: MinecraftServerConnection,
        stage: String,
    ): Packet {
        minecraftServerConnection.requestFlush()
        val deadlineResult = try {
            awaitExternal(PROTOCOL_STAGE_TIMEOUT) {
                minecraftServerConnection.incoming.receive()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "Official client disconnected while $stage (server state ${minecraftServerConnection.connectionState})",
                failure,
            )
        }
        return when (deadlineResult) {
            is DeadlineResult.Completed -> deadlineResult.value
            DeadlineResult.TimedOut -> error(
                "No client packet in $PROTOCOL_STAGE_TIMEOUT: $stage; state=${minecraftServerConnection.connectionState}",
            )
        }
    }

    private suspend fun <T> awaitExternal(
        timeout: Duration,
        block: suspend () -> T,
    ): DeadlineResult<T> = withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeout) {
            DeadlineResult.Completed(block())
        } ?: DeadlineResult.TimedOut
    }

    private suspend fun runProtocolStage(
        label: String,
        block: suspend () -> Unit,
    ) {
        try {
            when (awaitExternal(PROTOCOL_STAGE_TIMEOUT, block)) {
                is DeadlineResult.Completed -> Unit
                DeadlineResult.TimedOut -> error("$label exceeded $PROTOCOL_STAGE_TIMEOUT")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw AssertionError("$label failed: $failure", failure)
        }
    }

    private fun HeadlessMinecraftClientState.isTerminalConnectionScreen(): Boolean =
        screenClassName == TITLE_SCREEN_CLASS ||
                screenClassName?.endsWith(".DisconnectedScreen") == true

    private fun HeadlessMinecraftClientState.description(): String =
        screenClassName ?: "no displayed GUI"

    private data class ConnectedOfficialClient(
        val minecraftServer: MinecraftServer,
        val minecraftServerConnection: MinecraftServerConnection,
    )

    private sealed interface DeadlineResult<out T> {
        data class Completed<T>(val value: T) : DeadlineResult<T>

        data object TimedOut : DeadlineResult<Nothing>
    }

    private const val MAXIMUM_CONNECTION_ATTEMPTS = 3
    private const val LOOPBACK = "127.0.0.1"
    private const val TITLE_SCREEN_CLASS = "net.minecraft.client.gui.screens.TitleScreen"
    private val CONNECTION_ATTEMPT_TIMEOUT = 15.seconds
    private val PROTOCOL_STAGE_TIMEOUT = 30.seconds
}
