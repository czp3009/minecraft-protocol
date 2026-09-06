package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.configuration.ClientRegistryView
import com.hiczp.minecraft.protocol.configuration.resolveClientRegistryView

/** Resolves captured Configuration data with this negotiation's registry context, without connection access or I/O. */
fun MinecraftClientNegotiationResult.resolveClientRegistryView(): ClientRegistryView =
    dataPackConfigurationSnapshot.resolveClientRegistryView(minecraftDimensionContext.packetCodecContext)
