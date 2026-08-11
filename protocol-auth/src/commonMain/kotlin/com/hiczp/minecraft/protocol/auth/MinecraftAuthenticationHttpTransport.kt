package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal fun directAuthenticationRoute(
    httpClient: HttpClient,
): MinecraftAuthenticationHttpRoute = DirectMinecraftAuthenticationHttpRoute(httpClient)

internal fun relayAuthenticationRoute(
    httpClient: HttpClient,
    endpoint: Url,
): MinecraftAuthenticationHttpRoute {
    require(endpoint.protocol.name == "https") {
        "The authentication relay endpoint must use HTTPS"
    }
    require(endpoint.user.isNullOrEmpty() && endpoint.password.isNullOrEmpty()) {
        "The authentication relay endpoint cannot contain user information"
    }
    return RelayMinecraftAuthenticationHttpRoute(httpClient, endpoint)
}

internal interface MinecraftAuthenticationHttpRoute {
    val channel: MicrosoftOAuthTransportChannel

    suspend fun execute(operation: MinecraftAuthenticationOperation): MinecraftAuthenticationHttpResponse
}

internal data class MinecraftAuthenticationHttpResponse(
    val status: HttpStatusCode,
    val contentType: ContentType?,
    val retryAfter: String?,
    val body: ByteArray,
)

internal enum class MicrosoftOAuthTransportChannel {
    DIRECT,
    RELAY,
}

internal sealed class MinecraftAuthenticationOperation(
    val relayOperation: MinecraftAuthenticationRelayOperation,
    val maximumResponseBytes: Int,
) {
    abstract val url: Url
    abstract val method: HttpMethod

    open fun configure(request: HttpRequestBuilder) = Unit

    open val microsoftClientId: String? = null

    abstract fun wirePayload(): JsonObject

    override fun toString(): String =
        "MinecraftAuthenticationOperation(operation=$relayOperation, payload=<redacted>)"
}

internal class MicrosoftDeviceAuthorizationOperation(
    val tenant: String,
    override val microsoftClientId: String,
    val scopes: String,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.MICROSOFT_DEVICE_AUTHORIZATION,
    MAXIMUM_OAUTH_RESPONSE_BYTES,
) {
    override val url: Url = microsoftEndpoint(tenant, "devicecode")
    override val method: HttpMethod = HttpMethod.Post

    override fun configure(request: HttpRequestBuilder) {
        request.setBody(
            FormDataContent(
                Parameters.build {
                    append("client_id", microsoftClientId)
                    append("scope", scopes)
                },
            ),
        )
    }

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("tenant", tenant)
        put("clientId", microsoftClientId)
        put("scopes", scopes)
    }
}

internal sealed class MicrosoftTokenGrant {
    abstract val name: String

    abstract fun appendTo(parameters: ParametersBuilder)

    abstract fun wirePayload(): JsonObject

    class AuthorizationCode(
        private val code: String,
        private val redirectUri: String,
        private val codeVerifier: String,
    ) : MicrosoftTokenGrant() {
        override val name: String = "authorization_code"

        override fun appendTo(parameters: ParametersBuilder) {
            parameters.append("grant_type", name)
            parameters.append("code", code)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("code_verifier", codeVerifier)
        }

        override fun wirePayload(): JsonObject = buildJsonObject {
            put("grantType", name)
            put("code", code)
            put("redirectUri", redirectUri)
            put("codeVerifier", codeVerifier)
        }
    }

    class DeviceCode(
        private val deviceCode: String,
    ) : MicrosoftTokenGrant() {
        override val name: String = DEVICE_CODE_GRANT

        override fun appendTo(parameters: ParametersBuilder) {
            parameters.append("grant_type", name)
            parameters.append("device_code", deviceCode)
        }

        override fun wirePayload(): JsonObject = buildJsonObject {
            put("grantType", name)
            put("deviceCode", deviceCode)
        }
    }

    class RefreshToken(
        private val refreshToken: String,
        private val scopes: String,
    ) : MicrosoftTokenGrant() {
        override val name: String = "refresh_token"

        override fun appendTo(parameters: ParametersBuilder) {
            parameters.append("grant_type", name)
            parameters.append("refresh_token", refreshToken)
            parameters.append("scope", scopes)
        }

        override fun wirePayload(): JsonObject = buildJsonObject {
            put("grantType", name)
            put("refreshToken", refreshToken)
            put("scopes", scopes)
        }
    }
}

internal class MicrosoftTokenOperation(
    val tenant: String,
    override val microsoftClientId: String,
    val grant: MicrosoftTokenGrant,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.MICROSOFT_TOKEN,
    MAXIMUM_OAUTH_RESPONSE_BYTES,
) {
    override val url: Url = microsoftEndpoint(tenant, "token")
    override val method: HttpMethod = HttpMethod.Post

    override fun configure(request: HttpRequestBuilder) {
        request.setBody(
            FormDataContent(
                Parameters.build {
                    append("client_id", microsoftClientId)
                    grant.appendTo(this)
                },
            ),
        )
    }

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("tenant", tenant)
        put("clientId", microsoftClientId)
        put("grant", grant.wirePayload())
    }
}

internal class XboxUserAuthenticationOperation(
    private val microsoftAccessToken: String,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.XBOX_USER_AUTHENTICATION,
    MAXIMUM_XBOX_RESPONSE_BYTES,
) {
    override val url: Url = Url(XBOX_USER_AUTHENTICATION_URL)
    override val method: HttpMethod = HttpMethod.Post

    override fun configure(request: HttpRequestBuilder) {
        request.headers {
            append(XBOX_CONTRACT_VERSION_HEADER, XBOX_CONTRACT_VERSION)
        }
        request.setJsonBody(
            buildJsonObject {
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
                put(
                    "Properties",
                    buildJsonObject {
                        put("AuthMethod", "RPS")
                        put("SiteName", "user.auth.xboxlive.com")
                        put("RpsTicket", "d=$microsoftAccessToken")
                    },
                )
            },
        )
    }

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("microsoftAccessToken", microsoftAccessToken)
    }
}

internal class XboxXstsAuthorizationOperation(
    private val xboxUserToken: String,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.XBOX_XSTS_AUTHORIZATION,
    MAXIMUM_XBOX_RESPONSE_BYTES,
) {
    override val url: Url = Url(XBOX_XSTS_AUTHORIZATION_URL)
    override val method: HttpMethod = HttpMethod.Post

    override fun configure(request: HttpRequestBuilder) {
        request.headers {
            append(XBOX_CONTRACT_VERSION_HEADER, XBOX_CONTRACT_VERSION)
        }
        request.setJsonBody(
            buildJsonObject {
                put("RelyingParty", "rp://api.minecraftservices.com/")
                put("TokenType", "JWT")
                put(
                    "Properties",
                    buildJsonObject {
                        put("SandboxId", "RETAIL")
                        put(
                            "UserTokens",
                            kotlinx.serialization.json.buildJsonArray {
                                add(xboxUserToken)
                            },
                        )
                    },
                )
            },
        )
    }

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("xboxUserToken", xboxUserToken)
    }
}

internal class MinecraftXboxLoginOperation(
    private val userHash: String,
    private val xstsToken: String,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.MINECRAFT_XBOX_LOGIN,
    MAXIMUM_MINECRAFT_SERVICE_RESPONSE_BYTES,
) {
    override val url: Url = Url(MINECRAFT_XBOX_LOGIN_URL)
    override val method: HttpMethod = HttpMethod.Post

    override fun configure(request: HttpRequestBuilder) {
        request.setJsonBody(
            buildJsonObject {
                put("identityToken", "XBL3.0 x=$userHash;$xstsToken")
            },
        )
    }

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("userHash", userHash)
        put("xstsToken", xstsToken)
    }
}

internal abstract class MinecraftBearerOperation(
    relayOperation: MinecraftAuthenticationRelayOperation,
    private val minecraftAccessToken: String,
) : MinecraftAuthenticationOperation(
    relayOperation,
    MAXIMUM_MINECRAFT_SERVICE_RESPONSE_BYTES,
) {
    override val method: HttpMethod = HttpMethod.Get

    override fun configure(request: HttpRequestBuilder) {
        request.headers {
            append(HttpHeaders.Authorization, "Bearer $minecraftAccessToken")
        }
    }

    final override fun wirePayload(): JsonObject = buildJsonObject {
        put("minecraftAccessToken", minecraftAccessToken)
    }
}

internal class MinecraftEntitlementsOperation(
    minecraftAccessToken: String,
) : MinecraftBearerOperation(
    MinecraftAuthenticationRelayOperation.MINECRAFT_ENTITLEMENTS,
    minecraftAccessToken,
) {
    override val url: Url = Url(MINECRAFT_ENTITLEMENTS_URL)
}

internal class MinecraftProfileOperation(
    minecraftAccessToken: String,
) : MinecraftBearerOperation(
    MinecraftAuthenticationRelayOperation.MINECRAFT_PROFILE,
    minecraftAccessToken,
) {
    override val url: Url = Url(MINECRAFT_PROFILE_URL)
}

internal class MinecraftSessionJoinOperation(
    private val accessToken: String,
    private val selectedProfile: String,
    private val serverHash: String,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.SESSION_JOIN,
    MAXIMUM_SESSION_RESPONSE_BYTES,
) {
    override val url: Url = Url(MINECRAFT_SESSION_JOIN_URL)
    override val method: HttpMethod = HttpMethod.Post

    override fun configure(request: HttpRequestBuilder) {
        request.setJsonBody(
            buildJsonObject {
                put("accessToken", accessToken)
                put("selectedProfile", selectedProfile)
                put("serverId", serverHash)
            },
        )
    }

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("accessToken", accessToken)
        put("selectedProfile", selectedProfile)
        put("serverHash", serverHash)
    }
}

internal class MinecraftSessionHasJoinedOperation(
    private val username: String,
    private val serverHash: String,
    private val ipAddress: String?,
) : MinecraftAuthenticationOperation(
    MinecraftAuthenticationRelayOperation.SESSION_HAS_JOINED,
    MAXIMUM_SESSION_RESPONSE_BYTES,
) {
    override val url: Url = URLBuilder(MINECRAFT_SESSION_HAS_JOINED_URL).apply {
        parameters.append("username", username)
        parameters.append("serverId", serverHash)
        if (ipAddress != null) parameters.append("ip", ipAddress)
    }.build()
    override val method: HttpMethod = HttpMethod.Get

    override fun wirePayload(): JsonObject = buildJsonObject {
        put("username", username)
        put("serverHash", serverHash)
        if (ipAddress != null) put("ipAddress", ipAddress)
    }
}

private class DirectMinecraftAuthenticationHttpRoute(
    private val httpClient: HttpClient,
) : MinecraftAuthenticationHttpRoute {
    override val channel: MicrosoftOAuthTransportChannel = MicrosoftOAuthTransportChannel.DIRECT

    override suspend fun execute(
        operation: MinecraftAuthenticationOperation,
    ): MinecraftAuthenticationHttpResponse = executeDirect(httpClient, operation)
}

internal suspend fun executeDirect(
    httpClient: HttpClient,
    operation: MinecraftAuthenticationOperation,
): MinecraftAuthenticationHttpResponse {
    val response = httpClient.request(operation.url) {
        method = operation.method
        expectSuccess = false
        operation.configure(this)
    }
    val body = response.bodyAsChannel().readBuffer(
        operation.maximumResponseBytes + 1,
    )
    if (body.size > operation.maximumResponseBytes) {
        throw MinecraftAuthenticationException(
            "Authentication response exceeded its endpoint limit",
        )
    }
    return MinecraftAuthenticationHttpResponse(
        status = response.status,
        contentType = response.contentType(),
        retryAfter = response.headers[HttpHeaders.RetryAfter],
        body = body.readByteArray(),
    )
}

private class RelayMinecraftAuthenticationHttpRoute(
    private val httpClient: HttpClient,
    private val endpoint: Url,
) : MinecraftAuthenticationHttpRoute {
    override val channel: MicrosoftOAuthTransportChannel = MicrosoftOAuthTransportChannel.RELAY

    override suspend fun execute(
        operation: MinecraftAuthenticationOperation,
    ): MinecraftAuthenticationHttpResponse {
        val requestBody = RELAY_JSON.encodeToString(
            MinecraftAuthenticationRelayWireRequest(
                version = MINECRAFT_AUTHENTICATION_RELAY_VERSION,
                operation = operation.relayOperation.wireName,
                payload = operation.wirePayload(),
            ),
        )
        require(requestBody.encodeToByteArray().size <= MAXIMUM_RELAY_REQUEST_BYTES) {
            "Authentication relay request exceeds its limit"
        }
        val response = httpClient.request(endpoint) {
            method = HttpMethod.Post
            expectSuccess = false
            contentType(MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE)
            setBody(TextContent(requestBody, MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE))
        }
        val responseBytes = response.bodyAsChannel().readBuffer(
            MAXIMUM_RELAY_RESPONSE_BYTES + 1,
        )
        if (responseBytes.size > MAXIMUM_RELAY_RESPONSE_BYTES) {
            throw MinecraftAuthenticationException(
                "Authentication relay response exceeds its limit",
            )
        }
        if (!response.status.isSuccess()) {
            throw relayHttpFailure(response.status)
        }
        val responseContentType = response.contentType()
        if (
            responseContentType == null ||
            !responseContentType.match(MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE)
        ) {
            throw MinecraftAuthenticationException(
                "Authentication relay returned an invalid content type",
            )
        }
        val wire = decodeRelayResponse(responseBytes.readByteArray())
        if (
            wire.version != MINECRAFT_AUTHENTICATION_RELAY_VERSION ||
            wire.operation != operation.relayOperation.wireName ||
            wire.status !in 100..599
        ) {
            throw MinecraftAuthenticationException(
                "Authentication relay response does not match the request",
            )
        }
        val body = wire.body.encodeToByteArray()
        if (body.size > operation.maximumResponseBytes) {
            throw MinecraftAuthenticationException(
                "Relayed authentication response exceeded its endpoint limit",
            )
        }
        return MinecraftAuthenticationHttpResponse(
            status = HttpStatusCode.fromValue(wire.status),
            contentType = wire.contentType?.let(ContentType::parse),
            retryAfter = wire.retryAfter,
            body = body,
        )
    }
}

private fun HttpRequestBuilder.setJsonBody(body: JsonObject) {
    contentType(ContentType.Application.Json)
    setBody(TextContent(RELAY_JSON.encodeToString(JsonObject.serializer(), body), ContentType.Application.Json))
}

private fun microsoftEndpoint(tenant: String, endpoint: String): Url =
    URLBuilder(MICROSOFT_LOGIN_BASE_URL).apply {
        pathSegments = listOf(tenant, "oauth2", "v2.0", endpoint)
    }.build()

private fun decodeRelayResponse(body: ByteArray): MinecraftAuthenticationRelayWireResponse =
    try {
        RELAY_JSON.decodeFromString(
            MinecraftAuthenticationRelayWireResponse.serializer(),
            body.decodeToString(throwOnInvalidSequence = true),
        )
    } catch (failure: IllegalArgumentException) {
        throw MinecraftAuthenticationException(
            "Authentication relay returned an invalid response envelope",
            failure,
        )
    }

private fun relayHttpFailure(status: HttpStatusCode): MinecraftAuthenticationException =
    if (status.value == 429 || status.value >= 500) {
        MinecraftAuthenticationUnavailableException(
            "Authentication relay failed with HTTP ${status.value}",
        )
    } else {
        MinecraftAuthenticationException(
            "Authentication relay rejected the request with HTTP ${status.value}",
        )
    }

@Serializable
internal data class MinecraftAuthenticationRelayWireRequest(
    val version: Int,
    val operation: String,
    val payload: JsonObject,
) {
    override fun toString(): String =
        "MinecraftAuthenticationRelayWireRequest(version=$version, operation=$operation, payload=<redacted>)"
}

@Serializable
internal data class MinecraftAuthenticationRelayWireResponse(
    val version: Int,
    val operation: String,
    val status: Int,
    val contentType: String? = null,
    val retryAfter: String? = null,
    val body: String = "",
) {
    override fun toString(): String =
        "MinecraftAuthenticationRelayWireResponse(version=$version, operation=$operation, status=$status, body=<redacted>)"
}

internal val RELAY_JSON: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

internal const val MINECRAFT_AUTHENTICATION_RELAY_VERSION = 1
internal val MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE: ContentType =
    ContentType("application", "vnd.minecraft-protocol.authentication+json")
internal const val MAXIMUM_RELAY_REQUEST_BYTES = 65_536
internal const val MAXIMUM_RELAY_RESPONSE_BYTES = 2_097_152
internal const val MAXIMUM_OAUTH_RESPONSE_BYTES = 65_536
internal const val MAXIMUM_XBOX_RESPONSE_BYTES = 65_536
internal const val MAXIMUM_MINECRAFT_SERVICE_RESPONSE_BYTES = 1_048_576
internal const val MAXIMUM_SESSION_RESPONSE_BYTES = 1_048_576

private const val MICROSOFT_LOGIN_BASE_URL = "https://login.microsoftonline.com"
private const val XBOX_USER_AUTHENTICATION_URL = "https://user.auth.xboxlive.com/user/authenticate"
private const val XBOX_XSTS_AUTHORIZATION_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
private const val MINECRAFT_XBOX_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
private const val MINECRAFT_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore"
private const val MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
private const val MINECRAFT_SESSION_JOIN_URL = "https://sessionserver.mojang.com/session/minecraft/join"
private const val MINECRAFT_SESSION_HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
private const val XBOX_CONTRACT_VERSION_HEADER = "x-xbl-contract-version"
private const val XBOX_CONTRACT_VERSION = "1"
private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
