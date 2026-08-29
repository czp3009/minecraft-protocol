package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.datapack.ClientRegistryView
import com.hiczp.minecraft.protocol.datapack.resolveClientRegistryView

/** Converts this result with the authoritative registry context retained by its open connection. */
fun MinecraftClientNegotiationResult.resolveClientRegistryView(
    minecraftClientConnection: MinecraftClientConnection,
): ClientRegistryView =
    dataPackConfigurationSnapshot.resolveClientRegistryView(minecraftClientConnection.protocolRegistryContext)
