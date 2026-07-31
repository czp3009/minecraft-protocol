package com.hiczp.minecraft.protocol.model.packet

import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * Internal handoff from KSP-discovered packet declarations to the physical
 * registry owned by protocol-serialization.
 */
@RequiresOptIn(
    message = "Packet definitions are an internal cross-module runtime detail.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class InternalPacketRegistryApi

@InternalPacketRegistryApi
class PacketDefinition<T : Packet> internal constructor(
    val state: ConnectionState,
    val direction: PacketDirection,
    val id: Int,
    val framing: PacketFraming,
    val packetClass: KClass<T>,
    val serializer: KSerializer<T>,
)
