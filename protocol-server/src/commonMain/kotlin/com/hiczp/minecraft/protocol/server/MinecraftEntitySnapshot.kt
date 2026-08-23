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
    val metadata: EntityMetadata? = null,
    val attributes: List<AttributeSnapshot> = emptyList(),
    val equipment: List<EquipmentUpdate> = emptyList(),
    val passengerEntityIds: List<Int> = emptyList(),
    val vehiclePassengerRelation: MinecraftEntityPassengersSnapshot? = null,
    val leashHolderEntityId: Int? = null,
) {
    fun typeId(registries: ProtocolRegistryContext): Int =
        registries.requireRegistryEntry(ProtocolRegistryContext.ENTITY_TYPE_REGISTRY, type).rawId

    /** Builds the raw delimiter-bounded packet sequence for callers that manage its physical enqueueing themselves. */
    fun packets(registries: ProtocolRegistryContext): List<ClientboundPacket> =
        packets(typeId(registries))

    /** Builds the raw delimiter-bounded packet sequence with an already-resolved Entity type ID. */
    fun packets(typeId: Int): List<ClientboundPacket> = buildList {
        add(BundleDelimiterPacket)
        addAll(contentPackets(typeId))
        add(BundleDelimiterPacket)
    }

    /** Builds one logical bundle that a connection enqueues and writes without channel-level interleaving. */
    fun bundle(registries: ProtocolRegistryContext): ClientboundBundlePacket =
        bundle(typeId(registries))

    /** Builds one logical bundle with an already-resolved Entity type ID. */
    fun bundle(typeId: Int): ClientboundBundlePacket = ClientboundBundlePacket(contentPackets(typeId))

    private fun contentPackets(typeId: Int): List<ClientboundPacket> = buildList {
        addContentPackets(this@MinecraftEntitySnapshot, typeId)
    }
}

private fun MutableList<ClientboundPacket>.addContentPackets(
    snapshot: MinecraftEntitySnapshot,
    typeId: Int,
) {
    with(snapshot) {
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
        metadata?.takeIf { it.entries.isNotEmpty() }?.let {
            add(SetEntityMetadataPacket(entityId, EntityMetadata(it.entries.toList())))
        }
        if (attributes.isNotEmpty()) {
            add(
                UpdateAttributesPacket(
                    entityId,
                    attributes.map { attribute -> attribute.copy(modifiers = attribute.modifiers.toList()) },
                ),
            )
        }
        if (equipment.isNotEmpty()) {
            add(SetEquipmentPacket(entityId, EquipmentUpdates(equipment.toList())))
        }
        if (passengerEntityIds.isNotEmpty()) {
            add(SetPassengersPacket(entityId, passengerEntityIds.toList()))
        }
        vehiclePassengerRelation?.let { relation -> add(relation.packet()) }
        leashHolderEntityId?.let { holderId -> add(LinkEntitiesPacket(entityId, holderId)) }
    }
}

/** Builds one logical bundle containing every Entity snapshot in iteration order. */
fun Iterable<MinecraftEntitySnapshot>.bundle(
    registries: ProtocolRegistryContext,
): ClientboundBundlePacket = ClientboundBundlePacket(
    buildList {
        this@bundle.forEach { snapshot ->
            addContentPackets(snapshot, snapshot.typeId(registries))
        }
    },
)

/**
 * Snapshots caller-owned runtime Entities and builds one logical bundle in iteration order.
 *
 * [snapshotOf] supplies connection-local protocol state that a semantic [Entity] does not own.
 */
fun <E : Any> Iterable<Entity<E>>.toMinecraftEntityBundle(
    registries: ProtocolRegistryContext,
    snapshotOf: (Entity<E>) -> MinecraftEntitySnapshot,
): ClientboundBundlePacket = ClientboundBundlePacket(
    buildList {
        this@toMinecraftEntityBundle.forEach { entity ->
            val snapshot = snapshotOf(entity)
            addContentPackets(snapshot, snapshot.typeId(registries))
        }
    },
)

/** One vehicle-to-passenger relationship included in an Entity pairing bundle. */
data class MinecraftEntityPassengersSnapshot(
    val vehicleEntityId: Int,
    val passengerEntityIds: List<Int>,
) {
    fun packet(): SetPassengersPacket = SetPassengersPacket(vehicleEntityId, passengerEntityIds.toList())
}

/**
 * Converts a persisted semantic Entity into detached common spawn state.
 *
 * Runtime-only protocol state is supplied explicitly. In particular, persisted NBT does not contain the connection's
 * numeric Entity ID, registry-resolved attributes, protocol ItemStacks, metadata indices, or current tracking links.
 */
fun <E : Any> Entity<E>.toMinecraftEntitySnapshot(
    entityId: Int,
    headYaw: Float = rotation.yaw,
    data: Int = 0,
    metadata: EntityMetadata? = null,
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
    pitch = rotation.pitch,
    yaw = rotation.yaw,
    headYaw = headYaw,
    data = data,
    metadata = metadata?.let { value -> EntityMetadata(value.entries.toList()) },
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
suspend fun MinecraftServerConnection.sendEntitySnapshot(snapshot: MinecraftEntitySnapshot) {
    outgoing.send(snapshot.bundle(registries))
}

/** Enqueues one bundle containing every Entity snapshot in iteration order. */
suspend fun MinecraftServerConnection.sendEntitySnapshots(snapshots: Iterable<MinecraftEntitySnapshot>) {
    outgoing.send(snapshots.bundle(registries))
}
