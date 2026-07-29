package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ClientboundCustomPayloadSerializer
import com.hiczp.minecraft.protocol.model.type.CustomPayload
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
data class ClientboundCloseContainerPacket(
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
data class SetContainerPropertyPacket(
    @VarInt
    val containerId: Int,
    val property: Short,
    val value: Short,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x15,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "cookie_request",
)
data class PlayCookieRequestPacket(
    val key: Identifier,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x16,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "cooldown",
)
data class SetCooldownPacket(
    val cooldownGroup: Identifier,
    @VarInt
    val cooldownTicks: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
enum class ChatSuggestionsAction {
    ADD,
    REMOVE,
    SET,
}

@Serializable
@PacketInfo(
    0x17,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "custom_chat_completions",
)
data class ChatSuggestionsPacket(
    val action: ChatSuggestionsAction,
    @MaxLength(32_767)
    val entries: List<String>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x18,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "custom_payload",
)
data class PlayClientboundPluginMessagePacket(
    @Serializable(with = ClientboundCustomPayloadSerializer::class)
    val payload: CustomPayload,
) : PlayStatePacket, ClientboundPacket
