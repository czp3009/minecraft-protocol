package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Caller-driven access to the game client's Minecraft Friends and Presence services. */
class MinecraftFriendsApi(
    private val httpClient: HttpClient,
) {
    suspend fun fetchFriends(
        accessToken: String,
        etag: String? = null,
    ): MinecraftConditionalResponse<MinecraftFriendsListResponse> {
        val response = httpClient.get(MINECRAFT_FRIENDS_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
            etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        return response.decodeConditionalServiceResponse<
                MinecraftFriendsListResponse,
                MinecraftFriendsErrorResponse
                >(etag, ::MinecraftFriendsResponseException)
    }

    suspend fun updateFriend(
        accessToken: String,
        request: MinecraftFriendActionRequest,
    ): MinecraftFriendsListResponse? {
        val response = httpClient.put(MINECRAFT_FRIENDS_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(MinecraftServiceJson.encodeToString(request))
        }
        return response.decodeOptionalServiceResponse<
                MinecraftFriendsListResponse,
                MinecraftFriendsErrorResponse
                >(::MinecraftFriendsResponseException)
    }

    suspend fun updatePresence(
        accessToken: String,
        request: MinecraftPresenceRequest,
        etag: String? = null,
    ): MinecraftConditionalResponse<MinecraftPresenceResponse> {
        val response = httpClient.post(MINECRAFT_PRESENCE_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
            etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            contentType(ContentType.Application.Json)
            setBody(MinecraftServiceJson.encodeToString(request))
        }
        return response.decodeConditionalServiceResponse<
                MinecraftPresenceResponse,
                MinecraftFriendsErrorResponse
                >(etag, ::MinecraftFriendsResponseException)
    }
}

/**
 * An `ADD` by name or profile ID sends or accepts a request. A `REMOVE` by profile ID removes, declines, or revokes it
 * according to the relationship currently stored by Minecraft Services.
 */
@Serializable
data class MinecraftFriendActionRequest(
    val name: String? = null,
    val profileId: String? = null,
    val updateType: MinecraftFriendUpdateType,
) {
    companion object {
        fun byName(
            name: String,
            updateType: MinecraftFriendUpdateType,
        ): MinecraftFriendActionRequest = MinecraftFriendActionRequest(
            name = name,
            updateType = updateType,
        )

        fun byProfileId(
            profileId: Uuid,
            updateType: MinecraftFriendUpdateType,
        ): MinecraftFriendActionRequest = MinecraftFriendActionRequest(
            profileId = profileId.toHexString(),
            updateType = updateType,
        )
    }
}

@Serializable
enum class MinecraftFriendUpdateType {
    ADD,
    REMOVE,
}

@Serializable
data class MinecraftFriendsListResponse(
    val friends: List<Friend>? = null,
    val incomingRequests: List<Friend>? = null,
    val outgoingRequests: List<Friend>? = null,
) {
    @Serializable
    data class Friend(
        val profileId: String,
        val name: String,
    )
}

@Serializable
data class MinecraftPresenceRequest(
    val status: MinecraftPresenceStatus,
)

@Serializable
data class MinecraftPresenceResponse(
    val presence: List<Presence>? = null,
) {
    @Serializable
    data class Presence(
        val profileId: String,
        val pmid: String? = null,
        val status: MinecraftPresenceStatus,
        val lastUpdated: String? = null,
    )
}

@Serializable
enum class MinecraftPresenceStatus {
    ONLINE,
    PLAYING_OFFLINE,
    PLAYING_REALMS,
    PLAYING_SERVER,
    PLAYING_HOSTED_SERVER,
    OFFLINE,
}

@Serializable
data class MinecraftFriendsErrorResponse(
    val path: String? = null,
    val error: String? = null,
    val errorMessage: String? = null,
    val details: JsonObject? = null,
)

open class MinecraftFriendsResponseException(
    response: HttpResponse,
    val responseBody: String,
    val parsedErrorBody: MinecraftFriendsErrorResponse,
) : ResponseException(response, responseBody)

suspend fun MinecraftFriendsApi.fetchFriends(
    identity: MinecraftOnlineIdentity,
    etag: String? = null,
): MinecraftConditionalResponse<MinecraftFriendsListResponse> = fetchFriends(identity.accessToken, etag)

suspend fun MinecraftFriendsApi.updateFriend(
    identity: MinecraftOnlineIdentity,
    request: MinecraftFriendActionRequest,
): MinecraftFriendsListResponse? = updateFriend(identity.accessToken, request)

suspend fun MinecraftFriendsApi.updatePresence(
    identity: MinecraftOnlineIdentity,
    request: MinecraftPresenceRequest,
    etag: String? = null,
): MinecraftConditionalResponse<MinecraftPresenceResponse> = updatePresence(identity.accessToken, request, etag)

private val MINECRAFT_FRIENDS_ENDPOINT = Url("https://api.minecraftservices.com/friends")
private val MINECRAFT_PRESENCE_ENDPOINT = Url("https://api.minecraftservices.com/presence")
