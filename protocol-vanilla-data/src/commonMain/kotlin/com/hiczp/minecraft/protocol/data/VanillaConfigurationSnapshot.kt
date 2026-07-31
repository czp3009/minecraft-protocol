package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import kotlin.io.encoding.Base64

internal data class VanillaConfigurationSnapshot(
    val knownPacks: List<KnownPack>,
    val featureFlags: FeatureFlagsPacket,
    val completeRegistries: List<RegistryDataPacket>,
    val clientKnownRegistries: List<RegistryDataPacket>,
    val tags: ConfigurationUpdateTagsPacket,
)

internal fun decodeVanillaConfigurationSnapshot(): VanillaConfigurationSnapshot {
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
    payload = Base64.decode(chunks.joinToString(separator = "")),
)
