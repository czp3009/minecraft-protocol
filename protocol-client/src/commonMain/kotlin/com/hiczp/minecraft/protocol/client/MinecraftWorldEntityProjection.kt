package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityRotation
import com.hiczp.minecraft.world.format.EntityVector3d

/**
 * Adapts one vanilla Entity pairing bundle to caller-owned runtime Entity state.
 *
 * [createData] supplies the runtime subtype value installed in the strong [Entity]. [registerEntity] runs after that
 * Entity has been created and before any trailing pairing packet is applied, matching the official client's lookup
 * behavior. The remaining callbacks run once per matching packet in received order; their relative order is not
 * validated. Relationship packets can refer to Entities other than the newly registered one, so adapters normally
 * capture the caller's Entity table.
 */
interface MinecraftEntityPacketAdapter<E : Any> {
    fun createData(packet: SpawnEntityPacket, type: Identifier): E

    fun registerEntity(packet: SpawnEntityPacket, entity: Entity<E>) {}

    fun applyMetadata(entity: Entity<E>, packet: SetEntityMetadataPacket) {}

    fun applyAttributes(entity: Entity<E>, packet: UpdateAttributesPacket) {}

    fun applyEquipment(entity: Entity<E>, packet: SetEquipmentPacket) {}

    fun applyPassengers(entity: Entity<E>, packet: SetPassengersPacket) {}

    fun applyLink(entity: Entity<E>, packet: LinkEntitiesPacket) {}

    fun applyOther(entity: Entity<E>, packet: ClientboundPacket) {}
}

/**
 * Stateless projection of clientbound Entity spawn packets into strong world Entities.
 *
 * The decoder resolves the packet's numeric Entity type through the active connection registry. The returned Entity
 * contains the semantic state shared with world storage: type, UUID, position, velocity, yaw, and pitch. The adapter
 * overload routes runtime-only pairing state into the caller's client simulation.
 */
class MinecraftEntityPacketDecoder(
    val registries: ProtocolRegistryContext,
) {
    private val entityTypes = registries.requireRegistry(ProtocolRegistryContext.ENTITY_TYPE_REGISTRY)

    fun decode(packet: SpawnEntityPacket): Entity<NbtCompound> =
        decode(packet, NbtCompound(emptyMap()))

    /** Decodes common spawn state without adapting trailing runtime packets. */
    fun decode(bundle: ClientboundBundlePacket): Entity<NbtCompound> = decode(bundle.spawnEntityPacket())

    /** Returns null when [bundle] is not a vanilla Entity pairing bundle. */
    fun decodeOrNull(bundle: ClientboundBundlePacket): Entity<NbtCompound>? =
        bundle.spawnEntityPacketOrNull()?.let(::decode)

    /** Decodes common spawn state while installing caller-owned runtime subtype data. */
    fun <E : Any> decode(packet: SpawnEntityPacket, data: E): Entity<E> {
        val type = type(packet)
        return createEntity(packet, type, data)
    }

    /** Creates, registers, and adapts one complete vanilla Entity pairing bundle. */
    fun <E : Any> decode(
        bundle: ClientboundBundlePacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E> = decode(bundle, bundle.spawnEntityPacket(), adapter)

    /** Returns null when [bundle] is not a vanilla Entity pairing bundle. */
    fun <E : Any> decodeOrNull(
        bundle: ClientboundBundlePacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E>? {
        val packet = bundle.spawnEntityPacketOrNull() ?: return null
        return decode(bundle, packet, adapter)
    }

    private fun <E : Any> decode(
        bundle: ClientboundBundlePacket,
        packet: SpawnEntityPacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E> {
        val type = type(packet)
        val entity = createEntity(packet, type, adapter.createData(packet, type))
        adapter.registerEntity(packet, entity)
        bundle.applyEntityPairingPackets(entity, adapter)
        return entity
    }

    private fun type(packet: SpawnEntityPacket): Identifier = entityTypes[packet.typeId]?.id
        ?: throw IllegalArgumentException("Entity type registry ID ${packet.typeId} has no installed entry")

    private fun <E : Any> createEntity(
        packet: SpawnEntityPacket,
        type: Identifier,
        data: E,
    ): Entity<E> = Entity(
        type = type.value,
        uuid = packet.entityUuid,
        data = data,
        position = EntityVector3d(packet.x, packet.y, packet.z),
        velocity = EntityVector3d(packet.velocity.x, packet.velocity.y, packet.velocity.z),
        rotation = EntityRotation(
            yaw = packet.yaw.degrees,
            pitch = packet.pitch.degrees,
        ),
    )

    /** Decodes common spawn state with caller-owned data without adapting trailing runtime packets. */
    fun <E : Any> decode(bundle: ClientboundBundlePacket, data: E): Entity<E> =
        decode(bundle.spawnEntityPacket(), data)

    /** Returns null when [bundle] is not a vanilla Entity pairing bundle. */
    fun <E : Any> decodeOrNull(bundle: ClientboundBundlePacket, data: E): Entity<E>? =
        bundle.spawnEntityPacketOrNull()?.let { packet -> decode(packet, data) }
}

/**
 * Returns the spawn packet when this bundle uses the vanilla Entity pairing shape.
 *
 * Vanilla identifies that shape by placing [SpawnEntityPacket] first. The remaining packets are intentionally not
 * scanned or order-validated; callers dispatch their metadata, attributes, equipment, and relationship updates by
 * packet type.
 */
fun ClientboundBundlePacket.spawnEntityPacketOrNull(): SpawnEntityPacket? =
    subPackets.firstOrNull() as? SpawnEntityPacket

/** Whether this bundle uses the vanilla Entity pairing shape. */
val ClientboundBundlePacket.isEntityPairingBundle: Boolean
    get() = spawnEntityPacketOrNull() != null

/** Returns the leading spawn packet or fails when this is not a vanilla Entity pairing bundle. */
fun ClientboundBundlePacket.spawnEntityPacket(): SpawnEntityPacket = requireNotNull(spawnEntityPacketOrNull()) {
    "An Entity pairing bundle must begin with SpawnEntityPacket"
}

/**
 * Applies the trailing packets of this Entity pairing bundle to an already created and registered [entity].
 *
 * This is the primitive counterpart to the decoder's one-step adapter overload. It performs no buffering, grouping,
 * or tail-order validation.
 */
fun <E : Any> ClientboundBundlePacket.applyEntityPairingPackets(
    entity: Entity<E>,
    adapter: MinecraftEntityPacketAdapter<E>,
) {
    spawnEntityPacket()
    applyTrailingEntityPairingPackets(entity, adapter)
}

private fun <E : Any> ClientboundBundlePacket.applyTrailingEntityPairingPackets(
    entity: Entity<E>,
    adapter: MinecraftEntityPacketAdapter<E>,
) {
    for (index in 1 until subPackets.size) {
        when (val packet = subPackets[index]) {
            is SetEntityMetadataPacket -> adapter.applyMetadata(entity, packet)
            is UpdateAttributesPacket -> adapter.applyAttributes(entity, packet)
            is SetEquipmentPacket -> adapter.applyEquipment(entity, packet)
            is SetPassengersPacket -> adapter.applyPassengers(entity, packet)
            is LinkEntitiesPacket -> adapter.applyLink(entity, packet)
            else -> adapter.applyOther(entity, packet)
        }
    }
}

/** Fluent clientbound spawn packet to strong world-Entity conversion. */
fun SpawnEntityPacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound> = decoder.decode(this)

/** Fluent clientbound spawn packet to strong world-Entity conversion with caller-owned subtype data. */
fun <E : Any> SpawnEntityPacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E> = decoder.decode(this, data)

/** Fluent complete Entity pairing bundle to strong world-Entity conversion. */
fun ClientboundBundlePacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound> = decoder.decode(this)

/** Returns null when this is not a vanilla Entity pairing bundle. */
fun ClientboundBundlePacket.toEntityOrNull(
    decoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound>? = decoder.decodeOrNull(this)

/** Creates, registers, and adapts one complete vanilla Entity pairing bundle. */
fun <E : Any> ClientboundBundlePacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): Entity<E> = decoder.decode(this, adapter)

/** Returns null when this is not a vanilla Entity pairing bundle. */
fun <E : Any> ClientboundBundlePacket.toEntityOrNull(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): Entity<E>? = decoder.decodeOrNull(this, adapter)

/** Fluent complete Entity pairing bundle to strong world-Entity conversion with caller-owned subtype data. */
fun <E : Any> ClientboundBundlePacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E> = decoder.decode(this, data)

/** Returns null when this is not a vanilla Entity pairing bundle. */
fun <E : Any> ClientboundBundlePacket.toEntityOrNull(
    decoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E>? = decoder.decodeOrNull(this, data)
