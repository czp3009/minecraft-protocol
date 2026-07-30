package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.type.ClientInformation
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.JsonTextComponent
import com.hiczp.minecraft.protocol.model.type.KnownPack

interface MinecraftServerHandler {
    suspend fun statusJson(
        configuration: MinecraftServerConfiguration,
    ): String = configuration.statusJson()

    suspend fun acceptProfile(profile: GameProfile): Boolean = true

    /**
     * Transfer-aware admission hook. The legacy overload remains the default so
     * existing handlers keep their behavior.
     */
    suspend fun acceptProfile(
        profile: GameProfile,
        transferred: Boolean,
    ): Boolean = acceptProfile(profile)

    /**
     * Returns a Login-state rejection component, or null to admit the profile.
     * The default preserves [acceptProfile] for source-compatible handlers.
     */
    suspend fun profileRejection(
        profile: GameProfile,
        transferred: Boolean,
        configuration: MinecraftServerConfiguration,
    ): JsonTextComponent? =
        if (acceptProfile(profile, transferred)) {
            null
        } else {
            JsonTextComponent("""{"text":"Login rejected by server policy"}""")
        }

    suspend fun playLogin(
        profile: GameProfile,
        clientInformation: ClientInformation,
        configuration: MinecraftServerConfiguration,
    ): PlayLoginPacket =
        configuration.playLogin(profile)

    /**
     * Transfer-aware Play Login hook. Applications can use [transferred] when
     * restoring state carried by another server.
     */
    suspend fun playLogin(
        profile: GameProfile,
        clientInformation: ClientInformation,
        transferred: Boolean,
        configuration: MinecraftServerConfiguration,
    ): PlayLoginPacket =
        playLogin(profile, clientInformation, configuration)

    /**
     * Optional clientbound Configuration packets sent after vanilla registries
     * and tags and before Finish Configuration. This is where a complete server
     * can expose fire-and-forget server links, cookies, or custom payloads.
     * Protocol-managed registry/finish packets are rejected.
     */
    suspend fun configurationPackets(
        profile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        configuration: MinecraftServerConfiguration,
    ): List<Packet> = emptyList()

    /**
     * Ordered Configuration exchanges that must complete before Finish
     * Configuration. Use these for a code of conduct, a required resource pack,
     * or another client response that gates Play entry.
     */
    suspend fun configurationTasks(
        profile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        configuration: MinecraftServerConfiguration,
    ): List<MinecraftServerConfigurationTask> = emptyList()

    suspend fun onPacket(packet: Packet) = Unit
}

class MinecraftServerConfigurationTask(
    val name: String,
    packets: List<Packet>,
    private val completion: suspend (Packet) -> Boolean,
) {
    val packets: List<Packet> = packets.toList()

    init {
        require(name.isNotBlank()) {
            "Configuration task name must not be blank"
        }
    }

    suspend fun isComplete(packet: Packet): Boolean = completion(packet)
}

object DefaultMinecraftServerHandler : MinecraftServerHandler
