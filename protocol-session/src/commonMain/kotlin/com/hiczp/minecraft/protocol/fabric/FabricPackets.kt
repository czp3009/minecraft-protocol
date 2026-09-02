package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RemoteRegistrySnapshot
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

/** Payloads which Fabric API registers in both directions. */
interface FabricBidirectionalPacket :
    ClientboundPacket.Extension,
    ServerboundPacket.Extension

@Serializable
data class FabricCommonVersionPacket(
    @VarIntElements
    @MaxCollectionSize(FabricProtocolLimits.MAX_COMMON_VERSIONS)
    val versions: List<Int>,
) : FabricBidirectionalPacket

@Serializable
data class FabricCommonRegisterPacket(
    @VarInt
    val version: Int,
    @MaxLength(FabricProtocolLimits.MAX_PROTOCOL_NAME_LENGTH)
    val protocol: String,
    @MaxCollectionSize(FabricProtocolLimits.MAX_CHANNELS)
    val channels: Set<Identifier>,
) : FabricBidirectionalPacket

sealed interface FabricChannelRegistrationPacket : FabricBidirectionalPacket {
    val channels: List<Identifier>
}

data class FabricRegisterChannelsPacket(
    override val channels: List<Identifier>,
) : FabricChannelRegistrationPacket

data class FabricUnregisterChannelsPacket(
    override val channels: List<Identifier>,
) : FabricChannelRegistrationPacket

@Serializable
data class FabricSplitPacket(
    @RemainingBytes
    val data: ByteString,
) : FabricBidirectionalPacket

data class FabricRegistrySyncPacket(
    val remoteRegistrySnapshot: RemoteRegistrySnapshot,
    val optionalRegistryIds: Set<Identifier> = emptySet(),
) : ClientboundPacket.Extension

@Serializable
data object FabricRegistrySyncCompletePacket : ServerboundPacket.Extension

object FabricChannels {
    val CommonVersion: Identifier = Identifier("c:version")
    val CommonRegister: Identifier = Identifier("c:register")
    val Register: Identifier = Identifier("register")
    val Unregister: Identifier = Identifier("unregister")
    val Split: Identifier = Identifier("fabric:split")
    val RegistrySync: Identifier = Identifier("fabric:registry/sync")
    val RegistrySyncComplete: Identifier = Identifier("fabric:registry/sync/complete")
}

object FabricProtocolLimits {
    const val MAX_COMMON_VERSIONS: Int = 64
    const val MAX_PROTOCOL_NAME_LENGTH: Int = 32
    const val MAX_CHANNELS: Int = 8_192
    const val MAX_CHANNEL_NAME_LENGTH: Int = 128
}
