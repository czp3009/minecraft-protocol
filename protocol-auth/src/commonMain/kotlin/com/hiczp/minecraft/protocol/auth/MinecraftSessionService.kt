package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.ProfileProperty
import com.hiczp.minecraft.protocol.model.type.Uuid
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class JoinedMinecraftProfile(
    val profile: GameProfile,
    val profileActions: Set<String>,
)

class MinecraftSessionService(
    val httpClient: HttpClient,
    val baseUrl: String = PRODUCTION_BASE_URL,
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    suspend fun join(
        accessToken: String,
        selectedProfile: Uuid,
        serverHash: String,
    ) {
        val response = httpClient.post("$baseUrl/session/minecraft/join") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    JoinRequest(
                        accessToken = accessToken,
                        selectedProfile = selectedProfile.toUndashedString(),
                        serverId = serverHash,
                    ),
                ),
            )
        }
        if (
            response.status != HttpStatusCode.NoContent &&
            response.status != HttpStatusCode.OK
        ) {
            throw response.toAuthenticationException("join")
        }
    }

    suspend fun hasJoined(
        username: String,
        serverHash: String,
        ipAddress: String? = null,
    ): JoinedMinecraftProfile? {
        val response = httpClient.get("$baseUrl/session/minecraft/hasJoined") {
            parameter("username", username)
            parameter("serverId", serverHash)
            if (ipAddress != null) parameter("ip", ipAddress)
        }
        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = json.decodeFromString<HasJoinedResponse>(
                    response.bodyAsText(),
                )
                JoinedMinecraftProfile(
                    profile = GameProfile(
                        id = parseMinecraftUuid(body.id),
                        name = body.name ?: username,
                        properties = body.properties.map { property ->
                            ProfileProperty(
                                name = property.name,
                                value = property.value,
                                signature = property.signature,
                            )
                        },
                    ),
                    profileActions = body.profileActions
                        .orEmpty()
                        .mapTo(linkedSetOf(), ProfileActionResponse::type),
                )
            }

            HttpStatusCode.NoContent,
            HttpStatusCode.NotFound,
            HttpStatusCode.Forbidden,
                -> null

            else -> throw response.toAuthenticationException("hasJoined")
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.toAuthenticationException(operation: String): MinecraftAuthenticationException {
        val message =
            "Minecraft session $operation failed with HTTP ${status.value}: " +
                    bodyAsText().take(1_024)
        return if (status.value >= 500) {
            MinecraftAuthenticationUnavailableException(message)
        } else {
            MinecraftAuthenticationException(message)
        }
    }

    companion object {
        const val PRODUCTION_BASE_URL: String =
            "https://sessionserver.mojang.com"
    }
}

@Serializable
private data class JoinRequest(
    val accessToken: String,
    val selectedProfile: String,
    val serverId: String,
)

@Serializable
private data class HasJoinedResponse(
    val id: String,
    val name: String? = null,
    val properties: List<ProfilePropertyResponse> = emptyList(),
    val profileActions: List<ProfileActionResponse>? = null,
)

@Serializable
private data class ProfilePropertyResponse(
    val name: String,
    val value: String,
    val signature: String? = null,
)

@Serializable
private data class ProfileActionResponse(
    val type: String,
)
