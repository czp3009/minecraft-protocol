package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Caller-driven access to the game client's Minecraft Services user attributes and block list. */
class MinecraftUserApi(
    private val httpClient: HttpClient,
) {
    suspend fun fetchAttributes(accessToken: String): MinecraftUserAttributesResponse? {
        val response = httpClient.get(MINECRAFT_USER_ATTRIBUTES_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
        }
        return response.decodeOptionalServiceResponse<
                MinecraftUserAttributesResponse,
                MinecraftUserErrorResponse
                >(::MinecraftUserResponseException)
    }

    suspend fun updateAttributes(
        accessToken: String,
        request: MinecraftUserAttributesRequest,
    ): MinecraftUserAttributesResponse? {
        val response = httpClient.post(MINECRAFT_USER_ATTRIBUTES_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
            setBody(minecraftServiceJsonContent(request))
        }
        return response.decodeOptionalServiceResponse<
                MinecraftUserAttributesResponse,
                MinecraftUserErrorResponse
                >(::MinecraftUserResponseException)
    }

    suspend fun fetchBlockList(accessToken: String): MinecraftBlockListResponse? {
        val response = httpClient.get(MINECRAFT_BLOCK_LIST_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
        }
        return response.decodeOptionalServiceResponse<
                MinecraftBlockListResponse,
                MinecraftUserErrorResponse
                >(::MinecraftUserResponseException)
    }
}

@Serializable
data class MinecraftUserAttributesRequest(
    val profanityFilterPreferences: ProfanityFilterPreferences? = null,
    val friendsPreferences: FriendsPreferences? = null,
) {
    @Serializable
    data class ProfanityFilterPreferences(
        val enabled: Boolean,
    )

    @Serializable
    data class FriendsPreferences(
        val friends: MinecraftToggleValue,
        val acceptInvites: MinecraftToggleValue,
    )
}

@Serializable
data class MinecraftUserAttributesResponse(
    val privileges: Privileges? = null,
    val profanityFilterPreferences: ProfanityFilterPreferences? = null,
    val friendsPreferences: FriendsPreferences? = null,
    val chatPreferences: ChatPreferences? = null,
    val banStatus: BanStatus? = null,
) {
    @Serializable
    data class Privileges(
        val onlineChat: Privilege? = null,
        val multiplayerServer: Privilege? = null,
        val multiplayerRealms: Privilege? = null,
        val telemetry: Privilege? = null,
        val optionalTelemetry: Privilege? = null,
    ) {
        @Serializable
        data class Privilege(
            val enabled: Boolean,
        )
    }

    @Serializable
    data class ProfanityFilterPreferences(
        val enabled: Boolean,
    )

    @Serializable
    data class FriendsPreferences(
        val friends: MinecraftToggleValue,
        val acceptInvites: MinecraftToggleValue,
    )

    @Serializable
    data class ChatPreferences(
        val textCommunication: MinecraftChatToggleValue,
    )

    @Serializable
    data class BanStatus(
        val bannedScopes: Map<String, BannedScope>,
    ) {
        @Serializable
        data class BannedScope(
            val banId: String? = null,
            val expires: String? = null,
            val reason: String? = null,
            val reasonMessage: String? = null,
        )
    }
}

@Serializable
data class MinecraftBlockListResponse(
    val blockedProfiles: Set<String>? = null,
)

@Serializable
enum class MinecraftToggleValue {
    DISABLED,
    ENABLED,
}

@Serializable
enum class MinecraftChatToggleValue {
    DISABLED,
    FRIENDS_ONLY,
    ENABLED,
}

@Serializable
data class MinecraftUserErrorResponse(
    val path: String? = null,
    val error: String? = null,
    val errorMessage: String? = null,
    val details: JsonObject? = null,
)

open class MinecraftUserResponseException(
    response: HttpResponse,
    val responseBody: String,
    val parsedErrorBody: MinecraftUserErrorResponse,
) : ResponseException(response, responseBody)

suspend fun MinecraftUserApi.fetchAttributes(
    identity: MinecraftOnlineIdentity,
): MinecraftUserAttributesResponse? = fetchAttributes(identity.accessToken)

suspend fun MinecraftUserApi.updateAttributes(
    identity: MinecraftOnlineIdentity,
    request: MinecraftUserAttributesRequest,
): MinecraftUserAttributesResponse? = updateAttributes(identity.accessToken, request)

suspend fun MinecraftUserApi.fetchBlockList(
    identity: MinecraftOnlineIdentity,
): MinecraftBlockListResponse? = fetchBlockList(identity.accessToken)

private val MINECRAFT_USER_ATTRIBUTES_ENDPOINT = Url("https://api.minecraftservices.com/player/attributes")
private val MINECRAFT_BLOCK_LIST_ENDPOINT = Url("https://api.minecraftservices.com/privacy/blocklist")
