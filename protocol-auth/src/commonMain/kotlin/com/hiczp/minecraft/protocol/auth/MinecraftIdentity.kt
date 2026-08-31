package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

@Serializable(with = MinecraftIdentitySerializer::class)
sealed interface MinecraftIdentity {
    val id: Uuid
    val name: String
}

@Serializable
data class MinecraftOfflineIdentity(
    override val name: String,
) : MinecraftIdentity {
    init {
        require(name.isNotEmpty()) { "Minecraft player name cannot be empty" }
    }

    @Transient
    override val id: Uuid = minecraftOfflineUuid(name)

    companion object {
        fun minecraftOfflineUuid(name: String): Uuid {
            require(name.isNotEmpty()) { "Minecraft player name cannot be empty" }
            val byteArray = "OfflinePlayer:$name"
                .encodeUtf8()
                .md5()
                .toByteArray()
            byteArray[6] = ((byteArray[6].toInt() and 0x0F) or 0x30).toByte()
            byteArray[8] = ((byteArray[8].toInt() and 0x3F) or 0x80).toByte()
            return Uuid.fromByteArray(byteArray)
        }
    }
}

@Serializable
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

internal object MinecraftIdentitySerializer :
    JsonContentPolymorphicSerializer<MinecraftIdentity>(MinecraftIdentity::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<MinecraftIdentity> =
        if ("accessToken" in element.jsonObject) {
            MinecraftOnlineIdentity.serializer()
        } else {
            MinecraftOfflineIdentity.serializer()
        }
}

fun MinecraftOfflineIdentity.toGameProfile(): GameProfile = GameProfile(
    id = id,
    name = name,
    properties = emptyList(),
)
