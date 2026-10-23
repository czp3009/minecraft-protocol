package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.InteractionHand
import com.hiczp.minecraft.protocol.model.type.SwingAnimation
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.Serializable

/** Plays the supplied swing animation for one entity's hand. */
@Serializable
@PacketInfo(0x7B, ConnectionState.PLAY, PacketDirection.CLIENTBOUND, "swing_animation")
data class ClientboundSwingAnimationPacket(
    @VarInt val entityId: Int,
    @ZeroFallbackEnum val hand: InteractionHand,
    val animation: SwingAnimation,
) : PlayStatePacket, ClientboundPacket
