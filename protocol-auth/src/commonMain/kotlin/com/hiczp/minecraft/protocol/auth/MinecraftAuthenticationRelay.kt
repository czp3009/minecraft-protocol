package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Fixed operations that an application backend can explicitly allow on its authentication relay. */
enum class MinecraftAuthenticationRelayOperation(
    internal val wireName: String,
) {
    MICROSOFT_DEVICE_AUTHORIZATION("microsoft_device_authorization"),
    MICROSOFT_TOKEN("microsoft_token"),
    XBOX_USER_AUTHENTICATION("xbox_user_authentication"),
    XBOX_XSTS_AUTHORIZATION("xbox_xsts_authorization"),
    MINECRAFT_XBOX_LOGIN("minecraft_xbox_login"),
    MINECRAFT_ENTITLEMENTS("minecraft_entitlements"),
    MINECRAFT_PROFILE("minecraft_profile"),
    SESSION_JOIN("session_join"),
    SESSION_HAS_JOINED("session_has_joined"),
    ;

    internal companion object {
        fun fromWireName(value: String): MinecraftAuthenticationRelayOperation? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Fail-closed relay policy.
 *
 * OAuth operations additionally require an allowlisted caller-owned Microsoft application ID. Session server-only
 * operations are not enabled unless explicitly present in [allowedOperations].
 */
class MinecraftAuthenticationRelayPolicy(
    allowedOperations: Set<MinecraftAuthenticationRelayOperation>,
    allowedMicrosoftClientIds: Set<String> = emptySet(),
) {
    internal val operations: Set<MinecraftAuthenticationRelayOperation> = allowedOperations.toSet()
    internal val microsoftClientIds: Set<String> = allowedMicrosoftClientIds.mapTo(linkedSetOf()) { clientId ->
        clientId.validateMicrosoftClientId()
    }

    init {
        require(operations.isNotEmpty()) {
            "At least one authentication relay operation must be allowed"
        }
        if (
            MinecraftAuthenticationRelayOperation.MICROSOFT_DEVICE_AUTHORIZATION in operations ||
            MinecraftAuthenticationRelayOperation.MICROSOFT_TOKEN in operations
        ) {
            require(microsoftClientIds.isNotEmpty()) {
                "Relay OAuth operations require at least one allowed Microsoft client ID"
            }
        }
    }

    override fun toString(): String =
        "MinecraftAuthenticationRelayPolicy(allowedOperations=$operations, allowedMicrosoftClientIds=<redacted>)"
}

/** Framework-neutral bounded request passed from an application endpoint to [MinecraftAuthenticationRelayHandler]. */
class MinecraftAuthenticationRelayRequest(
    val contentType: String?,
    body: ByteArray,
) {
    private val requestBody = body.copyOf()

    val body: ByteArray
        get() = requestBody.copyOf()

    override fun toString(): String =
        "MinecraftAuthenticationRelayRequest(contentType=$contentType, body=<redacted>)"
}

/** Framework-neutral response written by the application through its own server framework. */
class MinecraftAuthenticationRelayResponse internal constructor(
    val statusCode: Int,
    val contentType: String,
    val cacheControl: String,
    val retryAfter: String?,
    body: ByteArray,
) {
    private val responseBody = body.copyOf()

    val body: ByteArray
        get() = responseBody.copyOf()

    override fun toString(): String =
        "MinecraftAuthenticationRelayResponse(statusCode=$statusCode, contentType=$contentType, cacheControl=$cacheControl, body=<redacted>)"
}

/**
 * Executes one versioned, allowlisted authentication operation against fixed upstream HTTPS endpoints.
 *
 * The handler does not start a server, install an engine, authenticate the relay caller, enforce CSRF or rate limits,
 * or close [upstreamHttpClient]. Those application and deployment responsibilities remain outside this library.
 */
class MinecraftAuthenticationRelayHandler(
    private val upstreamHttpClient: HttpClient,
    private val policy: MinecraftAuthenticationRelayPolicy,
) {
    suspend fun handle(
        request: MinecraftAuthenticationRelayRequest,
    ): MinecraftAuthenticationRelayResponse {
        if (request.body.size > MAXIMUM_RELAY_REQUEST_BYTES) {
            return relayError(HttpStatusCode.PayloadTooLarge, "request_too_large")
        }
        val contentType = request.contentType?.let { value ->
            runCatching { ContentType.parse(value) }.getOrNull()
        }
        if (
            contentType == null ||
            !contentType.match(MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE)
        ) {
            return relayError(HttpStatusCode.UnsupportedMediaType, "invalid_content_type")
        }

        val wireRequest = try {
            RELAY_JSON.decodeFromString<MinecraftAuthenticationRelayWireRequest>(
                request.body.decodeToString(throwOnInvalidSequence = true),
            )
        } catch (_: IllegalArgumentException) {
            return relayError(HttpStatusCode.BadRequest, "invalid_request")
        } catch (_: SerializationException) {
            return relayError(HttpStatusCode.BadRequest, "invalid_request")
        }
        if (wireRequest.version != MINECRAFT_AUTHENTICATION_RELAY_VERSION) {
            return relayError(HttpStatusCode.UpgradeRequired, "unsupported_version")
        }
        val relayOperation = MinecraftAuthenticationRelayOperation.fromWireName(
            wireRequest.operation,
        ) ?: return relayError(HttpStatusCode.BadRequest, "unknown_operation")
        if (relayOperation !in policy.operations) {
            return relayError(HttpStatusCode.Forbidden, "operation_not_allowed")
        }

        val operation = try {
            decodeOperation(relayOperation, wireRequest.payload)
        } catch (_: IllegalArgumentException) {
            return relayError(HttpStatusCode.BadRequest, "invalid_operation_payload")
        }
        val clientId = operation.microsoftClientId
        if (clientId != null && clientId !in policy.microsoftClientIds) {
            return relayError(HttpStatusCode.Forbidden, "client_id_not_allowed")
        }

        val upstream = try {
            executeDirect(upstreamHttpClient, operation)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return relayError(HttpStatusCode.BadGateway, "upstream_unavailable")
        }
        val body = try {
            upstream.body.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            return relayError(HttpStatusCode.BadGateway, "invalid_upstream_response")
        }
        val wireResponse = MinecraftAuthenticationRelayWireResponse(
            version = MINECRAFT_AUTHENTICATION_RELAY_VERSION,
            operation = relayOperation.wireName,
            status = upstream.status.value,
            contentType = upstream.contentType?.toString(),
            retryAfter = upstream.retryAfter,
            body = body,
        )
        val encoded = RELAY_JSON.encodeToString(wireResponse).encodeToByteArray()
        if (encoded.size > MAXIMUM_RELAY_RESPONSE_BYTES) {
            return relayError(HttpStatusCode.BadGateway, "upstream_response_too_large")
        }
        return MinecraftAuthenticationRelayResponse(
            statusCode = HttpStatusCode.OK.value,
            contentType = MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE.toString(),
            cacheControl = CACHE_CONTROL_NO_STORE,
            retryAfter = upstream.retryAfter,
            body = encoded,
        )
    }
}

private fun decodeOperation(
    operation: MinecraftAuthenticationRelayOperation,
    payload: JsonObject,
): MinecraftAuthenticationOperation = when (operation) {
    MinecraftAuthenticationRelayOperation.MICROSOFT_DEVICE_AUTHORIZATION -> {
        payload.requireKeys("tenant", "clientId", "scopes")
        MicrosoftDeviceAuthorizationOperation(
            tenant = payload.requiredString("tenant").validateMicrosoftTenant(),
            microsoftClientId = payload.requiredString("clientId").validateMicrosoftClientId(),
            scopes = payload.requiredString("scopes").validateMicrosoftOAuthScopeText(),
        )
    }

    MinecraftAuthenticationRelayOperation.MICROSOFT_TOKEN -> {
        payload.requireKeys("tenant", "clientId", "grant")
        val grantPayload = payload["grant"]?.jsonObject
            ?: throw RelayRequestException()
        MicrosoftTokenOperation(
            tenant = payload.requiredString("tenant").validateMicrosoftTenant(),
            microsoftClientId = payload.requiredString("clientId").validateMicrosoftClientId(),
            grant = decodeTokenGrant(grantPayload),
        )
    }

    MinecraftAuthenticationRelayOperation.XBOX_USER_AUTHENTICATION -> {
        payload.requireKeys("microsoftAccessToken")
        XboxUserAuthenticationOperation(payload.requiredSecret("microsoftAccessToken"))
    }

    MinecraftAuthenticationRelayOperation.XBOX_XSTS_AUTHORIZATION -> {
        payload.requireKeys("xboxUserToken")
        XboxXstsAuthorizationOperation(payload.requiredSecret("xboxUserToken"))
    }

    MinecraftAuthenticationRelayOperation.MINECRAFT_XBOX_LOGIN -> {
        payload.requireKeys("userHash", "xstsToken")
        MinecraftXboxLoginOperation(
            userHash = payload.requiredString("userHash"),
            xstsToken = payload.requiredSecret("xstsToken"),
        )
    }

    MinecraftAuthenticationRelayOperation.MINECRAFT_ENTITLEMENTS -> {
        payload.requireKeys("minecraftAccessToken")
        MinecraftEntitlementsOperation(payload.requiredSecret("minecraftAccessToken"))
    }

    MinecraftAuthenticationRelayOperation.MINECRAFT_PROFILE -> {
        payload.requireKeys("minecraftAccessToken")
        MinecraftProfileOperation(payload.requiredSecret("minecraftAccessToken"))
    }

    MinecraftAuthenticationRelayOperation.SESSION_JOIN -> {
        payload.requireKeys("accessToken", "selectedProfile", "serverHash")
        MinecraftSessionJoinOperation(
            accessToken = payload.requiredSecret("accessToken"),
            selectedProfile = payload.requiredString("selectedProfile"),
            serverHash = payload.requiredString("serverHash"),
        )
    }

    MinecraftAuthenticationRelayOperation.SESSION_HAS_JOINED -> {
        payload.requireKeys("username", "serverHash", optional = setOf("ipAddress"))
        MinecraftSessionHasJoinedOperation(
            username = payload.requiredString("username"),
            serverHash = payload.requiredString("serverHash"),
            ipAddress = payload.optionalString("ipAddress"),
        )
    }
}

private fun decodeTokenGrant(payload: JsonObject): MicrosoftTokenGrant =
    when (payload.requiredString("grantType")) {
        "authorization_code" -> {
            payload.requireKeys("grantType", "code", "redirectUri", "codeVerifier")
            MicrosoftTokenGrant.AuthorizationCode(
                code = payload.requiredSecret("code"),
                redirectUri = payload.requiredString("redirectUri"),
                codeVerifier = payload.requiredSecret("codeVerifier"),
            )
        }

        "urn:ietf:params:oauth:grant-type:device_code" -> {
            payload.requireKeys("grantType", "deviceCode")
            MicrosoftTokenGrant.DeviceCode(payload.requiredSecret("deviceCode"))
        }

        "refresh_token" -> {
            payload.requireKeys("grantType", "refreshToken", "scopes")
            MicrosoftTokenGrant.RefreshToken(
                refreshToken = payload.requiredSecret("refreshToken"),
                scopes = payload.requiredString("scopes")
                    .validateMicrosoftOAuthScopeText(),
            )
        }

        else -> throw RelayRequestException()
    }

private fun JsonObject.requireKeys(
    vararg required: String,
    optional: Set<String> = emptySet(),
) {
    val expected = required.toSet() + optional
    if (keys != expected && (keys - optional) != required.toSet()) {
        throw RelayRequestException()
    }
    if (!keys.containsAll(required.toSet())) throw RelayRequestException()
}

private fun JsonObject.requiredString(name: String): String =
    try {
        getValue(name).requireString().also { value ->
            if (value.isBlank()) throw RelayRequestException()
        }
    } catch (_: IllegalArgumentException) {
        throw RelayRequestException()
    }

private fun JsonObject.requiredSecret(name: String): String =
    requiredString(name).also { value ->
        if (value.length > MAXIMUM_RELAY_SECRET_CHARACTERS) {
            throw RelayRequestException()
        }
    }

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.requireString()?.also { value ->
        if (value.isBlank()) throw RelayRequestException()
    }

private fun kotlinx.serialization.json.JsonElement.requireString(): String {
    val primitive = this as? JsonPrimitive ?: throw RelayRequestException()
    if (!primitive.isString) throw RelayRequestException()
    return primitive.content
}

private fun relayError(
    status: HttpStatusCode,
    error: String,
): MinecraftAuthenticationRelayResponse {
    val body = RELAY_JSON.encodeToString(
        buildJsonObject {
            put("version", MINECRAFT_AUTHENTICATION_RELAY_VERSION)
            put("error", error)
        },
    ).encodeToByteArray()
    return MinecraftAuthenticationRelayResponse(
        statusCode = status.value,
        contentType = ContentType.Application.Json.toString(),
        cacheControl = CACHE_CONTROL_NO_STORE,
        retryAfter = null,
        body = body,
    )
}

internal fun String.validateMicrosoftClientId(): String {
    require(isNotBlank()) { "Microsoft client ID cannot be blank" }
    require(length <= 128 && all { character -> character.isAsciiLetterOrDigit() || character == '-' }) {
        "Microsoft client ID contains invalid characters"
    }
    require(any(Char::isAsciiLetterOrDigit)) {
        "Microsoft client ID must contain an ASCII letter or digit"
    }
    return this
}

internal fun String.validateMicrosoftTenant(): String {
    require(isNotBlank()) { "Microsoft tenant cannot be blank" }
    require(length <= 256 && all { character ->
        character.isAsciiLetterOrDigit() || character == '-' || character == '.'
    }) {
        "Microsoft tenant contains invalid characters"
    }
    require(this != "." && this != ".." && any(Char::isAsciiLetterOrDigit)) {
        "Microsoft tenant must contain an ASCII letter or digit"
    }
    return this
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private class RelayRequestException : IllegalArgumentException()

private const val CACHE_CONTROL_NO_STORE = "no-store"
private const val MAXIMUM_RELAY_SECRET_CHARACTERS = 32_768
