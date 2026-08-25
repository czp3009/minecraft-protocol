package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import com.hiczp.minecraft.protocol.model.type.RegistryTags
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import kotlin.io.encoding.Base64

internal data class VanillaConfigurationSnapshot(
    val offeredKnownPacks: List<KnownPack>,
    val enabledFeatureFlags: Set<Identifier>,
    val completeSynchronizedRegistryPackets: List<RegistryDataPacket>,
    val knownPackSynchronizedRegistryPackets: List<RegistryDataPacket>,
    val registryTags: List<RegistryTags>,
)

internal fun decodeVanillaConfigurationSnapshot(): VanillaConfigurationSnapshot {
    val configurationClientboundKnownPacksPacket = decodeConfigurationPacket(
        packetId = 0x0E,
        packetPayloadChunks = VanillaConfigurationPacketPayloads.offeredKnownPacksPayloadChunks,
    ) as ConfigurationClientboundKnownPacksPacket
    val featureFlagsPacket = decodeConfigurationPacket(
        packetId = 0x0C,
        packetPayloadChunks = VanillaConfigurationPacketPayloads.enabledFeatureFlagsPayloadChunks,
    ) as FeatureFlagsPacket
    val completeSynchronizedRegistryPackets =
        VanillaConfigurationPacketPayloads.completeSynchronizedRegistryPacketPayloadChunks.map { packetPayloadChunks ->
            decodeConfigurationPacket(0x07, packetPayloadChunks) as RegistryDataPacket
        }
    val knownPackSynchronizedRegistryPackets =
        VanillaConfigurationPacketPayloads.knownPackSynchronizedRegistryPacketPayloadChunks.map { packetPayloadChunks ->
            decodeConfigurationPacket(0x07, packetPayloadChunks) as RegistryDataPacket
        }
    val configurationUpdateTagsPacket = decodeConfigurationPacket(
        packetId = 0x0D,
        packetPayloadChunks = VanillaConfigurationPacketPayloads.registryTagsPayloadChunks,
    ) as ConfigurationUpdateTagsPacket

    check(completeSynchronizedRegistryPackets.isNotEmpty()) {
        "The official vanilla registry snapshot is empty"
    }
    check(completeSynchronizedRegistryPackets.size == knownPackSynchronizedRegistryPackets.size) {
        "The full and Known Packs registry snapshots have different sizes"
    }
    completeSynchronizedRegistryPackets.zip(knownPackSynchronizedRegistryPackets)
        .forEach { (completeRegistry, knownPackRegistry) ->
            check(completeRegistry.registryId == knownPackRegistry.registryId) {
                "The full and Known Packs snapshots have different registry order"
            }
            check(completeRegistry.entries.map(RegistryEntry::id) == knownPackRegistry.entries.map(RegistryEntry::id)) {
                "The full and Known Packs snapshots differ for ${completeRegistry.registryId}"
            }
        }

    return VanillaConfigurationSnapshot(
        offeredKnownPacks = configurationClientboundKnownPacksPacket.knownPacks,
        enabledFeatureFlags = featureFlagsPacket.featureFlags,
        completeSynchronizedRegistryPackets = completeSynchronizedRegistryPackets,
        knownPackSynchronizedRegistryPackets = knownPackSynchronizedRegistryPackets,
        registryTags = configurationUpdateTagsPacket.tags,
    )
}

private fun decodeConfigurationPacket(
    packetId: Int,
    packetPayloadChunks: List<String>,
): Packet = MinecraftPacketRegistry.decodePayload(
    state = ConnectionState.CONFIGURATION,
    direction = PacketDirection.CLIENTBOUND,
    id = packetId,
    payload = Base64.decode(packetPayloadChunks.joinToString(separator = "")),
)
