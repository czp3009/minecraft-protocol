package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Caller-driven access to Minecraft Services' player certificate and service-key endpoints. */
class MinecraftProfileKeyApi(
    private val httpClient: HttpClient,
) {
    /** Requests a fresh player profile key pair. The caller owns token refresh and request timing. */
    suspend fun fetchProfileKeyPair(accessToken: String): MinecraftProfileKeyPairResponse {
        val response = httpClient.post(MINECRAFT_PROFILE_KEY_PAIR_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
            // Official authlib sends this POST with a JSON content type and an explicitly empty body.
            setBody(ByteArrayContent(ByteArray(0), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw response.profileKeyFailure()
        }
        return ProfileKeyJson.decodeFromString(response.bodyAsText())
    }

    /** Fetches Mojang's current public keys. The API does not cache or schedule refreshes. */
    suspend fun fetchServicesPublicKeys(): MinecraftServicesPublicKeysResponse {
        val response = httpClient.get(MINECRAFT_SERVICES_PUBLIC_KEYS_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw response.profileKeyFailure()
        }
        return ProfileKeyJson.decodeFromString(response.bodyAsText())
    }
}

@Serializable
data class MinecraftProfileKeyPairResponse(
    val keyPair: KeyPair,
    @SerialName("publicKeySignatureV2")
    val publicKeySignatureV2: String,
    val expiresAt: String,
    val refreshedAfter: String,
) {
    @Serializable
    data class KeyPair(
        val privateKey: String,
        val publicKey: String,
    )
}

@Serializable
data class MinecraftServicesPublicKeysResponse(
    val profilePropertyKeys: List<Key>? = null,
    val playerCertificateKeys: List<Key>? = null,
) {
    @Serializable
    data class Key(
        val publicKey: String,
    )
}

@Serializable
data class MinecraftProfileKeyErrorResponse(
    val path: String? = null,
    val error: String? = null,
    val errorMessage: String? = null,
    val details: JsonObject? = null,
)

open class MinecraftProfileKeyResponseException(
    response: HttpResponse,
    val responseBody: String,
    val parsedErrorBody: MinecraftProfileKeyErrorResponse,
) : ResponseException(response, responseBody)

suspend fun MinecraftProfileKeyApi.fetchProfileKeyPair(
    identity: MinecraftOnlineIdentity,
): MinecraftProfileKeyPairResponse = fetchProfileKeyPair(identity.accessToken)

private suspend fun HttpResponse.profileKeyFailure(): MinecraftProfileKeyResponseException {
    val responseBody = bodyAsText()
    return MinecraftProfileKeyResponseException(
        response = this,
        responseBody = responseBody,
        parsedErrorBody = ProfileKeyJson.decodeFromString(responseBody),
    )
}

private val ProfileKeyJson = Json {
    ignoreUnknownKeys = true
}
private val MINECRAFT_PROFILE_KEY_PAIR_ENDPOINT = Url("https://api.minecraftservices.com/player/certificates")
private val MINECRAFT_SERVICES_PUBLIC_KEYS_ENDPOINT = Url("https://api.minecraftservices.com/publickeys")
