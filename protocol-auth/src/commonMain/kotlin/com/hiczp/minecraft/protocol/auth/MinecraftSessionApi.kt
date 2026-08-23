@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.ProfileProperty
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.encodeToStringMap
import kotlin.uuid.Uuid

class MinecraftSessionApi(
    private val httpClient: HttpClient,
) {
    suspend fun join(
        request: MinecraftSessionJoinRequest,
    ) {
        val response = httpClient.post(MINECRAFT_SESSION_JOIN_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(
                TextContent(
                    text = SessionJson.encodeToString(request),
                    contentType = ContentType.Application.Json,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throw response.failure()
        }
    }

    suspend fun hasJoined(
        request: MinecraftSessionHasJoinedRequest,
    ): MinecraftSessionHasJoinedResponse? {
        val endpoint = URLBuilder(MINECRAFT_SESSION_HAS_JOINED_ENDPOINT).apply {
            parameters.appendAll(Properties.encodeToStringMap(request))
        }.build()
        val response = httpClient.get(endpoint) {
            expectSuccess = false
            accept(ContentType.Application.Json)
        }
        return when (response.status) {
            HttpStatusCode.OK -> SessionJson.decodeFromString(response.bodyAsText())
            HttpStatusCode.NoContent -> null
            else -> throw response.failure()
        }
    }

    suspend fun fetchProfile(
        profileId: String,
        request: MinecraftSessionProfileRequest,
    ): MinecraftSessionProfileResponse? {
        val endpoint = URLBuilder(MINECRAFT_SESSION_PROFILE_ENDPOINT).apply {
            appendPathSegments(profileId)
            parameters.appendAll(Properties.encodeToStringMap(request))
        }.build()
        val response = httpClient.get(endpoint) {
            expectSuccess = false
            accept(ContentType.Application.Json)
        }
        return when (response.status) {
            HttpStatusCode.OK -> SessionJson.decodeFromString(response.bodyAsText())
            HttpStatusCode.NoContent -> null
            else -> throw response.failure()
        }
    }
}

@Serializable
data class MinecraftSessionJoinRequest(
    val accessToken: String,
    val selectedProfile: String,
    val serverId: String,
)

@Serializable
data class MinecraftSessionHasJoinedRequest(
    val username: String,
    val serverId: String,
    val ip: String? = null,
)

@Serializable
data class MinecraftSessionProfileRequest(
    val unsigned: Boolean,
)

@Serializable
data class MinecraftSessionHasJoinedResponse(
    val id: String,
    val properties: List<Property>? = null,
    val profileActions: List<ProfileAction>? = null,
) {
    @Serializable
    data class Property(
        val name: String,
        val value: String,
        val signature: String? = null,
    )

    @Serializable
    data class ProfileAction(
        val type: String,
    )
}

@Serializable
data class MinecraftSessionErrorResponse(
    val path: String? = null,
    val error: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class MinecraftSessionProfileResponse(
    val id: String,
    val name: String,
    val properties: List<Property>? = null,
    val profileActions: List<ProfileAction>? = null,
) {
    @Serializable
    data class Property(
        val name: String,
        val value: String,
        val signature: String? = null,
    )

    @Serializable
    data class ProfileAction(
        val type: String,
    )
}

open class MinecraftSessionResponseException(
    response: HttpResponse,
    val responseBody: String,
    val parsedErrorBody: MinecraftSessionErrorResponse,
) : ResponseException(response, responseBody)

suspend fun MinecraftSessionApi.join(
    accessToken: String,
    selectedProfile: Uuid,
    serverId: MinecraftServerHash,
) = join(
    MinecraftSessionJoinRequest(
        accessToken = accessToken,
        selectedProfile = selectedProfile.toHexString(),
        serverId = serverId.value,
    ),
)

suspend fun MinecraftSessionApi.join(
    identity: MinecraftOnlineIdentity,
    serverId: MinecraftServerHash,
) = join(
    accessToken = identity.accessToken,
    selectedProfile = identity.id,
    serverId = serverId,
)

suspend fun MinecraftSessionApi.hasJoined(
    username: String,
    serverId: MinecraftServerHash,
    ip: String? = null,
): MinecraftSessionHasJoinedResponse? = hasJoined(
    MinecraftSessionHasJoinedRequest(
        username = username,
        serverId = serverId.value,
        ip = ip,
    ),
)

suspend fun MinecraftSessionApi.fetchProfile(
    profileId: Uuid,
    requireSecure: Boolean,
): MinecraftSessionProfileResponse? = fetchProfile(
    profileId = profileId.toHexString(),
    request = MinecraftSessionProfileRequest(unsigned = !requireSecure),
)

fun MinecraftSessionHasJoinedResponse.toGameProfile(
    username: String,
): GameProfile = GameProfile(
    id = Uuid.parse(id),
    name = username,
    properties = properties.orEmpty().map { property ->
        ProfileProperty(
            name = property.name,
            value = property.value,
            signature = property.signature,
        )
    },
)

fun MinecraftSessionProfileResponse.toGameProfile(): GameProfile = GameProfile(
    id = Uuid.parse(id),
    name = name,
    properties = properties.orEmpty().map { property ->
        ProfileProperty(
            name = property.name,
            value = property.value,
            signature = property.signature,
        )
    },
)

private suspend fun HttpResponse.failure(): MinecraftSessionResponseException {
    val responseBody = bodyAsText()
    return MinecraftSessionResponseException(
        response = this,
        responseBody = responseBody,
        parsedErrorBody = SessionJson.decodeFromString(responseBody),
    )
}

private val SessionJson = Json {
    ignoreUnknownKeys = true
}
private val MINECRAFT_SESSION_JOIN_ENDPOINT = Url("https://sessionserver.mojang.com/session/minecraft/join")
private val MINECRAFT_SESSION_HAS_JOINED_ENDPOINT = Url("https://sessionserver.mojang.com/session/minecraft/hasJoined")
private val MINECRAFT_SESSION_PROFILE_ENDPOINT = Url("https://sessionserver.mojang.com/session/minecraft/profile/")
