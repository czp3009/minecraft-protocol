package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.data.VanillaStaticData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.test.HeadlessMinecraftClient
import com.hiczp.minecraft.test.HeadlessMinecraftClientConfiguration
import com.hiczp.minecraft.test.MinecraftTestEndpoint
import com.hiczp.minecraft.test.MinecraftTestSupport
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Black-box interoperability runner for the matching official client through
 * HeadlessMC's LWJGL stubs. No display server or GUI path is involved.
 */
internal object HeadlessClientEndToEndRunner {
    private const val PLAYER_NAME = "KmpE2EClient"
    private const val INITIAL_KEEP_ALIVE_ID = 0x1020_3040_5060_7080L
    private const val PLAY_PROBE_KEEP_ALIVE_ID = 0x1122_3344_5566_7788L
    private const val CONFIGURATION_KEEP_ALIVE_ID = 0x1234_5678_1020_3040L
    private const val POST_CONFIGURATION_KEEP_ALIVE_ID = 0x2233_4455_6677_0102L
    private const val PLAY_PING_ID = 0x1020_3040
    private const val CONFIGURATION_PING_ID = 0x5060_7080
    private const val PRE_CONFIGURATION_PING_ID = 0x5566_7788
    private const val PRE_CONFIGURATION_KEEP_ALIVE_ID = 0x3344_5566_7788_0102L
    private const val POST_CONFIGURATION_PING_ID = 0x1122_3344
    private const val RESPAWN_PING_ID = 0x2435_4657
    private const val RESPAWN_KEEP_ALIVE_ID = 0x3141_5926_5358_9793L
    private val COOKIE_KEY = Identifier("minecraft-protocol:e2e")
    private val COOKIE_PAYLOAD = ByteString(
        "official-client-cookie".encodeToByteArray(),
    )
    private val OPTIONS = MinecraftServerNegotiationOptions(
        compressionThreshold = 64,
        viewDistance = 2,
        simulationDistance = 5,
        statusDescription = "minecraft-protocol official client E2E",
    )

    suspend fun run() {
        var client: HeadlessMinecraftClient? = null
        try {
            SelectorManager(Dispatchers.Default).use { selector ->
                MinecraftServer.bind(
                    selectorManager = selector,
                    host = "127.0.0.1",
                    port = 0,
                ).use { server ->
                    val launched = MinecraftTestSupport.newHeadlessClient(
                        configuration =
                            HeadlessMinecraftClientConfiguration(
                                playerName = PLAYER_NAME,
                            ),
                    )
                    client = launched
                    MinecraftTestSupport.connectHeadlessClient(
                        client = launched,
                        endpoint = MinecraftTestEndpoint(
                            host = "127.0.0.1",
                            port = server.port,
                        ),
                    )
                    awaitPlayRoundTrip(server, launched)
                }
            }
        } catch (failure: Throwable) {
            val clientLog = client
                ?.let { MinecraftTestSupport.logText(it) }
                .orEmpty()
            throw AssertionError(
                """
                |Official client -> production initial-world E2E failed.
                |--- official client log ---
                |$clientLog
                """.trimMargin(),
                failure,
            )
        } finally {
            client?.let { MinecraftTestSupport.close(it) }
        }
    }

    private suspend fun awaitPlayRoundTrip(
        server: MinecraftServer,
        client: HeadlessMinecraftClient,
    ) = coroutineScope {
        val clientWatcher = launch {
            MinecraftTestSupport.awaitExit(client)
            server.close()
        }
        try {
            var completed = false
            while (!completed) {
                if (!MinecraftTestSupport.isAlive(client)) {
                    error(
                        "Official client exited with ${MinecraftTestSupport.exitCode(client)}",
                    )
                }
                val connection = try {
                    server.accept()
                } catch (failure: Throwable) {
                    if (!MinecraftTestSupport.isAlive(client)) {
                        error(
                            "Official client exited with ${MinecraftTestSupport.exitCode(client)}",
                        )
                    }
                    throw failure
                }
                try {
                    val ready = connection.negotiate(options = OPTIONS) ?: continue
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
                    val world = MinecraftInitialWorld.flatVanilla(
                        options = OPTIONS,
                        entities = listOf(pig, arrow, minecart, horse),
                    )
                    val synchronization = connection.synchronizeInitialWorld(world)
                    connection.outgoing.send(
                        PlayClientboundKeepAlivePacket(
                            INITIAL_KEEP_ALIVE_ID,
                        ),
                    )
                    val observed = mutableListOf<String>()
                    var teleportAcknowledged = false
                    var chunkBatchAcknowledged = false
                    var keepAliveAcknowledged = false
                    var clientTickObserved = false
                    var initialPacketBudget = OPTIONS.maximumPacketsPerPhase
                    while (
                        initialPacketBudget-- > 0 &&
                        !(
                                teleportAcknowledged &&
                                        chunkBatchAcknowledged &&
                                        keepAliveAcknowledged &&
                                        clientTickObserved
                                )
                    ) {
                        val packet = connection.incoming.receive()
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
                        "Client did not complete initial-world acknowledgements; teleport=$teleportAcknowledged, chunkBatch=$chunkBatchAcknowledged, keepAlive=$keepAliveAcknowledged, clientTick=$clientTickObserved; observed ${observed.joinToString()}"
                    }

                    exercisePlayPackets(
                        connection = connection,
                        playerEntityId = ready.login.playerId,
                        entity = pig,
                        projectile = arrow,
                        vehicle = minecart,
                        horse = horse,
                        nextTeleportId = synchronization.teleportId + 1,
                        observed = observed,
                    )
                    exerciseRespawn(
                        connection = connection,
                        world = world.copy(
                            teleportId = world.teleportId + 2,
                        ),
                        observed = observed,
                    )
                    exerciseReconfiguration(
                        connection = connection,
                        world = world,
                        observedPlayPackets = observed,
                    )
                    check(MinecraftTestSupport.isAlive(client)) {
                        "Official client exited after protocol round-trip probes"
                    }
                    connection.close()
                    completed = true
                } catch (failure: Throwable) {
                    connection.close()
                    throw failure
                }
            }
            Unit
        } finally {
            clientWatcher.cancelAndJoin()
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
                OPTIONS.protocolData.tags.registries
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
            connection.outgoing.send(packet)
            awaitPlayBarrier(
                connection = connection,
                label = packet::class.simpleName ?: "clientbound packet $index",
                pingId = PLAY_PING_ID + index,
                keepAliveId = PLAY_PROBE_KEEP_ALIVE_ID + index,
                observed = observed,
            )
        }

        connection.outgoing.send(
            PlayStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        connection.outgoing.send(PlayCookieRequestPacket(COOKIE_KEY))
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

        connection.outgoing.send(
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
    }

    private suspend fun exerciseRespawn(
        connection: MinecraftServerConnection,
        world: MinecraftInitialWorld,
        observed: MutableList<String>,
    ) {
        val login = checkNotNull(connection.playLogin) {
            "Respawn requires the negotiated Play Login"
        }
        connection.outgoing.send(
            RespawnPacket(
                spawnInfo = login.spawnInfo,
                dataToKeep = RespawnPacket.KEEP_ALL_DATA.toByte(),
            ),
        )
        val synchronization = connection.synchronizeInitialWorld(world)
        connection.outgoing.send(
            PlayClientboundKeepAlivePacket(RESPAWN_KEEP_ALIVE_ID),
        )
        connection.outgoing.send(ClientboundPingPacket(RESPAWN_PING_ID))

        var keepAlive = false
        var ping = false
        var tick = false
        var teleport = false
        var chunkBatch = false
        var playerLoaded = false
        var packetBudget = OPTIONS.maximumPacketsPerPhase
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
            "Official client did not complete Respawn; keepAlive=$keepAlive, ping=$ping, tick=$tick, teleport=$teleport, chunkBatch=$chunkBatch, playerLoaded=$playerLoaded"
        }
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
        connection.outgoing.send(ClientboundPingPacket(pingId))
        connection.outgoing.send(
            PlayClientboundKeepAlivePacket(keepAliveId),
        )
        var pingRoundTrip = false
        var keepAliveRoundTrip = false
        var tickObserved = false
        var packetBudget = OPTIONS.maximumPacketsPerPhase
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
                val packet = connection.incoming.receive()
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
                "Official client disconnected while processing $label; ping=$pingRoundTrip, keepAlive=$keepAliveRoundTrip, tick=$tickObserved, additional=${additionalComplete()}",
                failure,
            )
        }
        check(
            pingRoundTrip &&
                    keepAliveRoundTrip &&
                    tickObserved &&
                    additionalComplete(),
        ) {
            "Official client did not pass the $label barrier; ping=$pingRoundTrip, keepAlive=$keepAliveRoundTrip, tick=$tickObserved, additional=${additionalComplete()}"
        }
    }

    private suspend fun exerciseReconfiguration(
        connection: MinecraftServerConnection,
        world: MinecraftInitialWorld,
        observedPlayPackets: MutableList<String>,
    ) {
        var playerLoaded = observedPlayPackets.any { it == "PlayerLoadedPacket" }
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
        connection.outgoing.send(StartConfigurationPacket)
        var acknowledged = false
        var packetBudget = OPTIONS.maximumPacketsPerPhase
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
        check(connection.state == ConnectionState.CONFIGURATION) {
            "Server session did not enter Configuration after acknowledgement"
        }

        connection.outgoing.send(
            ConfigurationStoreCookiePacket(COOKIE_KEY, COOKIE_PAYLOAD),
        )
        connection.outgoing.send(ConfigurationCookieRequestPacket(COOKIE_KEY))
        connection.outgoing.send(
            ConfigurationClientboundKeepAlivePacket(
                CONFIGURATION_KEEP_ALIVE_ID,
            ),
        )
        connection.outgoing.send(
            ConfigurationPingPacket(CONFIGURATION_PING_ID),
        )
        connection.outgoing.send(
            ConfigurationClientboundPluginMessagePacket(
                CustomPayload.Brand("minecraft-protocol"),
            ),
        )
        connection.outgoing.send(ConfigurationRemoveResourcePackPacket(null))
        connection.outgoing.send(ResetChatPacket)
        connection.outgoing.send(
            ConfigurationCustomReportDetailsPacket(
                listOf(
                    ReportDetail(
                        title = "E2E",
                        description = "reconfiguration",
                    ),
                ),
            ),
        )
        connection.outgoing.send(ConfigurationServerLinksPacket(emptyList()))
        connection.outgoing.send(ConfigurationClearDialogPacket)
        connection.outgoing.send(
            OPTIONS.protocolData.featureFlags,
        )
        connection.outgoing.send(
            ConfigurationClientboundKnownPacksPacket(
                OPTIONS.protocolData.knownPacks,
            ),
        )

        var cookieRoundTrip = false
        var keepAliveRoundTrip = false
        var pingRoundTrip = false
        var knownPacks: ConfigurationServerboundKnownPacksPacket? = null
        packetBudget = OPTIONS.maximumPacketsPerPhase
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
            when (packet) {
                is ConfigurationCookieResponsePacket ->
                    if (packet.key == COOKIE_KEY) {
                        check(packet.payload == COOKIE_PAYLOAD) {
                            "Official client returned the wrong Configuration cookie"
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
            "Official client did not complete Configuration probes; cookie=$cookieRoundTrip, keepAlive=$keepAliveRoundTrip, ping=$pingRoundTrip, knownPacks=${knownPacks != null}"
        }
        val acceptedKnownPacks = knownPacks.knownPacks
        OPTIONS.protocolData
            .registryPackets(acceptedKnownPacks)
            .forEach { connection.outgoing.send(it) }
        connection.outgoing.send(
            OPTIONS.protocolData.tags,
        )
        connection.outgoing.send(FinishConfigurationPacket)

        var completed = false
        packetBudget = OPTIONS.maximumPacketsPerPhase
        while (packetBudget-- > 0 && !completed) {
            val packet = receiveForStage(
                connection,
                "waiting for Finish Configuration acknowledgement",
            )
            completed = packet == AcknowledgeFinishConfigurationPacket
        }
        check(completed) {
            "Official client did not finish reconfiguration"
        }
        check(connection.state == ConnectionState.PLAY) {
            "Server session did not return to Play after reconfiguration"
        }

        connection.outgoing.send(
            checkNotNull(connection.playLogin) {
                "Reconfiguration requires the negotiated Play Login"
            },
        )
        val reconfiguredWorld = world.copy(
            teleportId = world.teleportId + 3,
        )
        val synchronization = connection.synchronizeInitialWorld(reconfiguredWorld)
        connection.outgoing.send(
            PlayClientboundKeepAlivePacket(
                POST_CONFIGURATION_KEEP_ALIVE_ID,
            ),
        )
        connection.outgoing.send(
            ClientboundPingPacket(POST_CONFIGURATION_PING_ID),
        )
        var postKeepAlive = false
        var postPing = false
        var postTick = false
        var postTeleport = false
        var postChunkBatch = false
        var postPlayerLoaded = false
        packetBudget = OPTIONS.maximumPacketsPerPhase
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
            "Official client did not resume Play after reconfiguration; keepAlive=$postKeepAlive, ping=$postPing, tick=$postTick, teleport=$postTeleport, chunkBatch=$postChunkBatch, playerLoaded=$postPlayerLoaded"
        }
    }

    private suspend fun receiveForStage(
        connection: MinecraftServerConnection,
        stage: String,
    ): Packet =
        try {
            connection.incoming.receive()
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "Official client disconnected while $stage (server state ${connection.state})",
                failure,
            )
        }
}
