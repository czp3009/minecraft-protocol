package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.AttributeModifier
import com.hiczp.minecraft.protocol.model.type.MobEffectFlags
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x83,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_attributes",
)
data class ClientboundUpdateAttributesPacket(
    @VarInt
    val entityId: Int,
    @MaxCollectionSize(128)
    val attributes: List<AttributeSnapshot>,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    data class AttributeSnapshot(
        @VarInt
        val attribute: Int,
        val base: Double,
        val modifiers: List<AttributeModifier>,
    )
}

@Serializable
@PacketInfo(
    0x84,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_mob_effect",
)
data class ClientboundUpdateMobEffectPacket(
    @VarInt
    val entityId: Int,
    @VarInt
    val effect: Int,
    @VarInt
    val effectAmplifier: Int,
    @VarInt
    val effectDurationTicks: Int,
    val flags: MobEffectFlags,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x87,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "projectile_power",
)
data class ClientboundProjectilePowerPacket(
    @VarInt
    val id: Int,
    val accelerationPower: Double,
) : PlayStatePacket, ClientboundPacket
