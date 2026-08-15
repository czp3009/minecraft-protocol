package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

sealed interface MinecraftIdentity {
    val id: Uuid
    val name: String
}

data class MinecraftOfflineIdentity(
    override val name: String,
) : MinecraftIdentity {
    init {
        require(name.isNotEmpty()) { "Minecraft player name cannot be empty" }
    }

    override val id: Uuid = minecraftOfflineUuid(name)

    companion object {
        fun minecraftOfflineUuid(name: String): Uuid {
            require(name.isNotEmpty()) { "Minecraft player name cannot be empty" }
            val bytes = "OfflinePlayer:$name"
                .encodeUtf8()
                .md5()
                .toByteArray()
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x30).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            return Uuid.fromByteArray(bytes)
        }
    }
}

data class MinecraftOnlineIdentity(
    override val id: Uuid,
    override val name: String,
    val accessToken: String,
) : MinecraftIdentity {
    init {
        require(name.isNotEmpty()) { "Minecraft profile name cannot be empty" }
        require(accessToken.isNotBlank()) { "Minecraft access token cannot be blank" }
    }
}

fun MinecraftOfflineIdentity.toGameProfile(): GameProfile = GameProfile(
    id = id,
    name = name,
    properties = emptyList(),
)
