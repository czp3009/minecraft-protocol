package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityRotation
import com.hiczp.minecraft.world.format.EntityVector3d

/**
 * Adapts one or more Entity pairing sequences to caller-owned runtime Entity state.
 *
 * [createData] supplies the runtime subtype value installed in each strong [Entity]. [registerEntity] runs after that
 * Entity has been created and before its trailing pairing packets are applied, matching the official client's lookup
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
 * Stateless projection of clientbound Entity spawn and pairing packets into strong world Entities.
 *
 * The decoder resolves numeric Entity types through the active connection registry. Each returned Entity contains the
 * semantic state shared with world storage: type, UUID, position, velocity, yaw, and pitch. Adapter overloads route
 * runtime-only pairing state into the caller's client simulation.
 */
class MinecraftEntityPacketDecoder(
    val registries: ProtocolRegistryContext,
) {
    private val entityTypes = registries.requireRegistry(ProtocolRegistryContext.ENTITY_TYPE_REGISTRY)

    fun decode(packet: SpawnEntityPacket): Entity<NbtCompound> = decode(packet, NbtCompound(emptyMap()))

    /** Decodes one pairing bundle and requires it to contain exactly one Entity. */
    fun decode(bundle: ClientboundBundlePacket): Entity<NbtCompound> =
        decode(requireSingleSpawnEntityPacket(bundle))

    /** Returns the Entity only when [bundle] contains exactly one pairing sequence. */
    fun decodeOrNull(bundle: ClientboundBundlePacket): Entity<NbtCompound>? =
        bundle.singleSpawnEntityPacketOrNull()?.let(::decode)

    /** Decodes every pairing sequence in [bundle] without adapting runtime-only packets. */
    fun decodeEntities(bundle: ClientboundBundlePacket): List<Entity<NbtCompound>> = decodeEntities(bundle.subPackets)

    /** Returns null when [bundle] does not begin with an Entity pairing sequence. */
    fun decodeEntitiesOrNull(bundle: ClientboundBundlePacket): List<Entity<NbtCompound>>? =
        decodeEntitiesOrNull(bundle.subPackets)

    /** Decodes every pairing sequence in an unwrapped packet stream. */
    fun decodeEntities(packets: Iterable<ClientboundPacket>): List<Entity<NbtCompound>> =
        requireNotNull(decodeEntitiesOrNull(packets)) {
            "Entity pairing packets must begin with SpawnEntityPacket"
        }

    /** Returns null when [packets] does not begin with an Entity pairing sequence. */
    fun decodeEntitiesOrNull(packets: Iterable<ClientboundPacket>): List<Entity<NbtCompound>>? {
        val iterator = packets.iterator()
        val firstPacket = iterator.nextOrNull() as? SpawnEntityPacket ?: return null
        return buildList {
            add(decode(firstPacket))
            while (iterator.hasNext()) {
                val packet = iterator.next()
                if (packet is SpawnEntityPacket) add(decode(packet))
            }
        }
    }

    /** Decodes common spawn state while installing caller-owned runtime subtype data. */
    fun <E : Any> decode(packet: SpawnEntityPacket, data: E): Entity<E> {
        val type = type(packet)
        return createEntity(packet, type, data)
    }

    /** Creates, registers, and adapts one bundle containing exactly one Entity pairing sequence. */
    fun <E : Any> decode(
        bundle: ClientboundBundlePacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E> = requireSingleEntity(decodeEntities(bundle, adapter))

    /** Returns the Entity only when [bundle] contains exactly one pairing sequence. */
    fun <E : Any> decodeOrNull(
        bundle: ClientboundBundlePacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E>? = decodeEntitiesOrNull(bundle, adapter)?.singleOrNull()

    /** Creates, registers, and adapts every Entity pairing sequence in [bundle]. */
    fun <E : Any> decodeEntities(
        bundle: ClientboundBundlePacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>> = decodeEntities(bundle.subPackets, adapter)

    /** Returns null when [bundle] does not begin with an Entity pairing sequence. */
    fun <E : Any> decodeEntitiesOrNull(
        bundle: ClientboundBundlePacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>>? = decodeEntitiesOrNull(bundle.subPackets, adapter)

    /** Creates, registers, and adapts every pairing sequence in an unwrapped packet stream. */
    fun <E : Any> decodeEntities(
        packets: Iterable<ClientboundPacket>,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>> = requireNotNull(decodeEntitiesOrNull(packets, adapter)) {
        "Entity pairing packets must begin with SpawnEntityPacket"
    }

    /** Returns null when [packets] does not begin with an Entity pairing sequence. */
    fun <E : Any> decodeEntitiesOrNull(
        packets: Iterable<ClientboundPacket>,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>>? {
        val iterator = packets.iterator()
        val firstPacket = iterator.nextOrNull() as? SpawnEntityPacket ?: return null
        var entity = createAndRegister(firstPacket, adapter)
        return buildList {
            add(entity)
            while (iterator.hasNext()) {
                when (val packet = iterator.next()) {
                    is SpawnEntityPacket -> {
                        entity = createAndRegister(packet, adapter)
                        add(entity)
                    }

                    else -> applyEntityPairingPacket(entity, packet, adapter)
                }
            }
        }
    }

    /** Decodes common spawn state with caller-owned data without adapting trailing runtime packets. */
    fun <E : Any> decode(bundle: ClientboundBundlePacket, data: E): Entity<E> =
        decode(requireSingleSpawnEntityPacket(bundle), data)

    /** Returns the Entity only when [bundle] contains exactly one pairing sequence. */
    fun <E : Any> decodeOrNull(bundle: ClientboundBundlePacket, data: E): Entity<E>? =
        bundle.singleSpawnEntityPacketOrNull()?.let { packet -> decode(packet, data) }

    private fun <E : Any> createAndRegister(
        packet: SpawnEntityPacket,
        adapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E> {
        val type = type(packet)
        val entity = createEntity(packet, type, adapter.createData(packet, type))
        adapter.registerEntity(packet, entity)
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
}

/**
 * Returns the first spawn packet when this bundle uses an Entity pairing shape.
 *
 * The matching official server emits one Entity per bundle, while the bundle format and official client also accept
 * several consecutive pairing sequences. The remaining packets are intentionally not order-validated.
 */
fun ClientboundBundlePacket.spawnEntityPacketOrNull(): SpawnEntityPacket? =
    subPackets.firstOrNull() as? SpawnEntityPacket

/** Every spawn packet in this bundle, in received order. */
fun ClientboundBundlePacket.spawnEntityPackets(): Sequence<SpawnEntityPacket> =
    subPackets.asSequence().filterIsInstance<SpawnEntityPacket>()

/** Whether this bundle begins with one or more Entity pairing sequences. */
val ClientboundBundlePacket.isEntityPairingBundle: Boolean
    get() = spawnEntityPacketOrNull() != null

/** Returns the leading spawn packet or fails when this is not an Entity pairing bundle. */
fun ClientboundBundlePacket.spawnEntityPacket(): SpawnEntityPacket = requireNotNull(spawnEntityPacketOrNull()) {
    "Entity pairing packets must begin with SpawnEntityPacket"
}

/**
 * Applies one pairing sequence to an already created and registered [entity].
 *
 * The iterable must include its leading [SpawnEntityPacket] and no second Spawn. This primitive performs no buffering
 * or tail-order validation. Use [toEntities] when a bundle or raw packet list may contain several pairing sequences.
 */
fun <E : Any> Iterable<ClientboundPacket>.applyEntityPairingPackets(
    entity: Entity<E>,
    adapter: MinecraftEntityPacketAdapter<E>,
) {
    val iterator = iterator()
    require(iterator.nextOrNull() is SpawnEntityPacket) {
        "Entity pairing packets must begin with SpawnEntityPacket"
    }
    while (iterator.hasNext()) {
        val packet = iterator.next()
        require(packet !is SpawnEntityPacket) {
            "applyEntityPairingPackets accepts exactly one Entity pairing sequence"
        }
        applyEntityPairingPacket(entity, packet, adapter)
    }
}

private fun <E : Any> applyEntityPairingPacket(
    entity: Entity<E>,
    packet: ClientboundPacket,
    adapter: MinecraftEntityPacketAdapter<E>,
) {
    when (packet) {
        is SetEntityMetadataPacket -> adapter.applyMetadata(entity, packet)
        is UpdateAttributesPacket -> adapter.applyAttributes(entity, packet)
        is SetEquipmentPacket -> adapter.applyEquipment(entity, packet)
        is SetPassengersPacket -> adapter.applyPassengers(entity, packet)
        is LinkEntitiesPacket -> adapter.applyLink(entity, packet)
        else -> adapter.applyOther(entity, packet)
    }
}

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null

private fun ClientboundBundlePacket.singleSpawnEntityPacketOrNull(): SpawnEntityPacket? {
    if (!isEntityPairingBundle) return null
    val iterator = spawnEntityPackets().iterator()
    val packet = iterator.next()
    return if (iterator.hasNext()) null else packet
}

private fun requireSingleSpawnEntityPacket(bundle: ClientboundBundlePacket): SpawnEntityPacket =
    requireNotNull(bundle.singleSpawnEntityPacketOrNull()) {
        "An Entity pairing bundle must begin with and contain exactly one SpawnEntityPacket"
    }

private fun <E : Any> requireSingleEntity(entities: List<Entity<E>>): Entity<E> {
    require(entities.size == 1) {
        "Exactly one Entity pairing sequence was required, but found ${entities.size}"
    }
    return entities.single()
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

/** Fluent bundle containing exactly one Entity pairing sequence to a strong world Entity. */
fun ClientboundBundlePacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound> = decoder.decode(this)

/** Returns the Entity only when this bundle contains exactly one pairing sequence. */
fun ClientboundBundlePacket.toEntityOrNull(
    decoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound>? = decoder.decodeOrNull(this)

/** Creates, registers, and adapts a bundle containing exactly one Entity pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): Entity<E> = decoder.decode(this, adapter)

/** Returns the adapted Entity only when this bundle contains exactly one pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntityOrNull(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): Entity<E>? = decoder.decodeOrNull(this, adapter)

/** Fluent bundle containing exactly one Entity pairing sequence with caller-owned subtype data. */
fun <E : Any> ClientboundBundlePacket.toEntity(
    decoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E> = decoder.decode(this, data)

/** Returns the Entity only when this bundle contains exactly one pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntityOrNull(
    decoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E>? = decoder.decodeOrNull(this, data)

/** Decodes every Entity pairing sequence in this bundle. */
fun ClientboundBundlePacket.toEntities(
    decoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>> = decoder.decodeEntities(this)

/** Returns null when this bundle does not begin with an Entity pairing sequence. */
fun ClientboundBundlePacket.toEntitiesOrNull(
    decoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>>? = decoder.decodeEntitiesOrNull(this)

/** Creates, registers, and adapts every Entity pairing sequence in this bundle. */
fun <E : Any> ClientboundBundlePacket.toEntities(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>> = decoder.decodeEntities(this, adapter)

/** Returns null when this bundle does not begin with an Entity pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntitiesOrNull(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>>? = decoder.decodeEntitiesOrNull(this, adapter)

/** Decodes every Entity pairing sequence in this bundle or raw packet list. */
fun Iterable<ClientboundPacket>.toEntities(
    decoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>> = decoder.decodeEntities(this)

/** Returns null when these packets do not begin with an Entity pairing sequence. */
fun Iterable<ClientboundPacket>.toEntitiesOrNull(
    decoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>>? = decoder.decodeEntitiesOrNull(this)

/** Creates, registers, and adapts every Entity pairing sequence in this bundle or raw packet list. */
fun <E : Any> Iterable<ClientboundPacket>.toEntities(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>> = decoder.decodeEntities(this, adapter)

/** Returns null when these packets do not begin with an Entity pairing sequence. */
fun <E : Any> Iterable<ClientboundPacket>.toEntitiesOrNull(
    decoder: MinecraftEntityPacketDecoder,
    adapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>>? = decoder.decodeEntitiesOrNull(this, adapter)
