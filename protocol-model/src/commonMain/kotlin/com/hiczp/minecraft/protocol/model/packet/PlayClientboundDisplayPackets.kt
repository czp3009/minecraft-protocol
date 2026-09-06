@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.model.wire.FixedLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.WrappedEnum
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(
    0x21,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "disguised_chat",
)
data class ClientboundDisguisedChatPacket(
    val message: TextComponent,
    val chatType: BoundChatType,
) : PlayStatePacket, SkippableClientboundPacket

@Serializable
@PacketInfo(
    0x24,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "explode",
)
data class ClientboundExplodePacket(
    val center: Vector3d,
    val radius: Float,
    val blockCount: Int,
    val playerKnockback: Vector3d?,
    val explosionParticle: ParticleOptions,
    val explosionSound: SoundEventHolder,
    val blockParticles: List<WeightedExplosionParticle>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x2D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "level_chunk_with_light",
)
data class ClientboundLevelChunkWithLightPacket(
    val x: Int,
    val z: Int,
    val chunkData: ClientboundLevelChunkPacketData,
    val lightData: ClientboundLightUpdatePacketData,
) : PlayStatePacket, ClientboundPacket

@Serializable(with = ClientboundLevelParticlesPacketSerializer::class)
@PacketInfo(
    0x2F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "level_particles",
)
data class ClientboundLevelParticlesPacket(
    val x: Double,
    val y: Double,
    val z: Double,
    val xDist: Float,
    val yDist: Float,
    val zDist: Float,
    val maxSpeed: Float,
    val count: Int,
    val overrideLimiter: Boolean,
    val alwaysShow: Boolean,
    val particle: ParticleOptions,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x30,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "light_update",
)
data class ClientboundLightUpdatePacket(
    @VarInt
    val x: Int,
    @VarInt
    val z: Int,
    val lightData: ClientboundLightUpdatePacketData,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x31,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "login",
)
data class ClientboundLoginPacket(
    val playerId: Int,
    val hardcore: Boolean,
    val levels: Set<Identifier>,
    @VarInt
    val maxPlayers: Int,
    @VarInt
    val chunkRadius: Int,
    @VarInt
    val simulationDistance: Int,
    val reducedDebugInfo: Boolean,
    val showDeathScreen: Boolean,
    val doLimitedCrafting: Boolean,
    val commonPlayerSpawnInfo: CommonPlayerSpawnInfo,
    val onlineMode: Boolean,
    val enforcesSecureChat: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable(with = ClientboundMapItemDataPacketSerializer::class)
@PacketInfo(
    0x33,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "map_item_data",
)
data class ClientboundMapItemDataPacket(
    @VarInt
    val mapId: Int,
    val scale: Byte,
    val locked: Boolean,
    val decorations: List<MapDecoration>?,
    val colorPatch: MapColorPatch?,
) : PlayStatePacket, ClientboundPacket

internal object ClientboundMapItemDataPacketSerializer

    : KSerializer<ClientboundMapItemDataPacket> {
    private val decorationsSerializer = ListSerializer(MapDecoration.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ClientboundMapItemDataPacket",
    ) {
        element<Int>("mapId", annotations = listOf(VarInt()))
        element<Byte>("scale")
        element<Boolean>("locked")
        element("decorations", decorationsSerializer.nullable.descriptor)
        element("colorPatch", NullableMapColorPatchSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: ClientboundMapItemDataPacket) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, MAP_ID, value.mapId)
        output.encodeByteElement(descriptor, SCALE, value.scale)
        output.encodeBooleanElement(descriptor, LOCKED, value.locked)
        output.encodeNullableSerializableElement(
            descriptor,
            DECORATIONS,
            decorationsSerializer,
            value.decorations,
        )
        output.encodeSerializableElement(
            descriptor,
            COLOR_PATCH,
            NullableMapColorPatchSerializer,
            value.colorPatch,
        )
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ClientboundMapItemDataPacket {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val clientboundMapItemDataPacket = ClientboundMapItemDataPacket(
                mapId = input.decodeIntElement(descriptor, MAP_ID),
                scale = input.decodeByteElement(descriptor, SCALE),
                locked = input.decodeBooleanElement(descriptor, LOCKED),
                decorations = input.decodeNullableSerializableElement(
                    descriptor,
                    DECORATIONS,
                    decorationsSerializer.nullable,
                ),
                colorPatch = input.decodeSerializableElement(
                    descriptor,
                    COLOR_PATCH,
                    NullableMapColorPatchSerializer,
                ),
            )
            input.endStructure(descriptor)
            return clientboundMapItemDataPacket
        }

        var mapId: Int? = null
        var scale: Byte? = null
        var locked: Boolean? = null
        var decorations: List<MapDecoration>? = null
        var mapColorPatch: MapColorPatch? = null
        var sawDecorations = false
        var sawColorPatch = false
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                MAP_ID -> mapId = input.decodeIntElement(descriptor, MAP_ID)
                SCALE -> scale = input.decodeByteElement(descriptor, SCALE)
                LOCKED -> locked = input.decodeBooleanElement(descriptor, LOCKED)
                DECORATIONS -> {
                    decorations = input.decodeNullableSerializableElement(
                        descriptor,
                        DECORATIONS,
                        decorationsSerializer.nullable,
                    )
                    sawDecorations = true
                }

                COLOR_PATCH -> {
                    mapColorPatch = input.decodeSerializableElement(
                        descriptor,
                        COLOR_PATCH,
                        NullableMapColorPatchSerializer,
                    )
                    sawColorPatch = true
                }

                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException(
                    "Unexpected ClientboundMapItemDataPacket field $index",
                )
            }
        }
        input.endStructure(descriptor)
        if (!sawDecorations) {
            throw SerializationException("Missing map decorations field")
        }
        if (!sawColorPatch) {
            throw SerializationException("Missing map color-patch field")
        }
        return ClientboundMapItemDataPacket(
            mapId = mapId ?: throw SerializationException("Missing map ID"),
            scale = scale ?: throw SerializationException("Missing map scale"),
            locked = locked ?: throw SerializationException("Missing map lock state"),
            decorations = decorations,
            colorPatch = mapColorPatch,
        )
    }

    private const val MAP_ID: Int = 0
    private const val SCALE: Int = 1
    private const val LOCKED: Int = 2
    private const val DECORATIONS: Int = 3
    private const val COLOR_PATCH: Int = 4
}

@Serializable
@PacketInfo(
    0x41,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_chat",
)
data class ClientboundPlayerChatPacket(
    @VarInt
    val globalIndex: Int,
    val sender: Uuid,
    @VarInt
    val index: Int,
    @FixedLength(256)
    val signature: ByteString?,
    val body: PackedSignedMessageBody,
    val unsignedContent: TextComponent?,
    val filterMask: FilterMask,
    val chatType: BoundChatType,
) : PlayStatePacket, SkippableClientboundPacket

@Serializable
@PacketInfo(
    0x46,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_info_update",
    shapeException = "PlayerInfoUpdate couples the action set with entries and validates that every selected action has data in each entry.",
)
data class ClientboundPlayerInfoUpdatePacket(
    val update: PlayerInfoUpdatePayload,
) : PlayStatePacket, ClientboundPacket {
    data class Entry(
        val profileId: Uuid,
        val profile: PlayerListProfile? = null,
        val listed: Boolean = false,
        val latency: Int = 0,
        val gameMode: GameMode = GameMode.SURVIVAL,
        val displayName: TextComponent? = null,
        val showHat: Boolean = false,
        val listOrder: Int = 0,
        val chatSession: ChatSessionData? = null,
    )

    enum class Action {
        ADD_PLAYER,
        INITIALIZE_CHAT,
        UPDATE_GAME_MODE,
        UPDATE_LISTED,
        UPDATE_LATENCY,
        UPDATE_DISPLAY_NAME,
        UPDATE_LIST_ORDER,
        UPDATE_HAT,
    }
}

@Serializable
@PacketInfo(
    0x52,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "respawn",
)
data class ClientboundRespawnPacket(
    val commonPlayerSpawnInfo: CommonPlayerSpawnInfo,
    val dataToKeep: Byte,
) : PlayStatePacket, ClientboundPacket {
    companion object {
        const val KEEP_ATTRIBUTE_MODIFIERS: Int = 0x01
        const val KEEP_ENTITY_DATA: Int = 0x02
        const val KEEP_ALL_DATA: Int = KEEP_ATTRIBUTE_MODIFIERS or KEEP_ENTITY_DATA
    }
}

@Serializable
@PacketInfo(
    0x63,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_entity_data",
)
data class ClientboundSetEntityDataPacket(
    @VarInt
    val id: Int,
    val packedItems: EntityMetadata,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_objective",
    shapeException = "ObjectiveUpdate is a sealed create-update-remove value that excludes payload fields on removal.",
)
data class ClientboundSetObjectivePacket(
    val objectiveName: String,
    val update: ObjectiveUpdate,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_player_team",
    shapeException = "TeamUpdate is a sealed operation with its required parameters and player list; impossible method-payload combinations cannot be constructed.",
)
data class ClientboundSetPlayerTeamPacket(
    val name: String,
    val update: TeamUpdate,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    data class Parameters(
        val displayName: TextComponent,
        val playerPrefix: TextComponent,
        val playerSuffix: TextComponent,
        @ZeroFallbackEnum
        val nameTagVisibility: TeamVisibility,
        @ZeroFallbackEnum
        val collisionRule: TeamCollisionRule,
        @ZeroFallbackEnum
        val color: TeamColor?,
        val options: Byte,
    )
}

@Serializable
@PacketInfo(
    0x6E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_score",
)
data class ClientboundSetScorePacket(
    val owner: String,
    val objectiveName: String,
    @VarInt
    val score: Int,
    val display: TextComponent?,
    val numberFormat: NumberFormat?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x74,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "sound_entity",
)
data class ClientboundSoundEntityPacket(
    val sound: SoundEventHolder,
    val source: SoundSource,
    @VarInt
    val id: Int,
    val volume: Float,
    val pitch: Float,
    val seed: Long,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x75,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "sound",
)
data class ClientboundSoundPacket(
    val sound: SoundEventHolder,
    val source: SoundSource,
    val x: Int,
    val y: Int,
    val z: Int,
    val volume: Float,
    val pitch: Float,
    val seed: Long,
) : PlayStatePacket, ClientboundPacket {
    val position: Vector3d
        get() = Vector3d(x / POSITION_SCALE, y / POSITION_SCALE, z / POSITION_SCALE)

    companion object {
        private const val POSITION_SCALE: Double = 8.0

        fun fromPosition(
            soundEventHolder: SoundEventHolder,
            soundSource: SoundSource,
            x: Double,
            y: Double,
            z: Double,
            volume: Float,
            pitch: Float,
            seed: Long,
        ): ClientboundSoundPacket = ClientboundSoundPacket(
            soundEventHolder,
            soundSource,
            (x * POSITION_SCALE).toInt(),
            (y * POSITION_SCALE).toInt(),
            (z * POSITION_SCALE).toInt(),
            volume,
            pitch,
            seed,
        )
    }
}

@Serializable
@PacketInfo(
    0x8A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "waypoint",
)
data class ClientboundTrackedWaypointPacket(
    @WrappedEnum
    val operation: Operation,
    val waypoint: TrackedWaypoint,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    enum class Operation {
        TRACK,
        UNTRACK,
        UPDATE,
    }
}

@Serializable
@PacketInfo(
    0x3F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "place_ghost_recipe",
)
data class ClientboundPlaceGhostRecipePacket(
    @VarInt
    val containerId: Int,
    val recipeDisplay: RecipeDisplay,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "recipe_book_add",
)
data class ClientboundRecipeBookAddPacket(
    val entries: List<ClientboundRecipeBookAddPacket.Entry>,
    val replace: Boolean,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    data class Entry(
        val contents: RecipeDisplayEntry,
        val flags: Byte,
    ) {
        val notification: Boolean
            get() = flags.toInt() and NOTIFICATION != 0

        val highlight: Boolean
            get() = flags.toInt() and HIGHLIGHT != 0

        companion object {
            const val NOTIFICATION: Int = 0x01
            const val HIGHLIGHT: Int = 0x02

            fun of(
                contents: RecipeDisplayEntry,
                notification: Boolean,
                highlight: Boolean,
            ): Entry = Entry(
                contents,
                ((if (notification) NOTIFICATION else 0) or
                        (if (highlight) HIGHLIGHT else 0)).toByte(),
            )
        }
    }
}

@Serializable
@PacketInfo(
    0x82,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_advancements",
)
data class ClientboundUpdateAdvancementsPacket(
    val reset: Boolean,
    val added: List<AdvancementHolder>,
    val removed: Set<Identifier>,
    val progress: Map<Identifier, AdvancementProgress>,
    val showAdvancements: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x85,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_recipes",
)
data class ClientboundUpdateRecipesPacket(
    val itemSets: Map<Identifier, RecipePropertySet>,
    val stonecutterRecipes: List<StonecutterRecipeOption>,
) : PlayStatePacket, ClientboundPacket
