package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.KnownPack

interface MinecraftClientHandler {
    suspend fun loginCookie(
        request: LoginCookieRequestPacket,
    ): ByteString? = null

    suspend fun loginPlugin(
        request: LoginPluginRequestPacket,
    ): ByteString? = null

    suspend fun configurationCookie(
        request: ConfigurationCookieRequestPacket,
    ): ByteString? = null

    suspend fun selectKnownPacks(
        offered: List<KnownPack>,
    ): List<KnownPack> =
        offered.filter { it in VanillaProtocolData.knownPacks }

    suspend fun acceptCodeOfConduct(
        packet: CodeOfConductPacket,
    ): Boolean = true

    suspend fun onPacket(packet: Packet) = Unit
}

object DefaultMinecraftClientHandler : MinecraftClientHandler
