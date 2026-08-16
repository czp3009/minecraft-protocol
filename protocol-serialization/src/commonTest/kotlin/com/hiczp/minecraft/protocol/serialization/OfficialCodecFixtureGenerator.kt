package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDouble
import com.hiczp.minecraft.nbt.NbtFloat
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/**
 * Emits protocol-valid Kotlin payloads for the exact-version vanilla codec
 * oracle. This lives in JVM test code because the oracle itself executes the
 * official server JAR and is not part of the multiplatform library.
 */
internal object OfficialCodecFixtureGenerator {
    fun generate(): JsonElement {
        val format = MinecraftProtocolFormat(
            MinecraftProtocolFormatConfiguration(
                registries = testRegistryContext(chunkSectionCount = 0),
            ),
        )
        val fixtures = buildJsonArray {
            for (codec in MinecraftPacketRegistry.entries) {
                if (codec.framing != PacketFraming.NORMAL) {
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val serializer = codec.serializer as KSerializer<Packet>
                val packetName = serializer.descriptor.serialName
                val samples = explicitSamples(codec.packetClass)
                    ?: ProtocolSampleProfile.entries.mapNotNull { profile ->
                        runCatching {
                            profile.name.lowercase() to serializer.protocolValue(profile)
                        }.getOrNull()
                    }
                val seenPayloads = mutableSetOf<String>()
                for ((sampleName, sample) in samples) {
                    val payload = runCatching {
                        MinecraftPacketRegistry.encodePayload(sample, format)
                    }.getOrNull() ?: continue
                    val payloadHex = payload.payload.toHexString()
                    if (!seenPayloads.add(payloadHex)) {
                        continue
                    }
                    add(
                        buildJsonObject {
                            put("state", codec.key.state.name)
                            put("direction", codec.key.direction.name)
                            put("id", codec.key.id)
                            put(
                                "kotlinClass",
                                packetName,
                            )
                            put("sample", sampleName)
                            put("payloadHex", payloadHex)
                        },
                    )
                }
                check(seenPayloads.isNotEmpty()) {
                    "No protocol sample could be generated for ${codec.packetClass}"
                }
            }
        }
        return fixtures
    }

    private fun explicitSamples(packetClass: KClass<*>): List<Pair<String, Packet>>? =
        when (packetClass) {
            StatusResponsePacket::class ->
                listOf(
                    "valid_json" to StatusResponsePacket(
                        """{"version":{"name":"test","protocol":1},"players":{"max":0,"online":0},"description":{"text":"test"}}""",
                    ),
                )

            LoginDisconnectPacket::class ->
                listOf(
                    "text_component" to
                            LoginDisconnectPacket(JsonTextComponent("""{"text":"test"}""")),
                )

            ConfigurationShowDialogPacket::class ->
                listOf(
                    "notice_dialog" to ConfigurationShowDialogPacket(
                        NbtCompound(
                            mapOf(
                                "type" to NbtString("minecraft:notice"),
                                "title" to NbtString("test"),
                            ),
                        ),
                    ),
                )

            ParticlePacket::class ->
                (particleRegistrySamples() + additionalParticleBranchSamples())
                    .map { sample ->
                        "particle-${sample.name}" to ParticlePacket(
                            overrideLimiter = false,
                            alwaysShow = true,
                            x = 1.0,
                            y = 2.0,
                            z = 3.0,
                            offsetX = 0.1f,
                            offsetY = 0.2f,
                            offsetZ = 0.3f,
                            maxSpeed = 1.0f,
                            count = 1,
                            particle = sample.value,
                        )
                    }

            CommandsPacket::class ->
                commandParserRegistrySamples().map { sample ->
                    "parser-${sample.name}" to CommandsPacket(
                        nodes = listOf(
                            CommandNode.Root(children = listOf(1)),
                            CommandNode.Argument(
                                name = "value",
                                parser = sample.value,
                                children = emptyList(),
                                executable = true,
                            ),
                        ),
                        rootIndex = 0,
                    )
                }

            DebugSubscriptionRequestPacket::class ->
                listOf(
                    "all-subscriptions" to DebugSubscriptionRequestPacket(
                        DebugSubscriptionType.entries.toSet(),
                    ),
                )

            DebugEventPacket::class ->
                debugSubscriptionDataSamples().map { sample ->
                    "debug-${sample.name}" to DebugEventPacket(
                        DebugSubscriptionEvent(sample.value),
                    )
                }

            MapDataPacket::class ->
                listOf(
                    "without-color-patch" to MapDataPacket(
                        mapId = 1,
                        scale = 1,
                        locked = false,
                        decorations = null,
                        colorPatch = null,
                    ),
                    "non-symmetric-color-patch" to MapDataPacket(
                        mapId = 1,
                        scale = 1,
                        locked = false,
                        decorations = emptyList(),
                        colorPatch = MapColorPatch(
                            startX = 3,
                            startY = 4,
                            width = 2,
                            height = 1,
                            colors = ByteString(byteArrayOf(5, 6)),
                        ),
                    ),
                )

            BossBarPacket::class ->
                bossBarActionSamples().map { (name, action) ->
                    name to BossBarPacket(Uuid.fromLongs(1, 2), action)
                }

            PlayerInfoUpdatePacket::class ->
                playerInfoUpdateSamples()

            SetObjectivePacket::class ->
                objectiveUpdateSamples().map { (name, update) ->
                    name to SetObjectivePacket("objective", update)
                }

            SetPlayerTeamPacket::class ->
                teamUpdateSamples().map { (name, update) ->
                    name to SetPlayerTeamPacket("team", update)
                }

            WaypointPacket::class ->
                waypointSamples()

            PlaceGhostRecipePacket::class ->
                recipeDisplayRegistrySamples().map { sample ->
                    "recipe-${sample.name}" to PlaceGhostRecipePacket(
                        containerId = 1,
                        recipeDisplay = sample.value,
                    )
                } + slotDisplayRegistrySamples().map { sample ->
                    "slot-${sample.name}" to PlaceGhostRecipePacket(
                        containerId = 1,
                        recipeDisplay = RecipeDisplay.Shapeless(
                            ingredients = listOf(sample.value),
                            result = SlotDisplay.Empty,
                            craftingStation = SlotDisplay.Empty,
                        ),
                    )
                }

            SetEntityMetadataPacket::class ->
                entityDataValueSamples().map { sample ->
                    "entity-data-${sample.name}" to SetEntityMetadataPacket(
                        entityId = 1,
                        metadata = EntityMetadata(
                            listOf(
                                EntityMetadataEntry(
                                    index = 0,
                                    value = sample.value,
                                ),
                            ),
                        ),
                    )
                }

            SetCursorItemPacket::class ->
                listOf(
                    "empty_stack" to SetCursorItemPacket(ItemStack.Empty),
                ) + officialDataComponentSamples().map { sample ->
                    "component-${sample.name}" to SetCursorItemPacket(
                        ItemStack.of(
                            itemId = 1,
                            components = DataComponentPatch(
                                added = listOf(sample.value),
                            ),
                        ),
                    )
                }

            SetScorePacket::class ->
                listOf(
                    "without_optionals" to SetScorePacket(
                        owner = "owner",
                        objectiveName = "objective",
                        score = 1,
                        display = null,
                        numberFormat = null,
                    ),
                    "blank_number_format" to SetScorePacket(
                        owner = "owner",
                        objectiveName = "objective",
                        score = 1,
                        display = TextComponent.literal("display"),
                        numberFormat = NumberFormat.Blank,
                    ),
                    "styled_number_format" to SetScorePacket(
                        owner = "owner",
                        objectiveName = "objective",
                        score = 1,
                        display = TextComponent.literal("display"),
                        numberFormat = NumberFormat.Styled(NbtCompound(emptyMap())),
                    ),
                    "fixed_number_format" to SetScorePacket(
                        owner = "owner",
                        objectiveName = "objective",
                        score = 1,
                        display = TextComponent.literal("display"),
                        numberFormat = NumberFormat.Fixed(TextComponent.literal("fixed")),
                    ),
                )

            UpdateRecipesPacket::class ->
                listOf(
                    "empty" to UpdateRecipesPacket(
                        itemSets = emptyMap(),
                        stonecutterRecipes = emptyList(),
                    ),
                    "item_property_set" to UpdateRecipesPacket(
                        itemSets = mapOf(
                            Identifier("test") to RecipePropertySet(listOf(1)),
                        ),
                        stonecutterRecipes = emptyList(),
                    ),
                )

            PlayerSessionPacket::class -> {
                listOf(
                    "rsa_public_key" to PlayerSessionPacket(
                        officialChatSession(),
                    ),
                )
            }

            else -> null
        }

    private fun bossBarActionSamples(): List<Pair<String, BossBarAction>> =
        listOf(
            "add" to BossBarAction.Add(
                title = TextComponent.literal("title"),
                health = 0.5f,
                color = BossBarColor.BLUE,
                division = BossBarDivision.TEN_NOTCHES,
                flags = 3,
            ),
            "remove" to BossBarAction.Remove,
            "update-health" to BossBarAction.UpdateHealth(0.25f),
            "update-title" to BossBarAction.UpdateTitle(
                TextComponent.literal("updated"),
            ),
            "update-style" to BossBarAction.UpdateStyle(
                BossBarColor.WHITE,
                BossBarDivision.TWENTY_NOTCHES,
            ),
            "update-flags" to BossBarAction.UpdateFlags(7),
        )

    private fun playerInfoUpdateSamples(): List<Pair<String, Packet>> {
        val profileId = Uuid.fromLongs(1, 2)
        fun packet(
            name: String,
            action: PlayerInfoAction,
            entry: PlayerInfoEntry,
        ): Pair<String, Packet> = name to PlayerInfoUpdatePacket(
            PlayerInfoUpdatePayload(
                actions = setOf(action),
                entries = listOf(entry),
            ),
        )

        return listOf(
            packet(
                "add-player",
                PlayerInfoAction.ADD_PLAYER,
                PlayerInfoEntry(
                    profileId = profileId,
                    profile = PlayerListProfile("player", emptyList()),
                ),
            ),
            packet(
                "initialize-chat-null",
                PlayerInfoAction.INITIALIZE_CHAT,
                PlayerInfoEntry(profileId = profileId, chatSession = null),
            ),
            packet(
                "initialize-chat-value",
                PlayerInfoAction.INITIALIZE_CHAT,
                PlayerInfoEntry(
                    profileId = profileId,
                    chatSession = officialChatSession(),
                ),
            ),
            packet(
                "update-game-mode",
                PlayerInfoAction.UPDATE_GAME_MODE,
                PlayerInfoEntry(profileId = profileId, gameMode = GameMode.CREATIVE),
            ),
            packet(
                "update-listed",
                PlayerInfoAction.UPDATE_LISTED,
                PlayerInfoEntry(profileId = profileId, listed = true),
            ),
            packet(
                "update-latency",
                PlayerInfoAction.UPDATE_LATENCY,
                PlayerInfoEntry(profileId = profileId, latency = 300),
            ),
            packet(
                "update-display-name-null",
                PlayerInfoAction.UPDATE_DISPLAY_NAME,
                PlayerInfoEntry(profileId = profileId, displayName = null),
            ),
            packet(
                "update-display-name-value",
                PlayerInfoAction.UPDATE_DISPLAY_NAME,
                PlayerInfoEntry(
                    profileId = profileId,
                    displayName = TextComponent.literal("display"),
                ),
            ),
            packet(
                "update-list-order",
                PlayerInfoAction.UPDATE_LIST_ORDER,
                PlayerInfoEntry(profileId = profileId, listOrder = 300),
            ),
            packet(
                "update-hat",
                PlayerInfoAction.UPDATE_HAT,
                PlayerInfoEntry(profileId = profileId, showHat = true),
            ),
            "all-actions" to PlayerInfoUpdatePacket(
                PlayerInfoUpdatePayload(
                    actions = PlayerInfoAction.entries.toSet(),
                    entries = listOf(
                        PlayerInfoEntry(
                            profileId = profileId,
                            profile = PlayerListProfile("player", emptyList()),
                            chatSession = officialChatSession(),
                            gameMode = GameMode.CREATIVE,
                            listed = true,
                            latency = 300,
                            displayName = TextComponent.literal("display"),
                            listOrder = 300,
                            showHat = true,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun objectiveUpdateSamples(): List<Pair<String, ObjectiveUpdate>> =
        listOf(
            "add-without-format" to ObjectiveUpdate.Add(
                TextComponent.literal("objective"),
                ObjectiveRenderType.INTEGER,
                null,
            ),
            "add-with-format" to ObjectiveUpdate.Add(
                TextComponent.literal("objective"),
                ObjectiveRenderType.HEARTS,
                NumberFormat.Blank,
            ),
            "remove" to ObjectiveUpdate.Remove,
            "change-without-format" to ObjectiveUpdate.Change(
                TextComponent.literal("changed"),
                ObjectiveRenderType.INTEGER,
                null,
            ),
            "change-with-format" to ObjectiveUpdate.Change(
                TextComponent.literal("changed"),
                ObjectiveRenderType.HEARTS,
                NumberFormat.Fixed(TextComponent.literal("fixed")),
            ),
        )

    private fun teamUpdateSamples(): List<Pair<String, TeamUpdate>> {
        val parameters = TeamParameters(
            displayName = TextComponent.literal("team"),
            playerPrefix = TextComponent.literal("["),
            playerSuffix = TextComponent.literal("]"),
            nameTagVisibility = TeamVisibility.ALWAYS,
            collisionRule = TeamCollisionRule.ALWAYS,
            color = TeamColor.WHITE,
            options = 3,
        )
        return listOf(
            "add" to TeamUpdate.Add(parameters, listOf("player")),
            "remove" to TeamUpdate.Remove,
            "change" to TeamUpdate.Change(parameters),
            "join" to TeamUpdate.Join(listOf("player")),
            "leave" to TeamUpdate.Leave(listOf("player")),
        )
    }

    private fun waypointSamples(): List<Pair<String, Packet>> {
        val entity = WaypointIdentifier.Entity(Uuid.fromLongs(1, 2))
        val named = WaypointIdentifier.Named("named")
        val plainIcon = WaypointIcon(Identifier("minecraft:test"))
        val coloredIcon = WaypointIcon(
            Identifier("minecraft:test"),
            0x11_22_33,
        )
        return listOf(
            "empty-entity" to WaypointPacket(
                WaypointOperation.TRACK,
                TrackedWaypoint.Empty(entity, plainIcon),
            ),
            "position-named" to WaypointPacket(
                WaypointOperation.UPDATE,
                TrackedWaypoint.Position(named, coloredIcon, 1, 2, 3),
            ),
            "chunk-entity" to WaypointPacket(
                WaypointOperation.UPDATE,
                TrackedWaypoint.Chunk(entity, plainIcon, 1, 2),
            ),
            "azimuth-named" to WaypointPacket(
                WaypointOperation.UNTRACK,
                TrackedWaypoint.Azimuth(named, coloredIcon, 0.5f),
            ),
        )
    }

    private fun officialChatSession(): ChatSessionData {
        val encodedKey = Base64.decode(
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCXm27m9IJ99sRdr7KVI0d6dPaaDcR5VqzvQkUFFzObLW2WCXxEIywlQM4ti7xieFthhktqHF3fLzg85ySqmz/VVAPS0eH1ebJ7Q8Gd43Iz1B/GRZ4FuDOlwJdP+yCcnnJL9rUzKXgm0hmtHa8p8YEeOBi1w4j6/2HZRk5uJI2i9QIDAQAB",
        )
        return ChatSessionData(
            sessionId = Uuid.fromLongs(1L, 2L),
            profilePublicKey = ProfilePublicKeyData(
                expiresAtEpochMillis = 1L,
                encodedKey = ByteString(encodedKey),
                keySignature = ByteString(byteArrayOf(1)),
            ),
        )
    }

    private fun officialDataComponentSamples(): List<NamedDataComponentSample> {
        val generic = dataComponentTestSamples().filterNot { sample ->
            sample.name == "can_place_on-non_empty_collections" ||
                    sample.name == "can_break-non_empty_collections" ||
                    sample.name == "map_decorations-non_empty_collections" ||
                    sample.name == "debug_stick_state-non_empty_collections" ||
                    sample.name == "lock-non_empty_collections" ||
                    sample.type == DataComponentType.CONTAINER_LOOT
        }
        val blockPredicate = BlockPredicate(
            blocks = RegistryHolderSet.Direct(listOf(1)),
        )
        return generic + listOf(
            NamedDataComponentSample(
                name = "can_place_on-explicit_predicate",
                type = DataComponentType.CAN_PLACE_ON,
                value = DataComponent.CanPlaceOn(
                    AdventureModePredicate(listOf(blockPredicate)),
                ),
            ),
            NamedDataComponentSample(
                name = "can_break-explicit_predicate",
                type = DataComponentType.CAN_BREAK,
                value = DataComponent.CanBreak(
                    AdventureModePredicate(listOf(blockPredicate)),
                ),
            ),
            NamedDataComponentSample(
                name = "map_decorations-explicit_entry",
                type = DataComponentType.MAP_DECORATIONS,
                value = DataComponent.MapDecorations(
                    NbtCompound(
                        mapOf(
                            "marker" to NbtCompound(
                                mapOf(
                                    "rotation" to NbtFloat(3.0f),
                                    "x" to NbtDouble(1.0),
                                    "z" to NbtDouble(2.0),
                                    "type" to NbtString("minecraft:player"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            NamedDataComponentSample(
                name = "debug_stick_state-explicit_entry",
                type = DataComponentType.DEBUG_STICK_STATE,
                value = DataComponent.DebugStickState(
                    NbtCompound(
                        mapOf(
                            "minecraft:oak_log" to NbtString("axis"),
                        ),
                    ),
                ),
            ),
            NamedDataComponentSample(
                name = "container_loot-explicit",
                type = DataComponentType.CONTAINER_LOOT,
                value = DataComponent.ContainerLoot(
                    NbtCompound(
                        mapOf(
                            "loot_table" to NbtString("minecraft:empty"),
                        ),
                    ),
                ),
            ),
        ) + consumeEffectRegistrySamples().map { sample ->
            NamedDataComponentSample(
                name = "consumable-effect-${sample.name}",
                type = DataComponentType.CONSUMABLE,
                value = DataComponent.Consumable(
                    consumeSeconds = 1.0f,
                    animation = ItemUseAnimation.EAT,
                    sound = SoundEventHolder.Reference(0),
                    hasConsumeParticles = true,
                    onConsumeEffects = listOf(sample.value),
                ),
            )
        }
    }
}
