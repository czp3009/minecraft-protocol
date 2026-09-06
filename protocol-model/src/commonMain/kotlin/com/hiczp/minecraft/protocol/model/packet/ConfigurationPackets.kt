package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(0x05, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "cookie_request")
@PacketInfo(
    0x15,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "cookie_request",
)
@PacketInfo(0x00, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "cookie_request")
data class ClientboundCookieRequestPacket(
    val key: Identifier,
) : ConfigurationStatePacket, LoginStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x18,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "custom_payload",
)
@PacketInfo(0x01, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "custom_payload")
data class ClientboundCustomPayloadPacket(
    @Serializable(with = ClientboundCustomPayloadSerializer::class)
    val payload: CustomPayload,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x20,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "disconnect",
)
@PacketInfo(0x02, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "disconnect")
data class ClientboundDisconnectPacket(
    val reason: TextComponent,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "finish_configuration")
data object ClientboundFinishConfigurationPacket : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x2C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "keep_alive",
)
@PacketInfo(0x04, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "keep_alive")
data class ClientboundKeepAlivePacket(
    val id: Long,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "ping",
)
@PacketInfo(0x05, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "ping")
data class ClientboundPingPacket(
    val id: Int,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x06, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "reset_chat")
data object ClientboundResetChatPacket : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x07, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "registry_data")
data class ClientboundRegistryDataPacket(
    val registry: Identifier,
    val entries: List<RegistryEntry>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x78,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "store_cookie",
)
@PacketInfo(0x0A, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "store_cookie")
data class ClientboundStoreCookiePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x81,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "transfer",
)
@PacketInfo(0x0B, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "transfer")
data class ClientboundTransferPacket(
    @MaxLength(32_767)
    val host: String,
    @VarInt
    val port: Int,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0C, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "update_enabled_features")
data class ClientboundUpdateEnabledFeaturesPacket(
    val features: Set<Identifier>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x86,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "update_tags",
)
@PacketInfo(0x0D, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "update_tags")
data class ClientboundUpdateTagsPacket(
    val tags: List<RegistryTags>,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0E, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "select_known_packs",
)
data class ClientboundSelectKnownPacks(
    val knownPacks: List<KnownPack>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x88,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "custom_report_details",
)
@PacketInfo(0x0F, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "custom_report_details")
data class ClientboundCustomReportDetailsPacket(
    @MaxCollectionSize(32)
    val details: List<ReportDetail>,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x89,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "server_links",
)
@PacketInfo(0x10, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "server_links")
data class ClientboundServerLinksPacket(
    val links: List<ServerLink>,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x8B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "clear_dialog",
)
@PacketInfo(0x11, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "clear_dialog")
data object ClientboundClearDialogPacket :
    ConfigurationStatePacket, PlayStatePacket,
    ClientboundPacket

@Serializable
@PacketInfo(
    0x8C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "show_dialog",
)
@PacketInfo(
    0x12,
    ConnectionState.CONFIGURATION,
    PacketDirection.CLIENTBOUND,
    "show_dialog",
    serializer = ConfigurationShowDialogSerializer::class
)
data class ClientboundShowDialogPacket(
    val dialog: DialogHolder,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x13, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "code_of_conduct")
data class ClientboundCodeOfConductPacket(
    val codeOfConduct: String,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0E,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "client_information",
)
@PacketInfo(0x00, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "client_information")
data class ServerboundClientInformationPacket(
    val information: ClientInformation,
) : ConfigurationStatePacket, PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x04, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "cookie_response")
@PacketInfo(
    0x15,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "cookie_response",
)
@PacketInfo(0x01, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "cookie_response")
data class ServerboundCookieResponsePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString?,
) : ConfigurationStatePacket, LoginStatePacket, PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x16,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "custom_payload",
)
@PacketInfo(0x02, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "custom_payload")
data class ServerboundCustomPayloadPacket(
    @Serializable(with = ServerboundCustomPayloadSerializer::class)
    val payload: CustomPayload,
) : ConfigurationStatePacket, PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "finish_configuration")
data object ServerboundFinishConfigurationPacket :
    ConfigurationStatePacket,
    ServerboundPacket

@Serializable
@PacketInfo(
    0x1C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "keep_alive",
)
@PacketInfo(0x04, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "keep_alive")
data class ServerboundKeepAlivePacket(
    val id: Long,
) : ConfigurationStatePacket, PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "pong",
)
@PacketInfo(0x05, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "pong")
data class ServerboundPongPacket(
    val id: Int,
) : ConfigurationStatePacket, PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x07, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "select_known_packs",
)
data class ServerboundSelectKnownPacks(
    @MaxCollectionSize(64)
    val knownPacks: List<KnownPack>,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x44,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "custom_click_action",
)
@PacketInfo(0x08, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "custom_click_action")
data class ServerboundCustomClickActionPacket(
    val id: Identifier,
    @ByteLengthPrefixed(65_536)
    @NbtEndOptional
    val payload: NbtTag?,
) : ConfigurationStatePacket, PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x09, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "accept_code_of_conduct")
data object ServerboundAcceptCodeOfConductPacket :
    ConfigurationStatePacket,
    ServerboundPacket
