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
data class PacketDefinition<T : Packet>(
    val connectionState: ConnectionState,
    val packetDirection: PacketDirection,
    val id: Int,
    val packetFraming: PacketFraming,
    val packetClass: KClass<T>,
    val kSerializer: KSerializer<T>,
)
