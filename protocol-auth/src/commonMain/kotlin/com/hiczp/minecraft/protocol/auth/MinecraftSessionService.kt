package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.ProfileProperty
import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.uuid.Uuid

data class JoinedMinecraftProfile(
    val profile: GameProfile,
    val profileActions: Set<String>,
)

class MinecraftSessionService internal constructor(
    private val http: MinecraftAuthenticationHttpRoute,
) {
    constructor(httpClient: HttpClient) : this(
        directAuthenticationRoute(httpClient),
    )

    constructor(
        httpClient: HttpClient,
        relayEndpoint: Url,
    ) : this(
        relayAuthenticationRoute(httpClient, relayEndpoint),
    )

    suspend fun join(
        account: MinecraftOnlineAccount,
        serverHash: String,
    ) {
        joinWithExistingCredentials(
            accessToken = account.accessToken(),
            selectedProfile = account.id,
            serverHash = serverHash,
        )
    }

    /** Advanced migration entry for callers that cannot yet construct [MinecraftOnlineAccount]. */
    suspend fun joinWithExistingCredentials(
        accessToken: String,
        selectedProfile: Uuid,
        serverHash: String,
    ) {
        val response = http.execute(
            MinecraftSessionJoinOperation(
                accessToken = accessToken.validateOpaqueToken("Minecraft access token"),
                selectedProfile = selectedProfile.toUndashedString(),
                serverHash = serverHash,
            ),
        )
        if (
            response.status != HttpStatusCode.NoContent &&
            response.status != HttpStatusCode.OK
        ) {
            throw sessionFailure(response, MinecraftAuthenticationStage.SESSION_JOIN)
        }
    }

    suspend fun hasJoined(
        username: String,
        serverHash: String,
        ipAddress: String? = null,
    ): JoinedMinecraftProfile? {
        require(username.isNotBlank()) { "Minecraft username cannot be blank" }
        val response = http.execute(
            MinecraftSessionHasJoinedOperation(
                username = username,
                serverHash = serverHash,
                ipAddress = ipAddress,
            ),
        )
        return when (response.status) {
            HttpStatusCode.OK -> decodeJoinedProfile(response, username)
            HttpStatusCode.NoContent,
            HttpStatusCode.NotFound,
            HttpStatusCode.Forbidden,
                -> null

            else -> throw sessionFailure(
                response,
                MinecraftAuthenticationStage.SESSION_HAS_JOINED,
            )
        }
    }
}

private fun decodeJoinedProfile(
    response: MinecraftAuthenticationHttpResponse,
    requestedUsername: String,
): JoinedMinecraftProfile {
    val body = try {
        SESSION_JSON.decodeFromString<HasJoinedResponse>(
            response.body.decodeToString(throwOnInvalidSequence = true),
        )
    } catch (failure: IllegalArgumentException) {
        throw MinecraftAuthenticationException(
            "Minecraft hasJoined returned an invalid profile",
            failure,
        )
    } catch (failure: SerializationException) {
        throw MinecraftAuthenticationException(
            "Minecraft hasJoined returned an invalid profile",
            failure,
        )
    }
    return JoinedMinecraftProfile(
        profile = GameProfile(
            id = parseMinecraftUuid(body.id),
            // Matching authlib constructs the authenticated profile with the requested username.
            name = requestedUsername,
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

private fun sessionFailure(
    response: MinecraftAuthenticationHttpResponse,
    stage: MinecraftAuthenticationStage,
): MinecraftAuthenticationException =
    if (response.status.value == 429 || response.status.value >= 500) {
        MinecraftAuthenticationUnavailableException(
            "Minecraft session service is temporarily unavailable at stage $stage",
        )
    } else {
        MinecraftAuthenticationRejectedException(
            stage = stage,
            statusCode = response.status.value,
            message = "Minecraft session service rejected the request at stage $stage",
        )
    }

@Serializable
private data class HasJoinedResponse(
    val id: String,
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
    @SerialName("type")
    val type: String,
)

private val SESSION_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
}
