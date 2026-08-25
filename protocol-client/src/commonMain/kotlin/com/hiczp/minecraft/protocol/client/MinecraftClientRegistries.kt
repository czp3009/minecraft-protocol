package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.datapack.ClientRegistryView
import com.hiczp.minecraft.protocol.datapack.resolveClientRegistryView

/** Converts this result with the authoritative registry context retained by its open connection. */
fun MinecraftClientNegotiationResult.resolveClientRegistryView(
    connection: MinecraftClientConnection,
): ClientRegistryView = dataPackConfigurationSnapshot.resolveClientRegistryView(connection.protocolRegistryContext)
