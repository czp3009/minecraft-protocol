@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.FixedLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.WrappedEnum
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

@Serializable
@PacketInfo(
    0x21,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "disguised_chat",
)
data class DisguisedChatPacket(
    val message: TextComponent,
    val chatType: BoundChatType,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x24,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "explode",
)
data class ExplosionPacket(
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
data class ChunkDataAndUpdateLightPacket(
    val chunkX: Int,
    val chunkZ: Int,
    val chunkData: ChunkData,
    val lightData: LightUpdateData,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x2F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "level_particles",
)
data class ParticlePacket(
    val overrideLimiter: Boolean,
    val alwaysShow: Boolean,
    val x: Double,
    val y: Double,
    val z: Double,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    val maxSpeed: Float,
    val count: Int,
    val particle: ParticleOptions,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x30,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "light_update",
)
data class LightUpdatePacket(
    @VarInt
    val chunkX: Int,
    @VarInt
    val chunkZ: Int,
    val data: LightUpdateData,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x31,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "login",
)
data class PlayLoginPacket(
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
    val limitedCrafting: Boolean,
    val spawnInfo: CommonPlayerSpawnInfo,
    val onlineMode: Boolean,
    val enforcesSecureChat: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable(with = MapDataPacketSerializer::class)
@PacketInfo(
    0x33,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "map_item_data",
)
data class MapDataPacket(
    @VarInt
    val mapId: Int,
    val scale: Byte,
    val locked: Boolean,
    val decorations: List<MapDecoration>?,
    val colorPatch: MapColorPatch?,
) : PlayStatePacket, ClientboundPacket

internal object MapDataPacketSerializer : KSerializer<MapDataPacket> {
    private val decorationsSerializer = ListSerializer(MapDecoration.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.MapDataPacket",
    ) {
        element<Int>("mapId", annotations = listOf(VarInt()))
        element<Byte>("scale")
        element<Boolean>("locked")
        element("decorations", decorationsSerializer.nullable.descriptor)
        element("colorPatch", NullableMapColorPatchSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: MapDataPacket) {
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

    override fun deserialize(decoder: Decoder): MapDataPacket {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val value = MapDataPacket(
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
            return value
        }

        var mapId: Int? = null
        var scale: Byte? = null
        var locked: Boolean? = null
        var decorations: List<MapDecoration>? = null
        var colorPatch: MapColorPatch? = null
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
                    colorPatch = input.decodeSerializableElement(
                        descriptor,
                        COLOR_PATCH,
                        NullableMapColorPatchSerializer,
                    )
                    sawColorPatch = true
                }

                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException(
                    "Unexpected MapDataPacket field $index",
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
        return MapDataPacket(
            mapId = mapId ?: throw SerializationException("Missing map ID"),
            scale = scale ?: throw SerializationException("Missing map scale"),
            locked = locked ?: throw SerializationException("Missing map lock state"),
            decorations = decorations,
            colorPatch = colorPatch,
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
data class PlayerChatMessagePacket(
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
) : PlayStatePacket, ClientboundPacket {
    init {
        require(signature == null || signature.size == SIGNATURE_BYTES) {
            "A message signature must contain $SIGNATURE_BYTES bytes"
        }
    }

    companion object {
        const val SIGNATURE_BYTES: Int = 256
    }
}

@Serializable
@PacketInfo(
    0x46,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_info_update",
)
data class PlayerInfoUpdatePacket(
    val update: PlayerInfoUpdatePayload,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x52,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "respawn",
)
data class RespawnPacket(
    val spawnInfo: CommonPlayerSpawnInfo,
    val dataToKeep: Byte,
) : PlayStatePacket, ClientboundPacket {
    companion object {
        const val KEEP_ATTRIBUTE_MODIFIERS: Int = 0x01
        const val KEEP_ENTITY_DATA: Int = 0x02
        const val KEEP_ALL_DATA: Int =
            KEEP_ATTRIBUTE_MODIFIERS or KEEP_ENTITY_DATA
    }
}

@Serializable
@PacketInfo(
    0x63,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_entity_data",
)
data class SetEntityMetadataPacket(
    @VarInt
    val entityId: Int,
    val metadata: EntityMetadata,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_objective",
)
data class SetObjectivePacket(
    val objectiveName: String,
    val update: ObjectiveUpdate,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_player_team",
)
data class SetPlayerTeamPacket(
    val teamName: String,
    val update: TeamUpdate,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_score",
)
data class SetScorePacket(
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
data class EntitySoundEffectPacket(
    val sound: SoundEventHolder,
    val source: SoundSource,
    @VarInt
    val entityId: Int,
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
data class SoundEffectPacket(
    val sound: SoundEventHolder,
    val source: SoundSource,
    val encodedX: Int,
    val encodedY: Int,
    val encodedZ: Int,
    val volume: Float,
    val pitch: Float,
    val seed: Long,
) : PlayStatePacket, ClientboundPacket {
    val x: Double
        get() = encodedX / POSITION_SCALE

    val y: Double
        get() = encodedY / POSITION_SCALE

    val z: Double
        get() = encodedZ / POSITION_SCALE

    companion object {
        private const val POSITION_SCALE: Double = 8.0

        fun fromPosition(
            sound: SoundEventHolder,
            source: SoundSource,
            x: Double,
            y: Double,
            z: Double,
            volume: Float,
            pitch: Float,
            seed: Long,
        ): SoundEffectPacket = SoundEffectPacket(
            sound,
            source,
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
    0x8C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "show_dialog",
)
data class PlayShowDialogPacket(
    val dialog: DialogHolder,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x8A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "waypoint",
)
data class WaypointPacket(
    @WrappedEnum
    val operation: WaypointOperation,
    val waypoint: TrackedWaypoint,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "place_ghost_recipe",
)
data class PlaceGhostRecipePacket(
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
data class RecipeBookAddPacket(
    val entries: List<RecipeBookEntry>,
    val replace: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x82,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_advancements",
)
data class UpdateAdvancementsPacket(
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
data class UpdateRecipesPacket(
    val itemSets: Map<Identifier, RecipePropertySet>,
    val stonecutterRecipes: List<StonecutterRecipeOption>,
) : PlayStatePacket, ClientboundPacket
