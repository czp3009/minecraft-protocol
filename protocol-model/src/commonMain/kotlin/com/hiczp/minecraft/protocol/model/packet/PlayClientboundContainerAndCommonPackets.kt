package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x11,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "container_close",
)
data class ClientboundContainerClosePacket(
    @VarInt
    val containerId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x13,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "container_set_data",
)
data class ClientboundContainerSetDataPacket(
    @VarInt
    val containerId: Int,
    val id: Short,
    val value: Short,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x16,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "cooldown",
)
data class ClientboundCooldownPacket(
    val cooldownGroup: Identifier,
    @VarInt
    val duration: Int,
) : PlayStatePacket, ClientboundPacket


@Serializable
@PacketInfo(
    0x17,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "custom_chat_completions",
)
data class ClientboundCustomChatCompletionsPacket(
    val action: Action,
    @MaxLength(32_767)
    val entries: List<String>,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    enum class Action {
        ADD,
        REMOVE,
        SET,
    }
}
