@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class DebugSubscriptionType {
    DEDICATED_SERVER_TICK_TIME,
    BEE,
    VILLAGER_BRAIN,
    BREEZE,
    GOAL_SELECTOR,
    ENTITY_PATH,
    ENTITY_BLOCK_INTERSECTION,
    BEE_HIVE,
    POINT_OF_INTEREST,
    REDSTONE_WIRE_ORIENTATION,
    VILLAGE_SECTION,
    RAID,
    STRUCTURE,
    GAME_EVENT_LISTENER,
    NEIGHBOR_UPDATE,
    GAME_EVENT,
    ;

    val wireName: String
        get() = when (this) {
            DEDICATED_SERVER_TICK_TIME ->
                "minecraft:dedicated_server_tick_time"

            BEE -> "minecraft:bees"
            VILLAGER_BRAIN -> "minecraft:brains"
            BREEZE -> "minecraft:breezes"
            GOAL_SELECTOR -> "minecraft:goal_selectors"
            ENTITY_PATH -> "minecraft:entity_paths"
            ENTITY_BLOCK_INTERSECTION ->
                "minecraft:entity_block_intersections"

            BEE_HIVE -> "minecraft:bee_hives"
            POINT_OF_INTEREST -> "minecraft:pois"
            REDSTONE_WIRE_ORIENTATION ->
                "minecraft:redstone_wire_orientations"

            VILLAGE_SECTION -> "minecraft:village_sections"
            RAID -> "minecraft:raids"
            STRUCTURE -> "minecraft:structures"
            GAME_EVENT_LISTENER -> "minecraft:game_event_listeners"
            NEIGHBOR_UPDATE -> "minecraft:neighbor_updates"
            GAME_EVENT -> "minecraft:game_events"
        }
}

@Serializable
sealed interface DebugSubscriptionData {
    @Serializable
    data class Bee(
        val hivePosition: BlockPosition?,
        val flowerPosition: BlockPosition?,
        @VarInt
        val travelTicks: Int,
        val blacklistedHives: List<BlockPosition>,
    ) : DebugSubscriptionData

    @Serializable
    data class VillagerBrain(
        val name: String,
        val profession: String,
        val experience: Int,
        val health: Float,
        val maximumHealth: Float,
        val inventory: String,
        val wantsGolem: Boolean,
        val angerLevel: Int,
        val activities: List<String>,
        val behaviors: List<String>,
        val memories: List<String>,
        val gossips: List<String>,
        val pointsOfInterest: Set<BlockPosition>,
        val potentialPointsOfInterest: Set<BlockPosition>,
    ) : DebugSubscriptionData

    @Serializable
    data class Breeze(
        @VarInt
        val attackTargetEntityId: Int?,
        val jumpTarget: BlockPosition?,
    ) : DebugSubscriptionData

    @Serializable
    data class GoalSelector(
        val goals: List<DebugGoal>,
    ) : DebugSubscriptionData

    @Serializable
    data class EntityPath(
        val reached: Boolean,
        val nextNodeIndex: Int,
        val target: BlockPosition,
        val nodes: List<DebugPathNode>,
        val targetNodes: Set<DebugPathNode>,
        val openSet: List<DebugPathNode>,
        val closedSet: List<DebugPathNode>,
        val maximumNodeDistance: Float,
    ) : DebugSubscriptionData

    @Serializable
    data class EntityBlockIntersection(
        @ZeroFallbackEnum
        val intersection: DebugEntityBlockIntersection,
    ) : DebugSubscriptionData

    @Serializable
    data class BeeHive(
        @VarInt
        val blockTypeId: Int,
        @VarInt
        val occupantCount: Int,
        @VarInt
        val honeyLevel: Int,
        val sedated: Boolean,
    ) : DebugSubscriptionData

    @Serializable
    data class PointOfInterest(
        val position: BlockPosition,
        @VarInt
        val pointOfInterestTypeId: Int,
        @VarInt
        val freeTicketCount: Int,
    ) : DebugSubscriptionData

    @Serializable
    data class RedstoneWireOrientation(
        @VarInt
        val orientationIndex: Int,
    ) : DebugSubscriptionData {
        init {
            require(orientationIndex in 0..47) {
                "A redstone orientation index must be in 0..47"
            }
        }
    }

    @Serializable
    data object VillageSection : DebugSubscriptionData

    @Serializable
    data class Raid(
        val positions: List<BlockPosition>,
    ) : DebugSubscriptionData

    @Serializable
    data class Structures(
        val structures: List<DebugStructure>,
    ) : DebugSubscriptionData

    @Serializable
    data class GameEventListener(
        @VarInt
        val listenerRadius: Int,
    ) : DebugSubscriptionData

    @Serializable
    data class NeighborUpdate(
        val position: BlockPosition,
    ) : DebugSubscriptionData

    @Serializable
    data class GameEvent(
        @VarInt
        val eventTypeId: Int,
        val position: Vector3d,
    ) : DebugSubscriptionData
}

@Serializable
data class DebugGoal(
    @VarInt
    val priority: Int,
    val running: Boolean,
    @MaxLength(255)
    val name: String,
)

@Serializable
enum class DebugPathType {
    BLOCKED,
    OPEN,
    WALKABLE,
    WALKABLE_DOOR,
    TRAPDOOR,
    POWDER_SNOW,
    ON_TOP_OF_POWDER_SNOW,
    FENCE,
    LAVA,
    WATER,
    WATER_BORDER,
    RAIL,
    UNPASSABLE_RAIL,
    FIRE_IN_NEIGHBOR,
    FIRE,
    DAMAGING_IN_NEIGHBOR,
    DAMAGING,
    DOOR_OPEN,
    DOOR_WOOD_CLOSED,
    DOOR_IRON_CLOSED,
    BREACH,
    LEAVES,
    STICKY_HONEY,
    COCOA,
    DAMAGE_CAUTIOUS,
    ON_TOP_OF_TRAPDOOR,
    BIG_MOBS_CLOSE_TO_DANGER,
}

@Serializable
data class DebugPathNode(
    val x: Int,
    val y: Int,
    val z: Int,
    val walkedDistance: Float,
    val costMalus: Float,
    val closed: Boolean,
    val type: DebugPathType,
    val totalCost: Float,
)

@Serializable
enum class DebugEntityBlockIntersection {
    IN_BLOCK,
    IN_FLUID,
    IN_AIR,
}

@Serializable
data class DebugBoundingBox(
    val minimum: BlockPosition,
    val maximum: BlockPosition,
)

@Serializable
data class DebugStructurePiece(
    val boundingBox: DebugBoundingBox,
    val start: Boolean,
)

@Serializable
data class DebugStructure(
    val boundingBox: DebugBoundingBox,
    val pieces: List<DebugStructurePiece>,
)

@Serializable(with = DebugSubscriptionEventSerializer::class)
data class DebugSubscriptionEvent(
    val data: DebugSubscriptionData,
)

@Serializable(with = DebugSubscriptionUpdateSerializer::class)
data class DebugSubscriptionUpdate(
    val type: DebugSubscriptionType,
    val data: DebugSubscriptionData?,
)

internal object DebugSubscriptionEventSerializer :
    KSerializer<DebugSubscriptionEvent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.DebugSubscriptionEvent",
    ) {
        element<DebugSubscriptionType>("type")
        element<DebugSubscriptionData>("data")
    }

    override fun serialize(encoder: Encoder, value: DebugSubscriptionEvent) {
        val type = debugType(value.data)
        val serializer = debugSerializer(type)
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            TYPE,
            DebugSubscriptionType.serializer(),
            type,
        )
        output.encodeSerializableElement(descriptor, DATA, serializer, value.data)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): DebugSubscriptionEvent {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val type = input.decodeSerializableElement(
                descriptor,
                TYPE,
                DebugSubscriptionType.serializer(),
            )
            val data = input.decodeSerializableElement(
                descriptor,
                DATA,
                debugSerializer(type),
            )
            input.endStructure(descriptor)
            return DebugSubscriptionEvent(data)
        }

        var type: DebugSubscriptionType? = null
        var data: DebugSubscriptionData? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                TYPE -> type = input.decodeSerializableElement(
                    descriptor,
                    TYPE,
                    DebugSubscriptionType.serializer(),
                )

                DATA -> data = input.decodeSerializableElement(
                    descriptor,
                    DATA,
                    debugSerializer(
                        type ?: throw SerializationException(
                            "Debug event type must precede its data",
                        ),
                    ),
                )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected DebugSubscriptionEvent field $index",
                )
            }
        }
        input.endStructure(descriptor)
        return DebugSubscriptionEvent(
            data ?: throw SerializationException("Missing debug event data"),
        )
    }
}

internal object DebugSubscriptionUpdateSerializer :
    KSerializer<DebugSubscriptionUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.DebugSubscriptionUpdate",
    ) {
        element<DebugSubscriptionType>("type")
        element<DebugSubscriptionData?>("data")
    }

    override fun serialize(encoder: Encoder, value: DebugSubscriptionUpdate) {
        val serializer = debugSerializer(value.type)
        if (value.data != null && debugType(value.data) != value.type) {
            throw SerializationException(
                "Debug update type ${value.type} does not match ${debugType(value.data)}",
            )
        }
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            TYPE,
            DebugSubscriptionType.serializer(),
            value.type,
        )
        output.encodeNullableSerializableElement(
            descriptor,
            DATA,
            serializer,
            value.data,
        )
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): DebugSubscriptionUpdate {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val type = input.decodeSerializableElement(
                descriptor,
                TYPE,
                DebugSubscriptionType.serializer(),
            )
            val data = input.decodeNullableSerializableElement(
                descriptor,
                DATA,
                debugSerializer(type).nullable,
            )
            input.endStructure(descriptor)
            return DebugSubscriptionUpdate(type, data)
        }

        var type: DebugSubscriptionType? = null
        var data: DebugSubscriptionData? = null
        var sawData = false
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                TYPE -> type = input.decodeSerializableElement(
                    descriptor,
                    TYPE,
                    DebugSubscriptionType.serializer(),
                )

                DATA -> {
                    val actualType = type ?: throw SerializationException(
                        "Debug update type must precede its data",
                    )
                    data = input.decodeNullableSerializableElement(
                        descriptor,
                        DATA,
                        debugSerializer(actualType).nullable,
                    )
                    sawData = true
                }

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected DebugSubscriptionUpdate field $index",
                )
            }
        }
        input.endStructure(descriptor)
        val actualType = type ?: throw SerializationException(
            "Missing debug update type",
        )
        if (!sawData) {
            throw SerializationException("Missing debug update optional data field")
        }
        return DebugSubscriptionUpdate(actualType, data)
    }
}

private fun debugType(data: DebugSubscriptionData): DebugSubscriptionType =
    when (data) {
        is DebugSubscriptionData.Bee -> DebugSubscriptionType.BEE
        is DebugSubscriptionData.VillagerBrain ->
            DebugSubscriptionType.VILLAGER_BRAIN

        is DebugSubscriptionData.Breeze -> DebugSubscriptionType.BREEZE
        is DebugSubscriptionData.GoalSelector ->
            DebugSubscriptionType.GOAL_SELECTOR

        is DebugSubscriptionData.EntityPath -> DebugSubscriptionType.ENTITY_PATH
        is DebugSubscriptionData.EntityBlockIntersection ->
            DebugSubscriptionType.ENTITY_BLOCK_INTERSECTION

        is DebugSubscriptionData.BeeHive -> DebugSubscriptionType.BEE_HIVE
        is DebugSubscriptionData.PointOfInterest ->
            DebugSubscriptionType.POINT_OF_INTEREST

        is DebugSubscriptionData.RedstoneWireOrientation ->
            DebugSubscriptionType.REDSTONE_WIRE_ORIENTATION

        DebugSubscriptionData.VillageSection ->
            DebugSubscriptionType.VILLAGE_SECTION

        is DebugSubscriptionData.Raid -> DebugSubscriptionType.RAID
        is DebugSubscriptionData.Structures -> DebugSubscriptionType.STRUCTURE
        is DebugSubscriptionData.GameEventListener ->
            DebugSubscriptionType.GAME_EVENT_LISTENER

        is DebugSubscriptionData.NeighborUpdate ->
            DebugSubscriptionType.NEIGHBOR_UPDATE

        is DebugSubscriptionData.GameEvent -> DebugSubscriptionType.GAME_EVENT
    }

@Suppress("UNCHECKED_CAST")
private fun debugSerializer(
    type: DebugSubscriptionType,
): KSerializer<DebugSubscriptionData> = when (type) {
    DebugSubscriptionType.DEDICATED_SERVER_TICK_TIME ->
        throw SerializationException(
            "Vanilla has no value codec for dedicated_server_tick_time",
        )

    DebugSubscriptionType.BEE -> DebugSubscriptionData.Bee.serializer()
    DebugSubscriptionType.VILLAGER_BRAIN ->
        DebugSubscriptionData.VillagerBrain.serializer()

    DebugSubscriptionType.BREEZE -> DebugSubscriptionData.Breeze.serializer()
    DebugSubscriptionType.GOAL_SELECTOR ->
        DebugSubscriptionData.GoalSelector.serializer()

    DebugSubscriptionType.ENTITY_PATH ->
        DebugSubscriptionData.EntityPath.serializer()

    DebugSubscriptionType.ENTITY_BLOCK_INTERSECTION ->
        DebugSubscriptionData.EntityBlockIntersection.serializer()

    DebugSubscriptionType.BEE_HIVE ->
        DebugSubscriptionData.BeeHive.serializer()

    DebugSubscriptionType.POINT_OF_INTEREST ->
        DebugSubscriptionData.PointOfInterest.serializer()

    DebugSubscriptionType.REDSTONE_WIRE_ORIENTATION ->
        DebugSubscriptionData.RedstoneWireOrientation.serializer()

    DebugSubscriptionType.VILLAGE_SECTION ->
        DebugSubscriptionData.VillageSection.serializer()

    DebugSubscriptionType.RAID -> DebugSubscriptionData.Raid.serializer()
    DebugSubscriptionType.STRUCTURE ->
        DebugSubscriptionData.Structures.serializer()

    DebugSubscriptionType.GAME_EVENT_LISTENER ->
        DebugSubscriptionData.GameEventListener.serializer()

    DebugSubscriptionType.NEIGHBOR_UPDATE ->
        DebugSubscriptionData.NeighborUpdate.serializer()

    DebugSubscriptionType.GAME_EVENT ->
        DebugSubscriptionData.GameEvent.serializer()
} as KSerializer<DebugSubscriptionData>

private const val TYPE: Int = 0
private const val DATA: Int = 1
