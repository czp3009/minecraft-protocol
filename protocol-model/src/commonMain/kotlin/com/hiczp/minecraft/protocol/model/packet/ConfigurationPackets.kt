package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(0x00, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "cookie_request")
data class ConfigurationCookieRequestPacket(
    val key: Identifier,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "custom_payload")
data class ConfigurationClientboundPluginMessagePacket(
    @Serializable(with = ClientboundCustomPayloadSerializer::class)
    val payload: CustomPayload,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x02, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "disconnect")
data class ConfigurationDisconnectPacket(
    val reason: TextComponent,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "finish_configuration")
data object FinishConfigurationPacket : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x04, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "keep_alive")
data class ConfigurationClientboundKeepAlivePacket(
    val id: Long,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x05, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "ping")
data class ConfigurationPingPacket(
    val id: Int,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x06, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "reset_chat")
data object ResetChatPacket : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x07, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "registry_data")
data class RegistryDataPacket(
    val registryId: Identifier,
    val entries: List<RegistryEntry>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x08, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "resource_pack_pop")
data class ConfigurationRemoveResourcePackPacket(
    val uuid: Uuid?,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x09, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "resource_pack_push")
data class ConfigurationAddResourcePackPacket(
    val uuid: Uuid,
    @MaxLength(32_767)
    val url: String,
    @MaxLength(40)
    val hash: String,
    val forced: Boolean,
    val promptMessage: TextComponent?,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0A, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "store_cookie")
data class ConfigurationStoreCookiePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0B, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "transfer")
data class ConfigurationTransferPacket(
    @MaxLength(32_767)
    val host: String,
    @VarInt
    val port: Int,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0C, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "update_enabled_features")
data class FeatureFlagsPacket(
    val featureFlags: Set<Identifier>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0D, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "update_tags")
data class ConfigurationUpdateTagsPacket(
    val registries: List<RegistryTags>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0E, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "select_known_packs")
data class ConfigurationClientboundKnownPacksPacket(
    val knownPacks: List<KnownPack>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x0F, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "custom_report_details")
data class ConfigurationCustomReportDetailsPacket(
    @MaxCollectionSize(32)
    val details: List<ReportDetail>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x10, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "server_links")
data class ConfigurationServerLinksPacket(
    val links: List<ServerLink>,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x11, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "clear_dialog")
data object ConfigurationClearDialogPacket :
    ConfigurationStatePacket,
    ClientboundPacket

@Serializable
@PacketInfo(0x12, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "show_dialog")
data class ConfigurationShowDialogPacket(
    val dialog: NbtTag,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x13, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "code_of_conduct")
data class CodeOfConductPacket(
    val codeOfConduct: String,
) : ConfigurationStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x00, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "client_information")
data class ConfigurationClientInformationPacket(
    val information: ClientInformation,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "cookie_response")
data class ConfigurationCookieResponsePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString?,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x02, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "custom_payload")
data class ConfigurationServerboundPluginMessagePacket(
    @Serializable(with = ServerboundCustomPayloadSerializer::class)
    val payload: CustomPayload,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "finish_configuration")
data object AcknowledgeFinishConfigurationPacket :
    ConfigurationStatePacket,
    ServerboundPacket

@Serializable
@PacketInfo(0x04, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "keep_alive")
data class ConfigurationServerboundKeepAlivePacket(
    val id: Long,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x05, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "pong")
data class ConfigurationPongPacket(
    val id: Int,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x06, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "resource_pack")
data class ConfigurationResourcePackResponsePacket(
    val uuid: Uuid,
    val result: ResourcePackResult,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x07, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "select_known_packs")
data class ConfigurationServerboundKnownPacksPacket(
    @MaxCollectionSize(64)
    val knownPacks: List<KnownPack>,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x08, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "custom_click_action")
data class ConfigurationCustomClickActionPacket(
    val id: Identifier,
    @ByteLengthPrefixed(65_536)
    @NbtEndOptional
    val payload: NbtTag?,
) : ConfigurationStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x09, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND, "accept_code_of_conduct")
data object AcceptCodeOfConductPacket :
    ConfigurationStatePacket,
    ServerboundPacket
