package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.type.ClientInformation
import com.hiczp.minecraft.protocol.model.type.GameProfile

interface MinecraftServerHandler {
    suspend fun statusJson(
        configuration: MinecraftServerConfiguration,
    ): String = configuration.statusJson()

    suspend fun acceptProfile(profile: GameProfile): Boolean = true

    suspend fun playLogin(
        profile: GameProfile,
        clientInformation: ClientInformation,
        configuration: MinecraftServerConfiguration,
    ): PlayLoginPacket =
        configuration.playLogin(profile)

    suspend fun onPacket(packet: Packet) = Unit
}

object DefaultMinecraftServerHandler : MinecraftServerHandler
