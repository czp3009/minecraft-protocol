package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.serialization.Serializable

/** Replaces the client's active postprocessing effect identifiers. */
@Serializable
@PacketInfo(0x0A, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "post_effects")
@PacketInfo(0x53, ConnectionState.PLAY, PacketDirection.CLIENTBOUND, "post_effects")
data class ClientboundPostEffectsPacket(val postEffects: List<Identifier>) :
    ConfigurationStatePacket, PlayStatePacket, ClientboundPacket
