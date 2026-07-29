package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x81,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "transfer",
)
data class PlayTransferPacket(
    @MaxLength(32_767)
    val host: String,
    @VarInt
    val port: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x83,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_attributes",
)
data class UpdateAttributesPacket(
    @VarInt
    val entityId: Int,
    @MaxCollectionSize(128)
    val attributes: List<AttributeSnapshot>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x84,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_mob_effect",
)
data class EntityEffectPacket(
    @VarInt
    val entityId: Int,
    @VarInt
    val effectTypeId: Int,
    @VarInt
    val amplifier: Int,
    @VarInt
    val durationTicks: Int,
    val flags: MobEffectFlags,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x86,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_tags",
)
data class PlayUpdateTagsPacket(
    val tags: Map<Identifier, List<TagDefinition>>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x87,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "projectile_power",
)
data class ProjectilePowerPacket(
    @VarInt
    val entityId: Int,
    val power: Double,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x88,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "custom_report_details",
)
data class PlayCustomReportDetailsPacket(
    @MaxCollectionSize(32)
    val details: List<ReportDetail>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x89,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "server_links",
)
data class PlayServerLinksPacket(
    val links: List<ServerLink>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x8B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "clear_dialog",
)
data object ClearDialogPacket : PlayStatePacket, ClientboundPacket
