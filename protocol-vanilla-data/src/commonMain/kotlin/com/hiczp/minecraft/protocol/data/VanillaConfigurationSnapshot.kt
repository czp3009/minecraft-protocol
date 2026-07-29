@file:OptIn(ExperimentalEncodingApi::class)

package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class VanillaConfigurationSnapshot(
    val knownPacks: List<com.hiczp.minecraft.protocol.model.type.KnownPack>,
    val featureFlags: FeatureFlagsPacket,
    val completeRegistries: List<RegistryDataPacket>,
    val clientKnownRegistries: List<RegistryDataPacket>,
    val tags: ConfigurationUpdateTagsPacket,
)

internal fun decodeVanillaConfigurationSnapshot(): VanillaConfigurationSnapshot {
    check(VanillaConfigurationPayloads.minecraftVersion == MinecraftProtocol.MINECRAFT_VERSION) {
        "Vanilla data targets ${VanillaConfigurationPayloads.minecraftVersion}, " +
                "but models target ${MinecraftProtocol.MINECRAFT_VERSION}"
    }
    check(VanillaConfigurationPayloads.protocolVersion == MinecraftProtocol.PROTOCOL_VERSION) {
        "Vanilla data targets protocol ${VanillaConfigurationPayloads.protocolVersion}, " +
                "but models target ${MinecraftProtocol.PROTOCOL_VERSION}"
    }

    val knownPacks = decodeConfigurationPacket(
        id = 0x0E,
        chunks = VanillaConfigurationPayloads.knownPacks,
    ) as ConfigurationClientboundKnownPacksPacket
    val featureFlags = decodeConfigurationPacket(
        id = 0x0C,
        chunks = VanillaConfigurationPayloads.featureFlags,
    ) as FeatureFlagsPacket
    val completeRegistries = VanillaConfigurationPayloads.completeRegistries.map {
        decodeConfigurationPacket(0x07, it) as RegistryDataPacket
    }
    val clientKnownRegistries =
        VanillaConfigurationPayloads.clientKnownRegistries.map {
            decodeConfigurationPacket(0x07, it) as RegistryDataPacket
        }
    val tags = decodeConfigurationPacket(
        id = 0x0D,
        chunks = VanillaConfigurationPayloads.tags,
    ) as ConfigurationUpdateTagsPacket

    check(completeRegistries.isNotEmpty()) {
        "The official vanilla registry snapshot is empty"
    }
    check(completeRegistries.size == clientKnownRegistries.size) {
        "The full and Known Packs registry snapshots have different sizes"
    }
    completeRegistries.zip(clientKnownRegistries).forEach { (full, compact) ->
        check(full.registryId == compact.registryId) {
            "The full and Known Packs snapshots have different registry order"
        }
        check(full.entries.map { it.id } == compact.entries.map { it.id }) {
            "The full and Known Packs snapshots differ for ${full.registryId}"
        }
    }

    return VanillaConfigurationSnapshot(
        knownPacks = knownPacks.knownPacks,
        featureFlags = featureFlags,
        completeRegistries = completeRegistries,
        clientKnownRegistries = clientKnownRegistries,
        tags = tags,
    )
}

private fun decodeConfigurationPacket(
    id: Int,
    chunks: List<String>,
): Packet = MinecraftPacketRegistry.decodePayload(
    state = ConnectionState.CONFIGURATION,
    direction = PacketDirection.CLIENTBOUND,
    id = id,
    payload = Base64.Default.decode(chunks.joinToString(separator = "")),
)
