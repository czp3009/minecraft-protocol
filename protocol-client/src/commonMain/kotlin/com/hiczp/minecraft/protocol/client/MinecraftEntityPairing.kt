package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.packet.ClientboundAddEntityPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundBundlePacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.world.EntityPacketDecodeResult
import com.hiczp.minecraft.protocol.world.EntityPacketDecoder
import com.hiczp.minecraft.world.format.Entity

val ClientboundBundlePacket.isEntityPairingBundle: Boolean
    get() = subPackets.firstOrNull() is ClientboundAddEntityPacket

/**
 * Splits consecutive pairing sequences. Registration occurs before trailing mappings, and unresolved packets reach
 * the caller in received order. Every pending packet is also retained in the corresponding returned result.
 */
fun decodeEntityPairings(
    packets: Iterable<ClientboundPacket>,
    entityPacketDecoder: EntityPacketDecoder,
    registerEntity: (Int, Entity) -> Unit = { _, _ -> },
    pendingPacket: (EntityPacketDecodeResult) -> Unit = {},
): List<EntityPacketDecodeResult> {
    val results = mutableListOf<EntityPacketDecodeResult>()
    var current: EntityPacketDecodeResult? = null
    var pending = mutableListOf<ClientboundPacket>()
    packets.forEach { packet ->
        if (packet is ClientboundAddEntityPacket) {
            current?.let { results.add(it.copy(pendingPackets = pending)) }
            current = entityPacketDecoder.decode(packet)
            pending = mutableListOf()
            registerEntity(current.entityId, current.entity)
        } else {
            val target = requireNotNull(current) { "Entity pairing packets must begin with ClientboundAddEntityPacket" }
            val decoded = entityPacketDecoder.decode(target.entity, target.entityId, listOf(packet))
            if (decoded.pendingPackets.isNotEmpty()) {
                pending.addAll(decoded.pendingPackets)
                pendingPacket(decoded)
            }
        }
    }
    current?.let { results.add(it.copy(pendingPackets = pending)) }
        ?: error("Entity pairing packets must begin with ClientboundAddEntityPacket")
    return results
}

fun ClientboundBundlePacket.toEntities(
    entityPacketDecoder: EntityPacketDecoder,
    registerEntity: (Int, Entity) -> Unit = { _, _ -> },
    pendingPacket: (EntityPacketDecodeResult) -> Unit = {},
): List<EntityPacketDecodeResult> = decodeEntityPairings(subPackets, entityPacketDecoder, registerEntity, pendingPacket)
