package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.datapack.ClientDataPackRuntime
import com.hiczp.minecraft.protocol.datapack.ProtocolDataSet
import com.hiczp.minecraft.protocol.datapack.ReceivedDataPackConfiguration
import com.hiczp.minecraft.protocol.datapack.resolveRuntime
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaDataPacks
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.RemoteRegistrySnapshot
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema

/** Converts the retained Configuration packets without pretending that omitted server-only resources were received. */
fun MinecraftClientConfiguration.toReceivedDataPackConfiguration(): ReceivedDataPackConfiguration =
    ReceivedDataPackConfiguration(knownPacks, featureFlags, registries, tags)

/** Resolves synchronized IDs, loader mappings, block schemas, and tags into a client runtime view. */
fun MinecraftClientConfiguration.toDataPackRuntime(
    protocolData: ProtocolDataSet = VanillaDataPacks.protocolData,
    staticRegistries: StaticRegistrySchema = protocolData.staticRegistries,
    remoteRegistries: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
): ClientDataPackRuntime = toReceivedDataPackConfiguration().resolveRuntime(
    protocolData = protocolData,
    staticRegistries = staticRegistries,
    remoteRegistries = remoteRegistries,
)

/** Uses the exact context already resolved and installed by negotiation, including loader profile changes. */
fun MinecraftClientConfiguration.toDataPackRuntime(
    registryContext: ProtocolRegistryContext,
): ClientDataPackRuntime = toReceivedDataPackConfiguration().resolveRuntime(registryContext)

/** Converts this result with the authoritative registry context retained by its open connection. */
fun MinecraftClientNegotiationResult.toDataPackRuntime(
    connection: MinecraftClientConnection,
): ClientDataPackRuntime = configuration.toDataPackRuntime(connection.registries)
