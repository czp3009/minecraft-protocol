package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.Entity
import kotlin.uuid.Uuid

/**
 * Detached client-facing state for the complete vanilla Entity pairing bundle.
 *
 * Parameters follow the final packet sequence: spawn fields, metadata, attributes, equipment, the Entity's passenger
 * relation, its vehicle's passenger relation, and its leash relation.
 */
data class MinecraftEntitySnapshot(
    val entityId: Int,
    val uuid: Uuid,
    val type: Identifier,
    val position: Vector3d,
    val velocity: Vector3d = Vector3d(0.0, 0.0, 0.0),
    val pitch: Float = 0.0f,
    val yaw: Float = 0.0f,
    val headYaw: Float = yaw,
    val data: Int = 0,
    val entityMetadata: EntityMetadata? = null,
    val attributes: List<AttributeSnapshot> = emptyList(),
    val equipment: List<EquipmentUpdate> = emptyList(),
    val passengerEntityIds: List<Int> = emptyList(),
    val vehiclePassengerRelation: MinecraftEntityPassengersSnapshot? = null,
    val leashHolderEntityId: Int? = null,
) {
    fun typeId(protocolRegistryContext: ProtocolRegistryContext): Int =
        protocolRegistryContext.requireRegistryEntry(ProtocolRegistryContext.ENTITY_TYPE_REGISTRY, type).rawId

    /** Builds the raw delimiter-bounded packet sequence for callers that manage its physical enqueueing themselves. */
    fun packets(protocolRegistryContext: ProtocolRegistryContext): List<ClientboundPacket> =
        packets(typeId(protocolRegistryContext))

    /** Builds the raw delimiter-bounded packet sequence with an already-resolved Entity type ID. */
    fun packets(typeId: Int): List<ClientboundPacket> = buildList {
        add(BundleDelimiterPacket)
        addAll(contentPackets(typeId))
        add(BundleDelimiterPacket)
    }

    /** Builds one logical bundle that a connection enqueues and writes without channel-level interleaving. */
    fun bundle(protocolRegistryContext: ProtocolRegistryContext): ClientboundBundlePacket =
        bundle(typeId(protocolRegistryContext))

    /** Builds one logical bundle with an already-resolved Entity type ID. */
    fun bundle(typeId: Int): ClientboundBundlePacket = ClientboundBundlePacket(contentPackets(typeId))

    private fun contentPackets(typeId: Int): List<ClientboundPacket> = buildList {
        addContentPackets(this@MinecraftEntitySnapshot, typeId)
    }
}

private fun MutableList<ClientboundPacket>.addContentPackets(
    minecraftEntitySnapshot: MinecraftEntitySnapshot,
    typeId: Int,
) {
    with(minecraftEntitySnapshot) {
        add(
            SpawnEntityPacket(
                entityId = entityId,
                entityUuid = uuid,
                typeId = typeId,
                x = position.x,
                y = position.y,
                z = position.z,
                velocity = velocity,
                pitch = Angle.fromDegrees(pitch),
                yaw = Angle.fromDegrees(yaw),
                headYaw = Angle.fromDegrees(headYaw),
                data = data,
            ),
        )
        entityMetadata?.takeIf { it.entries.isNotEmpty() }?.let {
            add(SetEntityMetadataPacket(entityId, it))
        }
        if (attributes.isNotEmpty()) {
            add(UpdateAttributesPacket(entityId, attributes))
        }
        if (equipment.isNotEmpty()) {
            add(SetEquipmentPacket(entityId, EquipmentUpdates(equipment)))
        }
        if (passengerEntityIds.isNotEmpty()) {
            add(SetPassengersPacket(entityId, passengerEntityIds))
        }
        vehiclePassengerRelation?.let { relation -> add(relation.packet()) }
        leashHolderEntityId?.let { holderId -> add(LinkEntitiesPacket(entityId, holderId)) }
    }
}

/** Builds one logical bundle containing every Entity snapshot in iteration order. */
fun Iterable<MinecraftEntitySnapshot>.bundle(
    protocolRegistryContext: ProtocolRegistryContext,
): ClientboundBundlePacket = ClientboundBundlePacket(
    buildList {
        this@bundle.forEach { minecraftEntitySnapshot ->
            addContentPackets(
                minecraftEntitySnapshot,
                minecraftEntitySnapshot.typeId(protocolRegistryContext),
            )
        }
    },
)

/**
 * Snapshots caller-owned runtime Entities and builds one logical bundle in iteration order.
 *
 * [minecraftEntitySnapshotOf] supplies connection-local protocol state that a semantic [Entity] does not own.
 */
fun <E : Any> Iterable<Entity<E>>.toMinecraftEntityBundle(
    protocolRegistryContext: ProtocolRegistryContext,
    minecraftEntitySnapshotOf: (Entity<E>) -> MinecraftEntitySnapshot,
): ClientboundBundlePacket = ClientboundBundlePacket(
    buildList {
        this@toMinecraftEntityBundle.forEach { entity ->
            val minecraftEntitySnapshot = minecraftEntitySnapshotOf(entity)
            addContentPackets(
                minecraftEntitySnapshot,
                minecraftEntitySnapshot.typeId(protocolRegistryContext),
            )
        }
    },
)

/** One vehicle-to-passenger relationship included in an Entity pairing bundle. */
data class MinecraftEntityPassengersSnapshot(
    val vehicleEntityId: Int,
    val passengerEntityIds: List<Int>,
) {
    fun packet(): SetPassengersPacket = SetPassengersPacket(vehicleEntityId, passengerEntityIds)
}

/**
 * Converts a persisted semantic Entity into detached common spawn state.
 *
 * Runtime-only protocol state is supplied explicitly. In particular, persisted NBT does not contain the connection's
 * numeric Entity ID, registry-resolved attributes, protocol ItemStacks, metadata indices, or current tracking links.
 */
fun <E : Any> Entity<E>.toMinecraftEntitySnapshot(
    entityId: Int,
    headYaw: Float = entityRotation.yaw,
    data: Int = 0,
    entityMetadata: EntityMetadata? = null,
    attributes: List<AttributeSnapshot> = emptyList(),
    equipment: List<EquipmentUpdate> = emptyList(),
    passengerEntityIds: List<Int> = emptyList(),
    vehiclePassengerRelation: MinecraftEntityPassengersSnapshot? = null,
    leashHolderEntityId: Int? = null,
): MinecraftEntitySnapshot = MinecraftEntitySnapshot(
    entityId = entityId,
    uuid = uuid,
    type = Identifier(type),
    position = Vector3d(position.x, position.y, position.z),
    velocity = Vector3d(velocity.x, velocity.y, velocity.z),
    pitch = entityRotation.pitch,
    yaw = entityRotation.yaw,
    headYaw = headYaw,
    data = data,
    entityMetadata = entityMetadata?.let { value -> EntityMetadata(value.entries.toList()) },
    attributes = attributes.map { attribute ->
        attribute.copy(modifiers = attribute.modifiers.toList())
    },
    equipment = equipment.toList(),
    passengerEntityIds = passengerEntityIds.toList(),
    vehiclePassengerRelation = vehiclePassengerRelation?.let { relation ->
        relation.copy(passengerEntityIds = relation.passengerEntityIds.toList())
    },
    leashHolderEntityId = leashHolderEntityId,
)

/** Enqueues one complete Entity pairing bundle in protocol order. */
suspend fun MinecraftServerConnection.sendEntitySnapshot(minecraftEntitySnapshot: MinecraftEntitySnapshot) {
    outgoing.send(minecraftEntitySnapshot.bundle(protocolRegistryContext))
}

/** Enqueues one bundle containing every Entity snapshot in iteration order. */
suspend fun MinecraftServerConnection.sendEntitySnapshots(minecraftEntitySnapshots: Iterable<MinecraftEntitySnapshot>) {
    outgoing.send(minecraftEntitySnapshots.bundle(protocolRegistryContext))
}
