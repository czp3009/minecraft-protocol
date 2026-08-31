package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.TextComponent
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarIntElements
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

interface NeoForgeBidirectionalPacket :
    ClientboundPacket.Extension,
    ServerboundPacket.Extension

@Serializable
enum class NeoForgeConnectionProtocol(val id: String) {
    HANDSHAKING("handshake"),
    PLAY("play"),
    STATUS("status"),
    LOGIN("login"),
    CONFIGURATION("configuration"),
}

/** Ordinals match the repository-selected official PacketFlow enum. */
@Serializable
enum class NeoForgePacketFlow {
    SERVERBOUND,
    CLIENTBOUND,
}

@Serializable
data class NeoForgeNetworkComponent(
    val id: Identifier,
    val version: String,
    val neoForgePacketFlow: NeoForgePacketFlow? = null,
    val optional: Boolean = false,
) {
    init {
        require(version.isNotBlank()) {
            "NeoForge network component $id has a blank version"
        }
    }
}

@Serializable
data class NeoForgeCommonVersionPacket(
    @VarIntElements
    @MaxCollectionSize(NeoForgeProtocolLimits.MAX_COMMON_VERSIONS)
    val versions: List<Int>,
) : NeoForgeBidirectionalPacket

@Serializable
data class NeoForgeCommonRegisterPacket(
    @VarInt
    val version: Int,
    val protocol: String,
    @MaxCollectionSize(NeoForgeProtocolLimits.MAX_CHANNELS)
    val channels: Set<Identifier>,
) : NeoForgeBidirectionalPacket

sealed interface NeoForgeChannelRegistrationPacket : NeoForgeBidirectionalPacket {
    val channels: Set<Identifier>
}

data class NeoForgeRegisterChannelsPacket(
    override val channels: Set<Identifier>,
) : NeoForgeChannelRegistrationPacket

data class NeoForgeUnregisterChannelsPacket(
    override val channels: Set<Identifier>,
) : NeoForgeChannelRegistrationPacket

@Serializable
data class NeoForgeModdedNetworkQueryPacket(
    val queries: Map<NeoForgeConnectionProtocol, Set<NeoForgeNetworkComponent>>,
) : NeoForgeBidirectionalPacket

@Serializable
data class NeoForgeNetworkChannel(
    val id: Identifier,
    val chosenVersion: String,
)

@Serializable
data class NeoForgeNetworkSetup(
    val channels: Map<NeoForgeConnectionProtocol, Map<Identifier, NeoForgeNetworkChannel>>,
) {
    init {
        channels.forEach { (protocol, protocolChannels) ->
            require(
                protocol == NeoForgeConnectionProtocol.CONFIGURATION ||
                        protocol == NeoForgeConnectionProtocol.PLAY,
            ) {
                "NeoForge setup contains unsupported protocol $protocol"
            }
            require(protocolChannels.all { (id, channel) -> id == channel.id }) {
                "NeoForge setup map keys must match channel identifiers"
            }
        }
    }

    fun channels(neoForgeConnectionProtocol: NeoForgeConnectionProtocol): Map<Identifier, NeoForgeNetworkChannel> =
        channels[neoForgeConnectionProtocol].orEmpty()

    companion object {
        val Empty: NeoForgeNetworkSetup = NeoForgeNetworkSetup(emptyMap())
    }
}

@Serializable
data class NeoForgeModdedNetworkPacket(
    val neoForgeNetworkSetup: NeoForgeNetworkSetup,
) : NeoForgeBidirectionalPacket

@Serializable
data class NeoForgeModdedNetworkSetupFailedPacket(
    val failureReasons: Map<Identifier, TextComponent>,
) : ClientboundPacket.Extension

@Serializable(with = NeoForgeRegistrySnapshotSerializer::class)
class NeoForgeRegistrySnapshot(
    ids: Map<Int, Identifier>,
    aliases: Map<Identifier, Identifier> = emptyMap(),
) {
    val ids: Map<Int, Identifier> = ids.toMap()
    val aliases: Map<Identifier, Identifier> = aliases.toMap()

    init {
        require(this.ids.keys.all { it >= 0 }) {
            "NeoForge registry raw IDs must be non-negative"
        }
        require(this.ids.keys.zipWithNext().all { (left, right) -> left < right }) {
            "NeoForge registry raw IDs must be strictly increasing"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NeoForgeRegistrySnapshot &&
                ids == other.ids && aliases == other.aliases

    override fun hashCode(): Int = 31 * ids.hashCode() + aliases.hashCode()

    override fun toString(): String =
        "NeoForgeRegistrySnapshot(ids=$ids, aliases=$aliases)"
}

@Serializable
data class NeoForgeFrozenRegistrySyncStartPacket(
    val registryIds: List<Identifier>,
) : ClientboundPacket.Extension

@Serializable
data class NeoForgeFrozenRegistryPacket(
    val registryId: Identifier,
    val neoForgeRegistrySnapshot: NeoForgeRegistrySnapshot,
) : ClientboundPacket.Extension

@Serializable
data object NeoForgeFrozenRegistrySyncCompletedPacket : NeoForgeBidirectionalPacket

@Serializable
data class NeoForgeConfigFilePacket(
    val fileName: String,
    val contents: ByteString,
) : ClientboundPacket.Extension

@Serializable
data class NeoForgeKnownDataMap(
    val id: Identifier,
    val mandatory: Boolean,
)

@Serializable
data class NeoForgeKnownRegistryDataMapsPacket(
    val dataMaps: Map<Identifier, List<NeoForgeKnownDataMap>>,
) : ClientboundPacket.Extension

@Serializable
data class NeoForgeKnownRegistryDataMapsReplyPacket(
    val dataMaps: Map<Identifier, List<Identifier>>,
) : ServerboundPacket.Extension

@Serializable(with = NeoForgeNetworkCheckSerializer::class)
enum class NeoForgeNetworkCheck {
    CLIENTBOUND,
    SERVERBOUND,
    BIDIRECTIONAL,
}

@Serializable
data class NeoForgeEnumExtensionData(
    @VarInt
    val vanillaCount: Int,
    @VarInt
    val totalCount: Int,
    val entries: List<String>,
) {
    init {
        require(vanillaCount >= 0) { "NeoForge vanilla enum count must be non-negative" }
        require(totalCount >= vanillaCount) {
            "NeoForge total enum count must not be smaller than its vanilla count"
        }
        require(entries.size == totalCount - vanillaCount) {
            "NeoForge enum extension entry count does not match its declared counts"
        }
    }
}

@Serializable
data class NeoForgeEnumEntry(
    val className: String,
    val neoForgeNetworkCheck: NeoForgeNetworkCheck,
    val neoForgeEnumExtensionData: NeoForgeEnumExtensionData? = null,
)

@Serializable
data class NeoForgeExtensibleEnumDataPacket(
    val entries: List<NeoForgeEnumEntry>,
) : ClientboundPacket.Extension {
    init {
        require(entries.distinctBy(NeoForgeEnumEntry::className).size == entries.size) {
            "NeoForge extensible enum data contains duplicate class names"
        }
    }
}

@Serializable
data object NeoForgeExtensibleEnumAcknowledgePacket : ServerboundPacket.Extension

@Serializable
data class NeoForgeFeatureFlagDataPacket(
    val flags: Set<Identifier>,
) : ClientboundPacket.Extension

@Serializable
data object NeoForgeFeatureFlagAcknowledgePacket : ServerboundPacket.Extension

@Serializable
data class NeoForgeSplitPacket(
    val payload: ByteString,
) : NeoForgeBidirectionalPacket

@Serializable(with = NeoForgeRegistryDataMapSyncSerializer::class)
data class NeoForgeRegistryDataMapSyncPacket(
    val registry: Identifier,
    val dataMaps: Map<Identifier, Map<Identifier, JsonElement>>,
) : ClientboundPacket.Extension

object NeoForgeChannels {
    val Register: Identifier = Identifier("register")
    val Unregister: Identifier = Identifier("unregister")
    val NetworkQuery: Identifier = Identifier("neoforge:register")
    val Network: Identifier = Identifier("neoforge:network")
    val NetworkSetupFailed: Identifier = Identifier("neoforge:modded_network_setup_failed")
    val CommonVersion: Identifier = Identifier("c:version")
    val CommonRegister: Identifier = Identifier("c:register")
    val FrozenRegistrySyncStart: Identifier = Identifier("neoforge:frozen_registry_sync_start")
    val FrozenRegistry: Identifier = Identifier("neoforge:frozen_registry")
    val FrozenRegistrySyncCompleted: Identifier = Identifier("neoforge:frozen_registry_sync_completed")
    val ConfigFile: Identifier = Identifier("neoforge:config_file")
    val KnownRegistryDataMaps: Identifier = Identifier("neoforge:known_registry_data_maps")
    val KnownRegistryDataMapsReply: Identifier = Identifier("neoforge:known_registry_data_maps_reply")
    val ExtensibleEnumData: Identifier = Identifier("neoforge:extensible_enum_data")
    val ExtensibleEnumAcknowledge: Identifier = Identifier("neoforge:extensible_enum_ack")
    val FeatureFlagData: Identifier = Identifier("neoforge:feature_flags")
    val FeatureFlagAcknowledge: Identifier = Identifier("neoforge:feature_flags_ack")
    val Split: Identifier = Identifier("neoforge:split")
    val RegistryDataMapSync: Identifier = Identifier("neoforge:registry_data_map_sync")
}

object NeoForgeProtocolLimits {
    const val MAX_COMMON_VERSIONS: Int = 64
    const val MAX_CHANNELS: Int = 8_192
    const val MAX_CHANNEL_NAME_LENGTH: Int = 128
    const val SPLIT_PART_SIZE: Int = 2_097_126
}

internal object NeoForgeNetworkCheckSerializer :
    KSerializer<NeoForgeNetworkCheck> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "com.hiczp.minecraft.protocol.neoforge.NeoForgeNetworkCheck",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: NeoForgeNetworkCheck) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): NeoForgeNetworkCheck {
        val name = decoder.decodeString()
        return NeoForgeNetworkCheck.entries.firstOrNull { it.name == name }
            ?: throw SerializationException(
                "Unknown NeoForge network enum check $name",
            )
    }
}
