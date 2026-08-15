@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.hiczp.minecraft.account.auth

import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import kotlin.io.encoding.Base64

class MicrosoftOAuthApi(
    private val httpClient: HttpClient,
) {
    suspend fun tokenWithAuthorizationCode(
        request: MicrosoftAuthorizationCodeTokenRequest,
    ): MicrosoftTokenResponse {
        val response = httpClient.post(MICROSOFT_TOKEN_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(formDataContent(request))
        }
        return response.decodeAccountAuthResponse<MicrosoftTokenResponse, MicrosoftOAuthErrorResponse>(::MicrosoftOAuthResponseException)
    }

    suspend fun tokenWithRefreshToken(
        request: MicrosoftRefreshTokenRequest,
    ): MicrosoftTokenResponse {
        val response = httpClient.post(MICROSOFT_TOKEN_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(formDataContent(request))
        }
        return response.decodeAccountAuthResponse<MicrosoftTokenResponse, MicrosoftOAuthErrorResponse>(::MicrosoftOAuthResponseException)
    }

    suspend fun deviceCode(
        request: MicrosoftDeviceAuthorizationRequest,
    ): MicrosoftDeviceAuthorizationResponse {
        val response = httpClient.post(MICROSOFT_DEVICE_CODE_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(formDataContent(request))
        }
        return response.decodeAccountAuthResponse<MicrosoftDeviceAuthorizationResponse, MicrosoftOAuthErrorResponse>(::MicrosoftOAuthResponseException)
    }

    suspend fun tokenWithDeviceCode(
        request: MicrosoftDeviceCodeTokenRequest,
    ): MicrosoftTokenResponse {
        val response = httpClient.post(MICROSOFT_TOKEN_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(formDataContent(request))
        }
        return response.decodeAccountAuthResponse<MicrosoftTokenResponse, MicrosoftOAuthErrorResponse>(::MicrosoftOAuthResponseException)
    }
}

@Serializable
data class MicrosoftAuthorizationCodeTokenRequest(
    @SerialName("grant_type")
    val grantType: String,
    val code: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("redirect_uri")
    val redirectUri: String,
    @SerialName("code_verifier")
    val codeVerifier: String? = null,
    val scope: String? = null,
)

@Serializable
data class MicrosoftDeviceAuthorizationRequest(
    @SerialName("client_id")
    val clientId: String,
    val scope: String,
)

@Serializable
data class MicrosoftDeviceAuthorizationResponse(
    @SerialName("device_code")
    val deviceCode: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("verification_uri")
    val verificationUri: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    val interval: Long,
    val message: String,
)

@Serializable
data class MicrosoftDeviceCodeTokenRequest(
    @SerialName("grant_type")
    val grantType: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("device_code")
    val deviceCode: String,
)

@Serializable
data class MicrosoftRefreshTokenRequest(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("grant_type")
    val grantType: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    val scope: String? = null,
)

@Serializable
data class MicrosoftTokenResponse(
    @SerialName("token_type")
    val tokenType: String,
    val scope: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("ext_expires_in")
    val extExpiresIn: Long? = null,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
)

@Serializable
data class MicrosoftOAuthErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String? = null,
    @SerialName("error_codes")
    val errorCodes: List<Long>? = null,
    val timestamp: String? = null,
    @SerialName("trace_id")
    val traceId: String? = null,
    @SerialName("correlation_id")
    val correlationId: String? = null,
)

object MicrosoftOAuthTools {
    const val MINECRAFT_SCOPE = "xboxlive.signin xboxlive.offline_access"
    const val AUTHORIZATION_CODE_GRANT_TYPE = "authorization_code"
    const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    const val REFRESH_TOKEN_GRANT_TYPE = "refresh_token"

    fun generateState(byteCount: Int = OAUTH_RANDOM_BYTES): String =
        CryptographyRandom.nextBytes(byteCount).base64Url()

    fun generateCodeVerifier(byteCount: Int = OAUTH_RANDOM_BYTES): String =
        CryptographyRandom.nextBytes(byteCount).base64Url()

    fun s256CodeChallenge(codeVerifier: String): String = codeVerifier
        .encodeUtf8()
        .sha256()
        .toByteArray()
        .base64Url()

    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        codeVerifier: String,
    ): Url = URLBuilder(MICROSOFT_AUTHORIZATION_ENDPOINT).apply {
        parameters.append("client_id", clientId)
        parameters.append("response_type", "code")
        parameters.append("redirect_uri", redirectUri)
        parameters.append("response_mode", "query")
        parameters.append("scope", MINECRAFT_SCOPE)
        parameters.append("state", state)
        parameters.append("code_challenge", s256CodeChallenge(codeVerifier))
        parameters.append("code_challenge_method", "S256")
    }.build()

    fun authorizationCodeTokenRequest(
        clientId: String,
        authorizationCode: String,
        redirectUri: String,
        codeVerifier: String,
    ): MicrosoftAuthorizationCodeTokenRequest = MicrosoftAuthorizationCodeTokenRequest(
        grantType = AUTHORIZATION_CODE_GRANT_TYPE,
        code = authorizationCode,
        clientId = clientId,
        redirectUri = redirectUri,
        codeVerifier = codeVerifier,
        scope = MINECRAFT_SCOPE,
    )

    fun deviceAuthorizationRequest(clientId: String): MicrosoftDeviceAuthorizationRequest =
        MicrosoftDeviceAuthorizationRequest(
            clientId = clientId,
            scope = MINECRAFT_SCOPE,
        )

    fun deviceCodeTokenRequest(
        clientId: String,
        deviceCode: String,
    ): MicrosoftDeviceCodeTokenRequest = MicrosoftDeviceCodeTokenRequest(
        grantType = DEVICE_CODE_GRANT_TYPE,
        clientId = clientId,
        deviceCode = deviceCode,
    )

    fun refreshTokenRequest(
        clientId: String,
        refreshToken: String,
    ): MicrosoftRefreshTokenRequest = MicrosoftRefreshTokenRequest(
        clientId = clientId,
        grantType = REFRESH_TOKEN_GRANT_TYPE,
        refreshToken = refreshToken,
        scope = MINECRAFT_SCOPE,
    )
}

private val MICROSOFT_AUTHORIZATION_ENDPOINT = Url("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize")

private val MICROSOFT_TOKEN_ENDPOINT = Url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")

private val MICROSOFT_DEVICE_CODE_ENDPOINT = Url("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")

private fun ByteArray.base64Url(): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(this)

private const val OAUTH_RANDOM_BYTES = 32
