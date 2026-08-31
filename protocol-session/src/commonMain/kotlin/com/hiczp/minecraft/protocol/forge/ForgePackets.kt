package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarIntElements
import kotlinx.serialization.Serializable

sealed interface ForgeChannelRegistrationPacket : ClientboundPacket.Extension, ServerboundPacket.Extension {
    val channels: Set<Identifier>
}

data class ForgeRegisterChannelsPacket(
    override val channels: Set<Identifier>,
) : ForgeChannelRegistrationPacket

data class ForgeUnregisterChannelsPacket(
    override val channels: Set<Identifier>,
) : ForgeChannelRegistrationPacket

sealed interface ForgeHandshakeMessage

sealed interface ForgeClientboundHandshakeMessage : ForgeHandshakeMessage

sealed interface ForgeServerboundHandshakeMessage : ForgeHandshakeMessage

@Serializable
data class ForgeAcknowledgeMessage(
    @VarInt
    val token: Int,
) : ForgeServerboundHandshakeMessage {
    init {
        require(token >= 0) { "Forge acknowledgement token must be non-negative" }
    }
}

@Serializable
data class ForgeModInfo(
    @MaxLength(ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH)
    val name: String,
    @MaxLength(ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH)
    val version: String,
)

@Serializable
data class ForgeModVersionsMessage(
    @MaxCollectionSize(ForgeProtocolLimits.MAX_MODS)
    val mods: Map<String, ForgeModInfo>,
) : ForgeClientboundHandshakeMessage, ForgeServerboundHandshakeMessage {
    init {
        require(mods.keys.all { it.length <= ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH }) {
            "Forge mod identifiers must not exceed ${ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH} characters"
        }
    }
}

@Serializable
data class ForgeChannelVersionsMessage(
    @VarIntElements
    @MaxCollectionSize(ForgeProtocolLimits.MAX_CHANNELS)
    val channels: Map<Identifier, Int>,
) : ForgeClientboundHandshakeMessage, ForgeServerboundHandshakeMessage {
    init {
        require(channels.values.all { it >= 0 }) {
            "Forge channel versions must be non-negative"
        }
    }
}

@Serializable
data class ForgeRegistryListMessage(
    @VarInt
    val token: Int,
    @MaxCollectionSize(ForgeProtocolLimits.MAX_REGISTRIES)
    val registryIds: List<Identifier>,
    @MaxCollectionSize(ForgeProtocolLimits.MAX_REGISTRIES)
    val dataPackRegistryIds: List<Identifier>,
) : ForgeClientboundHandshakeMessage {
    init {
        require(token >= 0) { "Forge registry-list token must be non-negative" }
        require(registryIds.distinct().size == registryIds.size) {
            "Forge registry list contains duplicate ordinary registries"
        }
        require(dataPackRegistryIds.distinct().size == dataPackRegistryIds.size) {
            "Forge registry list contains duplicate data-pack registries"
        }
    }
}

@Serializable(with = ForgeRegistrySnapshotSerializer::class)
class ForgeRegistrySnapshot(
    ids: Map<Identifier, Int>,
    aliases: Map<Identifier, Identifier> = emptyMap(),
    overrides: Map<Identifier, String> = emptyMap(),
    blocked: Set<Int> = emptySet(),
) {
    val ids: Map<Identifier, Int> = ids.toMap()
    val aliases: Map<Identifier, Identifier> = aliases.toMap()
    val overrides: Map<Identifier, String> = overrides.toMap()
    val blocked: Set<Int> = blocked.toSet()

    init {
        require(this.ids.values.all { it >= 0 }) {
            "Forge registry raw IDs must be non-negative"
        }
        require(this.ids.values.distinct().size == this.ids.size) {
            "Forge registry raw IDs must be unique"
        }
        require(this.blocked.all { it >= 0 }) {
            "Forge blocked registry IDs must be non-negative"
        }
        require(this.overrides.values.all {
            it.length <= ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH
        }) {
            "Forge registry override owners are too long"
        }
    }

    val wireSize: Int
        get() = (
                ids.values.asSequence() + blocked.asSequence()
                ).maxOrNull()?.plus(1) ?: 0

    override fun equals(other: Any?): Boolean =
        other is ForgeRegistrySnapshot &&
                ids == other.ids &&
                aliases == other.aliases &&
                overrides == other.overrides &&
                blocked == other.blocked

    override fun hashCode(): Int {
        var result = ids.hashCode()
        result = 31 * result + aliases.hashCode()
        result = 31 * result + overrides.hashCode()
        return 31 * result + blocked.hashCode()
    }

    override fun toString(): String =
        "ForgeRegistrySnapshot(ids=$ids, aliases=$aliases, overrides=$overrides, blocked=$blocked)"
}

@Serializable
data class ForgeRegistryDataMessage(
    @VarInt
    val token: Int,
    val registryId: Identifier,
    val forgeRegistrySnapshot: ForgeRegistrySnapshot,
) : ForgeClientboundHandshakeMessage {
    init {
        require(token >= 0) { "Forge registry-data token must be non-negative" }
    }
}

@Serializable
data class ForgeConfigDataMessage(
    val fileName: String,
    val contents: ByteString,
) : ForgeClientboundHandshakeMessage

@Serializable
data class ForgeVersionMismatch(
    @MaxLength(ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH)
    val received: String,
    @MaxLength(ForgeProtocolLimits.MAX_METADATA_STRING_LENGTH)
    val local: String,
)

@Serializable
data class ForgeMismatchDataMessage(
    @MaxCollectionSize(ForgeProtocolLimits.MAX_CHANNELS)
    val mismatched: Map<Identifier, ForgeVersionMismatch>,
    @MaxCollectionSize(ForgeProtocolLimits.MAX_CHANNELS)
    val missing: Set<Identifier>,
) : ForgeClientboundHandshakeMessage

data class ForgeClientboundHandshakePacket(
    val forgeClientboundHandshakeMessage: ForgeClientboundHandshakeMessage,
) : ClientboundPacket.Extension

data class ForgeServerboundHandshakePacket(
    val forgeServerboundHandshakeMessage: ForgeServerboundHandshakeMessage,
) : ServerboundPacket.Extension

/** Raw selected-revision Forge messages 7+ carried by forge:handshake in Play. */
data class ForgeClientboundPlayHandshakePacket(
    val discriminator: Int,
    val data: ByteString,
) : ClientboundPacket.Extension {
    init {
        require(discriminator >= 0) {
            "Forge Play discriminator must be non-negative"
        }
    }
}

object ForgeChannels {
    val Register: Identifier = Identifier("register")
    val Unregister: Identifier = Identifier("unregister")
    val Login: Identifier = Identifier("forge:login")
    val Handshake: Identifier = Identifier("forge:handshake")
}

object ForgeProtocolLimits {
    const val MAX_METADATA_STRING_LENGTH: Int = 0x100
    const val MAX_MODS: Int = 8_192
    const val MAX_CHANNELS: Int = 8_192
    const val MAX_REGISTRIES: Int = 8_192
}
