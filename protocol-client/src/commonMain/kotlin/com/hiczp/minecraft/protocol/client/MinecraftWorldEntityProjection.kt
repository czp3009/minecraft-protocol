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
    fun createData(spawnEntityPacket: SpawnEntityPacket, type: Identifier): E

    fun registerEntity(spawnEntityPacket: SpawnEntityPacket, entity: Entity<E>) {}

    fun applyMetadata(entity: Entity<E>, setEntityMetadataPacket: SetEntityMetadataPacket) {}

    fun applyAttributes(entity: Entity<E>, updateAttributesPacket: UpdateAttributesPacket) {}

    fun applyEquipment(entity: Entity<E>, setEquipmentPacket: SetEquipmentPacket) {}

    fun applyPassengers(entity: Entity<E>, setPassengersPacket: SetPassengersPacket) {}

    fun applyLink(entity: Entity<E>, linkEntitiesPacket: LinkEntitiesPacket) {}

    fun applyOther(entity: Entity<E>, clientboundPacket: ClientboundPacket) {}
}

/**
 * Stateless projection of clientbound Entity spawn and pairing packets into strong world Entities.
 *
 * The decoder resolves numeric Entity types through the active connection registry. Each returned Entity contains the
 * semantic state shared with world storage: type, UUID, position, velocity, yaw, and pitch. Adapter overloads route
 * runtime-only pairing state into the caller's client simulation.
 */
class MinecraftEntityPacketDecoder(
    val protocolRegistryContext: ProtocolRegistryContext,
) {
    private val entityTypeProtocolRegistry =
        protocolRegistryContext.requireRegistry(ProtocolRegistryContext.ENTITY_TYPE_REGISTRY)

    fun decode(spawnEntityPacket: SpawnEntityPacket): Entity<NbtCompound> = decode(spawnEntityPacket, NbtCompound(emptyMap()))

    /** Decodes one pairing bundle and requires it to contain exactly one Entity. */
    fun decode(clientboundBundlePacket: ClientboundBundlePacket): Entity<NbtCompound> =
        decode(requireSingleSpawnEntityPacket(clientboundBundlePacket))

    /** Returns the Entity only when [clientboundBundlePacket] contains exactly one pairing sequence. */
    fun decodeOrNull(clientboundBundlePacket: ClientboundBundlePacket): Entity<NbtCompound>? =
        clientboundBundlePacket.singleSpawnEntityPacketOrNull()?.let(::decode)

    /** Decodes every pairing sequence in [clientboundBundlePacket] without adapting runtime-only packets. */
    fun decodeEntities(clientboundBundlePacket: ClientboundBundlePacket): List<Entity<NbtCompound>> = decodeEntities(clientboundBundlePacket.subPackets)

    /** Returns null when [clientboundBundlePacket] does not begin with an Entity pairing sequence. */
    fun decodeEntitiesOrNull(clientboundBundlePacket: ClientboundBundlePacket): List<Entity<NbtCompound>>? =
        decodeEntitiesOrNull(clientboundBundlePacket.subPackets)

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
                val clientboundPacket = iterator.next()
                if (clientboundPacket is SpawnEntityPacket) add(decode(clientboundPacket))
            }
        }
    }

    /** Decodes common spawn state while installing caller-owned runtime subtype data. */
    fun <E : Any> decode(spawnEntityPacket: SpawnEntityPacket, data: E): Entity<E> {
        val type = type(spawnEntityPacket)
        return createEntity(spawnEntityPacket, type, data)
    }

    /** Creates, registers, and adapts one bundle containing exactly one Entity pairing sequence. */
    fun <E : Any> decode(
        clientboundBundlePacket: ClientboundBundlePacket,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E> = requireSingleEntity(decodeEntities(clientboundBundlePacket, minecraftEntityPacketAdapter))

    /** Returns the Entity only when [clientboundBundlePacket] contains exactly one pairing sequence. */
    fun <E : Any> decodeOrNull(
        clientboundBundlePacket: ClientboundBundlePacket,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E>? = decodeEntitiesOrNull(clientboundBundlePacket, minecraftEntityPacketAdapter)?.singleOrNull()

    /** Creates, registers, and adapts every Entity pairing sequence in [clientboundBundlePacket]. */
    fun <E : Any> decodeEntities(
        clientboundBundlePacket: ClientboundBundlePacket,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>> = decodeEntities(clientboundBundlePacket.subPackets, minecraftEntityPacketAdapter)

    /** Returns null when [clientboundBundlePacket] does not begin with an Entity pairing sequence. */
    fun <E : Any> decodeEntitiesOrNull(
        clientboundBundlePacket: ClientboundBundlePacket,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>>? = decodeEntitiesOrNull(clientboundBundlePacket.subPackets, minecraftEntityPacketAdapter)

    /** Creates, registers, and adapts every pairing sequence in an unwrapped packet stream. */
    fun <E : Any> decodeEntities(
        packets: Iterable<ClientboundPacket>,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>> = requireNotNull(decodeEntitiesOrNull(packets, minecraftEntityPacketAdapter)) {
        "Entity pairing packets must begin with SpawnEntityPacket"
    }

    /** Returns null when [packets] does not begin with an Entity pairing sequence. */
    fun <E : Any> decodeEntitiesOrNull(
        packets: Iterable<ClientboundPacket>,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): List<Entity<E>>? {
        val iterator = packets.iterator()
        val firstPacket = iterator.nextOrNull() as? SpawnEntityPacket ?: return null
        var entity = createAndRegister(firstPacket, minecraftEntityPacketAdapter)
        return buildList {
            add(entity)
            while (iterator.hasNext()) {
                when (val clientboundPacket = iterator.next()) {
                    is SpawnEntityPacket -> {
                        entity = createAndRegister(clientboundPacket, minecraftEntityPacketAdapter)
                        add(entity)
                    }

                    else -> applyEntityPairingPacket(entity, clientboundPacket, minecraftEntityPacketAdapter)
                }
            }
        }
    }

    /** Decodes common spawn state with caller-owned data without adapting trailing runtime packets. */
    fun <E : Any> decode(clientboundBundlePacket: ClientboundBundlePacket, data: E): Entity<E> =
        decode(requireSingleSpawnEntityPacket(clientboundBundlePacket), data)

    /** Returns the Entity only when [clientboundBundlePacket] contains exactly one pairing sequence. */
    fun <E : Any> decodeOrNull(clientboundBundlePacket: ClientboundBundlePacket, data: E): Entity<E>? =
        clientboundBundlePacket.singleSpawnEntityPacketOrNull()?.let { spawnEntityPacket -> decode(spawnEntityPacket, data) }

    private fun <E : Any> createAndRegister(
        spawnEntityPacket: SpawnEntityPacket,
        minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
    ): Entity<E> {
        val type = type(spawnEntityPacket)
        val entity =
            createEntity(spawnEntityPacket, type, minecraftEntityPacketAdapter.createData(spawnEntityPacket, type))
        minecraftEntityPacketAdapter.registerEntity(spawnEntityPacket, entity)
        return entity
    }

    private fun type(spawnEntityPacket: SpawnEntityPacket): Identifier = entityTypeProtocolRegistry[spawnEntityPacket.typeId]?.id
        ?: throw IllegalArgumentException("Entity type registry ID ${spawnEntityPacket.typeId} has no installed entry")

    private fun <E : Any> createEntity(
        spawnEntityPacket: SpawnEntityPacket,
        type: Identifier,
        data: E,
    ): Entity<E> = Entity(
        type = type.value,
        uuid = spawnEntityPacket.entityUuid,
        data = data,
        position = EntityVector3d(spawnEntityPacket.x, spawnEntityPacket.y, spawnEntityPacket.z),
        velocity = EntityVector3d(spawnEntityPacket.velocity.x, spawnEntityPacket.velocity.y, spawnEntityPacket.velocity.z),
        entityRotation = EntityRotation(
            yaw = spawnEntityPacket.yaw.degrees,
            pitch = spawnEntityPacket.pitch.degrees,
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
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
) {
    val iterator = iterator()
    require(iterator.nextOrNull() is SpawnEntityPacket) {
        "Entity pairing packets must begin with SpawnEntityPacket"
    }
    while (iterator.hasNext()) {
        val clientboundPacket = iterator.next()
        require(clientboundPacket !is SpawnEntityPacket) {
            "applyEntityPairingPackets accepts exactly one Entity pairing sequence"
        }
        applyEntityPairingPacket(entity, clientboundPacket, minecraftEntityPacketAdapter)
    }
}

private fun <E : Any> applyEntityPairingPacket(
    entity: Entity<E>,
    clientboundPacket: ClientboundPacket,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
) {
    when (clientboundPacket) {
        is SetEntityMetadataPacket -> minecraftEntityPacketAdapter.applyMetadata(entity, clientboundPacket)
        is UpdateAttributesPacket -> minecraftEntityPacketAdapter.applyAttributes(entity, clientboundPacket)
        is SetEquipmentPacket -> minecraftEntityPacketAdapter.applyEquipment(entity, clientboundPacket)
        is SetPassengersPacket -> minecraftEntityPacketAdapter.applyPassengers(entity, clientboundPacket)
        is LinkEntitiesPacket -> minecraftEntityPacketAdapter.applyLink(entity, clientboundPacket)
        else -> minecraftEntityPacketAdapter.applyOther(entity, clientboundPacket)
    }
}

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null

private fun ClientboundBundlePacket.singleSpawnEntityPacketOrNull(): SpawnEntityPacket? {
    if (!isEntityPairingBundle) return null
    val iterator = spawnEntityPackets().iterator()
    val spawnEntityPacket = iterator.next()
    return if (iterator.hasNext()) null else spawnEntityPacket
}

private fun requireSingleSpawnEntityPacket(clientboundBundlePacket: ClientboundBundlePacket): SpawnEntityPacket =
    requireNotNull(clientboundBundlePacket.singleSpawnEntityPacketOrNull()) {
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
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound> = minecraftEntityPacketDecoder.decode(this)

/** Fluent clientbound spawn packet to strong world-Entity conversion with caller-owned subtype data. */
fun <E : Any> SpawnEntityPacket.toEntity(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E> = minecraftEntityPacketDecoder.decode(this, data)

/** Fluent bundle containing exactly one Entity pairing sequence to a strong world Entity. */
fun ClientboundBundlePacket.toEntity(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound> = minecraftEntityPacketDecoder.decode(this)

/** Returns the Entity only when this bundle contains exactly one pairing sequence. */
fun ClientboundBundlePacket.toEntityOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): Entity<NbtCompound>? = minecraftEntityPacketDecoder.decodeOrNull(this)

/** Creates, registers, and adapts a bundle containing exactly one Entity pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntity(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
): Entity<E> = minecraftEntityPacketDecoder.decode(this, minecraftEntityPacketAdapter)

/** Returns the adapted Entity only when this bundle contains exactly one pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntityOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
): Entity<E>? = minecraftEntityPacketDecoder.decodeOrNull(this, minecraftEntityPacketAdapter)

/** Fluent bundle containing exactly one Entity pairing sequence with caller-owned subtype data. */
fun <E : Any> ClientboundBundlePacket.toEntity(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E> = minecraftEntityPacketDecoder.decode(this, data)

/** Returns the Entity only when this bundle contains exactly one pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntityOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    data: E,
): Entity<E>? = minecraftEntityPacketDecoder.decodeOrNull(this, data)

/** Decodes every Entity pairing sequence in this bundle. */
fun ClientboundBundlePacket.toEntities(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>> = minecraftEntityPacketDecoder.decodeEntities(this)

/** Returns null when this bundle does not begin with an Entity pairing sequence. */
fun ClientboundBundlePacket.toEntitiesOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>>? = minecraftEntityPacketDecoder.decodeEntitiesOrNull(this)

/** Creates, registers, and adapts every Entity pairing sequence in this bundle. */
fun <E : Any> ClientboundBundlePacket.toEntities(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>> = minecraftEntityPacketDecoder.decodeEntities(this, minecraftEntityPacketAdapter)

/** Returns null when this bundle does not begin with an Entity pairing sequence. */
fun <E : Any> ClientboundBundlePacket.toEntitiesOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>>? = minecraftEntityPacketDecoder.decodeEntitiesOrNull(this, minecraftEntityPacketAdapter)

/** Decodes every Entity pairing sequence in this bundle or raw packet list. */
fun Iterable<ClientboundPacket>.toEntities(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>> = minecraftEntityPacketDecoder.decodeEntities(this)

/** Returns null when these packets do not begin with an Entity pairing sequence. */
fun Iterable<ClientboundPacket>.toEntitiesOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
): List<Entity<NbtCompound>>? = minecraftEntityPacketDecoder.decodeEntitiesOrNull(this)

/** Creates, registers, and adapts every Entity pairing sequence in this bundle or raw packet list. */
fun <E : Any> Iterable<ClientboundPacket>.toEntities(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>> = minecraftEntityPacketDecoder.decodeEntities(this, minecraftEntityPacketAdapter)

/** Returns null when these packets do not begin with an Entity pairing sequence. */
fun <E : Any> Iterable<ClientboundPacket>.toEntitiesOrNull(
    minecraftEntityPacketDecoder: MinecraftEntityPacketDecoder,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<E>,
): List<Entity<E>>? = minecraftEntityPacketDecoder.decodeEntitiesOrNull(this, minecraftEntityPacketAdapter)
