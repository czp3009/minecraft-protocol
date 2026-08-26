package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaDataPacks
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaRegistryData
import com.hiczp.minecraft.protocol.datapack.vanilla.toVanillaProtocolData
import com.hiczp.minecraft.protocol.datapack.vanilla.vanillaDataPackRegistryProjectors
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.test.*
import com.hiczp.minecraft.world.format.datapack.DataPack
import com.hiczp.minecraft.world.format.datapack.DataPackId
import com.hiczp.minecraft.world.format.datapack.DataPackStack
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
        protocolData = projectedVanillaProtocolData(),
        compressionThreshold = 64,
        viewDistance = 2,
        simulationDistance = 5,
        statusDescription = "minecraft-protocol official client E2E",
    )

    /** Exercises every release-matched default disk-JSON to network-NBT registry projector. */
    private fun projectedVanillaProtocolData() = VanillaDataPacks.coreDataPackStack.resolve(
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
        DataPackStack(projectedDataPack).toVanillaProtocolData()
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
                |Official client -> production initial-world E2E failed.
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
        val details = failures.joinToString(separator = "\n- ", prefix = "- ")
        error("Official client made no TCP connection after $MAXIMUM_CONNECTION_ATTEMPTS attempts:\n$details")
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
    ) {
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
        val recordingKeepAlive = minecraftServerConnection.enableRecordingPlayKeepAlive(5.seconds)
        try {
            val pig = MinecraftEntitySnapshot(
                entityId = 2,
                uuid = Uuid.fromLongs(0, 2),
                type = Identifier("pig"),
                position = Vector3d(3.5, 65.0, 3.5),
            )
            val arrow = MinecraftEntitySnapshot(
                entityId = 3,
                uuid = Uuid.fromLongs(0, 3),
                type = Identifier("arrow"),
                position = Vector3d(2.5, 66.0, 2.5),
                velocity = Vector3d(0.05, 0.0, 0.0),
            )
            val minecart = MinecraftEntitySnapshot(
                entityId = 4,
                uuid = Uuid.fromLongs(0, 4),
                type = Identifier("minecart"),
                position = Vector3d(4.5, 65.0, 4.5),
            )
            val horse = MinecraftEntitySnapshot(
                entityId = 5,
                uuid = Uuid.fromLongs(0, 5),
                type = Identifier("horse"),
                position = Vector3d(5.5, 65.0, 5.5),
            )
            val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
                minecraftServerNegotiationOptions = OPTIONS,
                entities = listOf(pig, arrow, minecart, horse),
            )
            runProtocolStage("managed Play KeepAlive request") {
                recordingKeepAlive.requestCreated.await()
            }
            runProtocolStage("initial-world synchronization") {
                minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
                minecraftServerConnection.requestFlush()
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
                    is ConfirmTeleportationPacket ->
                        teleportAcknowledged = packet.teleportId == minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId

                    is ChunkBatchReceivedPacket -> chunkBatchAcknowledged = true

                    is ClientTickEndPacket -> clientTickObserved = true

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
            runProtocolStage("managed Play KeepAlive round trip") {
                recordingKeepAlive.roundTrip.await()
            }

            runProtocolStage("Play packet coverage") {
                exercisePlayPackets(
                    minecraftServerConnection = minecraftServerConnection,
                    playerEntityId = ready.playLoginPacket.playerId,
                    minecraftEntitySnapshot = pig,
                    projectile = arrow,
                    vehicle = minecart,
                    horse = horse,
                    nextTeleportId = minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId + 1,
                    observed = observed,
                )
            }
            runProtocolStage("Respawn coverage") {
                exerciseRespawn(
                    minecraftServerConnection = minecraftServerConnection,
                    playLoginPacket = ready.playLoginPacket,
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
                    playLoginPacket = ready.playLoginPacket,
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
        minecraftEntitySnapshot: MinecraftEntitySnapshot,
        projectile: MinecraftEntitySnapshot,
        vehicle: MinecraftEntitySnapshot,
        horse: MinecraftEntitySnapshot,
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
                entityId = minecraftEntitySnapshot.entityId,
                velocity = Vector3d(0.01, 0.0, -0.01),
            ),
            UpdateEntityPositionPacket(
                entityId = minecraftEntitySnapshot.entityId,
                deltaX = 64,
                deltaY = 0,
                deltaZ = -64,
                onGround = true,
            ),
            TeleportEntityPacket(
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
                animationId = 0,
            ),
            AwardStatisticsPacket(emptyList()),
            AcknowledgeBlockChangePacket(sequenceId = 0),
            SetBlockDestroyStagePacket(
                entityId = minecraftEntitySnapshot.entityId,
                location = BlockPosition(0, 64, 0),
                destroyStage = 0,
            ),
            SetBlockDestroyStagePacket(
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
                eventId = 2,
            ),
            ExplosionPacket(
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
            GameTestHighlightPositionPacket(
                absolutePosition = BlockPosition(0, 65, 0),
                relativePosition = BlockPosition(0, 0, 0),
            ),
            HurtAnimationPacket(
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
                deltaX = 16,
                deltaY = 0,
                deltaZ = 16,
                yaw = Angle.fromDegrees(45.0f),
                pitch = Angle.fromDegrees(5.0f),
                onGround = true,
            ),
            UpdateEntityRotationPacket(
                entityId = minecraftEntitySnapshot.entityId,
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
                    fallbackPosition = minecraftEntitySnapshot.position,
                    entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
                effectTypeId = 0,
            ),
            PlayRemoveResourcePackPacket(id = null),
            SetHeadRotationPacket(
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
                metadata = EntityMetadata(emptyList()),
            ),
            LinkEntitiesPacket(
                attachedEntityId = minecraftEntitySnapshot.entityId,
                holdingEntityId = 0,
            ),
            SetPassengersPacket(
                vehicleEntityId = vehicle.entityId,
                passengerEntityIds = listOf(minecraftEntitySnapshot.entityId),
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
                entityId = minecraftEntitySnapshot.entityId,
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
                entityId = minecraftEntitySnapshot.entityId,
                volume = 0.1f,
                pitch = 1.0f,
                seed = 1,
            ),
            SoundEffectPacket.fromPosition(
                soundEventHolder = sound,
                soundSource = SoundSource.MASTER,
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
                entityId = minecraftEntitySnapshot.entityId,
                attributes = emptyList(),
            ),
            ProjectilePowerPacket(
                entityId = projectile.entityId,
                power = 1.0,
            ),
            EntityEffectPacket(
                entityId = minecraftEntitySnapshot.entityId,
                effectTypeId = 0,
                amplifier = 0,
                durationTicks = 20,
                flags = MobEffectFlags(0),
            ),
            RemoveEntityEffectPacket(
                entityId = minecraftEntitySnapshot.entityId,
                effectTypeId = 0,
            ),
            PlayUpdateTagsPacket(
                OPTIONS.protocolData.registryTags.associate { registryTags ->
                    registryTags.registry to registryTags.tags
                },
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
                waypoint = TrackedWaypoint.Position(
                    identifier = waypointId,
                    icon = waypointIcon,
                    x = 1,
                    y = 65,
                    z = 1,
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
            minecraftServerConnection.outgoing.send(packet)
            awaitPlayBarrier(
                minecraftServerConnection = minecraftServerConnection,
                label = packet::class.simpleName ?: "clientbound packet $index",
                pingId = PLAY_PING_ID + index,
                observed = observed,
            )
        }

        minecraftServerConnection.outgoing.send(
            PlayStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        minecraftServerConnection.outgoing.send(PlayCookieRequestPacket(COOKIE_KEY))
        var cookieRoundTrip = false
        awaitPlayBarrier(
            minecraftServerConnection = minecraftServerConnection,
            label = "Play cookie store/request",
            pingId = PLAY_PING_ID + packets.size,
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

        minecraftServerConnection.outgoing.send(
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
            minecraftServerConnection = minecraftServerConnection,
            label = "second player-position synchronization",
            pingId = PLAY_PING_ID + packets.size + 1,
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
        check(playerEntityId != minecraftEntitySnapshot.entityId) {
            "Play probe entity unexpectedly reused the player entity ID"
        }
    }

    private suspend fun exerciseRespawn(
        minecraftServerConnection: MinecraftServerConnection,
        playLoginPacket: PlayLoginPacket,
        minecraftInitialWorld: MinecraftInitialWorld,
        observed: MutableList<String>,
    ) {
        minecraftServerConnection.outgoing.send(
            RespawnPacket(
                spawnInfo = playLoginPacket.spawnInfo,
                dataToKeep = RespawnPacket.KEEP_ALL_DATA.toByte(),
            ),
        )
        minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
        minecraftServerConnection.outgoing.send(ClientboundPingPacket(RESPAWN_PING_ID))
        minecraftServerConnection.requestFlush()

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
                is PlayPongPacket ->
                    if (packet.id == RESPAWN_PING_ID) ping = true

                is ConfirmTeleportationPacket ->
                    if (packet.teleportId == minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId) {
                        teleport = true
                    }

                is ChunkBatchReceivedPacket -> chunkBatch = true
                PlayerLoadedPacket -> playerLoaded = true
                is ClientTickEndPacket -> tick = true
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
                    is PlayPongPacket ->
                        if (packet.id == pingId) pingRoundTrip = true

                    is ClientTickEndPacket -> tickObserved = true
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
        playLoginPacket: PlayLoginPacket,
        minecraftInitialWorld: MinecraftInitialWorld,
        observedPlayPackets: MutableList<String>,
    ) {
        var playerLoaded = observedPlayPackets.any { it == "PlayerLoadedPacket" }
        if (!playerLoaded) {
            awaitPlayBarrier(
                minecraftServerConnection = minecraftServerConnection,
                label = "Player Loaded readiness",
                pingId = PRE_CONFIGURATION_PING_ID,
                observed = observedPlayPackets,
                additionalComplete = { playerLoaded },
                onPacket = { packet ->
                    if (packet == PlayerLoadedPacket) playerLoaded = true
                },
            )
        }
        minecraftServerConnection.outgoing.send(StartConfigurationPacket)
        var acknowledged = false
        var packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (packetBudget-- > 0 && !acknowledged) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for the Play reconfiguration acknowledgement",
            )
            observedPlayPackets +=
                packet::class.simpleName ?: "<anonymous>"
            acknowledged = packet == AcknowledgeConfigurationPacket
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
            ConfigurationStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        minecraftServerConnection.outgoing.send(ConfigurationCookieRequestPacket(COOKIE_KEY))
        minecraftServerConnection.outgoing.send(
            ConfigurationPingPacket(CONFIGURATION_PING_ID),
        )
        minecraftServerConnection.outgoing.send(
            ConfigurationClientboundPluginMessagePacket(
                CustomPayload.Brand("minecraft-protocol"),
            ),
        )
        minecraftServerConnection.outgoing.send(ConfigurationRemoveResourcePackPacket(null))
        minecraftServerConnection.outgoing.send(ResetChatPacket)
        minecraftServerConnection.outgoing.send(
            ConfigurationCustomReportDetailsPacket(
                listOf(
                    ReportDetail(
                        title = "E2E",
                        description = "reconfiguration",
                    ),
                ),
            ),
        )
        minecraftServerConnection.outgoing.send(ConfigurationServerLinksPacket(emptyList()))
        minecraftServerConnection.outgoing.send(ConfigurationClearDialogPacket)
        minecraftServerConnection.outgoing.send(
            FeatureFlagsPacket(OPTIONS.protocolData.enabledFeatureFlags),
        )
        minecraftServerConnection.outgoing.send(
            ConfigurationClientboundKnownPacksPacket(
                OPTIONS.protocolData.offeredKnownPacks,
            ),
        )

        var cookieRoundTrip = false
        var pingRoundTrip = false
        var configurationServerboundKnownPacksPacket: ConfigurationServerboundKnownPacksPacket? = null
        packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (
            packetBudget-- > 0 &&
            !(
                    cookieRoundTrip &&
                            pingRoundTrip &&
                            configurationServerboundKnownPacksPacket != null
                    )
        ) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for Configuration cookie/keepalive/ping/Known Packs",
            )
            when (packet) {
                is ConfigurationCookieResponsePacket ->
                    if (packet.key == COOKIE_KEY) {
                        check(packet.payload == COOKIE_PAYLOAD) {
                            "Official client returned the wrong Configuration cookie"
                        }
                        cookieRoundTrip = true
                    }

                is ConfigurationPongPacket ->
                    if (packet.id == CONFIGURATION_PING_ID) {
                        pingRoundTrip = true
                    }

                is ConfigurationServerboundKnownPacksPacket ->
                    configurationServerboundKnownPacksPacket = packet

                else -> Unit
            }
        }
        val configurationState = listOf(
            "cookie=$cookieRoundTrip",
            "ping=$pingRoundTrip",
            "knownPacks=${configurationServerboundKnownPacksPacket != null}",
        ).joinToString()
        check(
            cookieRoundTrip &&
                    pingRoundTrip &&
                    configurationServerboundKnownPacksPacket != null,
        ) {
            "Configuration probes incomplete: $configurationState"
        }
        configurationKeepAlive.roundTrip.await()
        val acceptedKnownPacks = configurationServerboundKnownPacksPacket.knownPacks
        OPTIONS.protocolData
            .synchronizedRegistryPackets(acceptedKnownPacks)
            .forEach { registryDataPacket -> minecraftServerConnection.outgoing.send(registryDataPacket) }
        minecraftServerConnection.outgoing.send(
            ConfigurationUpdateTagsPacket(OPTIONS.protocolData.registryTags),
        )
        minecraftServerConnection.outgoing.send(FinishConfigurationPacket)

        var completed = false
        packetBudget = MAXIMUM_PACKETS_PER_STAGE
        while (packetBudget-- > 0 && !completed) {
            val packet = receiveForStage(
                minecraftServerConnection,
                "waiting for Finish Configuration acknowledgement",
            )
            completed = packet == AcknowledgeFinishConfigurationPacket
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

        minecraftServerConnection.outgoing.send(playLoginPacket)
        val reconfiguredWorld = minecraftInitialWorld.copy(
            minecraftInitialWorldBootstrap = minecraftInitialWorld.minecraftInitialWorldBootstrap.copy(
                teleportId = minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId + 3,
            ),
        )
        minecraftServerConnection.synchronizeInitialWorld(reconfiguredWorld)
        minecraftServerConnection.outgoing.send(
            ClientboundPingPacket(POST_CONFIGURATION_PING_ID),
        )
        minecraftServerConnection.requestFlush()
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
                is PlayPongPacket ->
                    if (packet.id == POST_CONFIGURATION_PING_ID) {
                        postPing = true
                    }

                is ConfirmTeleportationPacket ->
                    if (packet.teleportId == reconfiguredWorld.minecraftInitialWorldBootstrap.teleportId) {
                        postTeleport = true
                    }

                is ChunkBatchReceivedPacket ->
                    postChunkBatch = true

                PlayerLoadedPacket -> postPlayerLoaded = true
                is ClientTickEndPacket -> postTick = true
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
        when (awaitExternal(PROTOCOL_STAGE_TIMEOUT, block)) {
            is DeadlineResult.Completed -> Unit
            DeadlineResult.TimedOut -> error("$label exceeded $PROTOCOL_STAGE_TIMEOUT")
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
