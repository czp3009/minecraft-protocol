package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.jvm.JvmInline
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@JvmInline
value class MicrosoftOAuthScope(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Microsoft OAuth scope cannot be blank" }
        require(value.length <= 512) { "Microsoft OAuth scope is too long" }
        require(value.all { it.code in 0x21..0x7E }) {
            "A Microsoft OAuth scope must contain visible ASCII characters only"
        }
    }

    override fun toString(): String = value
}

/** Caller-owned Microsoft public-client registration configuration. */
class MicrosoftOAuthApplication(
    clientId: String,
    scopes: Collection<MicrosoftOAuthScope>,
    tenant: String = "consumers",
) {
    val clientId: String = clientId.validateMicrosoftClientId()
    val scopes: List<MicrosoftOAuthScope> = validateMicrosoftOAuthScopes(scopes)
    val tenant: String = tenant.validateMicrosoftTenant()

    internal val scopeText: String = this.scopes.joinToString(" ", transform = MicrosoftOAuthScope::value)
    internal val bindingScopes: List<String> = this.scopes.map(MicrosoftOAuthScope::value)

    override fun toString(): String =
        "MicrosoftOAuthApplication(clientId=$clientId, scopes=$scopes, tenant=$tenant)"
}

private fun MicrosoftOAuthScope.resourceAudience(): String? = when {
    value in NEUTRAL_IDENTITY_SCOPES -> null
    value.startsWith("xboxlive.", ignoreCase = true) -> "xboxlive"
    "://" in value -> runCatching { Url(value).host }.getOrDefault(value)
    else -> value.substringBefore('.')
}

private fun validateMicrosoftOAuthScopes(
    scopes: Collection<MicrosoftOAuthScope>,
): List<MicrosoftOAuthScope> = scopes.toList().also { values ->
    require(values.isNotEmpty()) {
        "At least one caller-approved Microsoft OAuth scope is required"
    }
    require(values.distinct().size == values.size) {
        "Microsoft OAuth scopes cannot contain duplicates"
    }
    val audiences = values.mapNotNull(MicrosoftOAuthScope::resourceAudience).toSet()
    require(audiences.size <= 1) {
        "Microsoft OAuth scopes for different resource audiences cannot be mixed"
    }
}

internal fun String.validateMicrosoftOAuthScopeText(): String {
    require(isNotEmpty() && trim() == this && "  " !in this) {
        "Microsoft OAuth scope text must use single spaces as separators"
    }
    validateMicrosoftOAuthScopes(split(' ').map(::MicrosoftOAuthScope))
    return this
}

/** Pending Authorization Code + PKCE transaction. The verifier and state never enter its rendering. */
class MicrosoftAuthorizationCodeLogin internal constructor(
    val authorizationUri: Url,
    val expiresAt: Instant,
    internal val applicationBinding: MicrosoftApplicationBinding,
    internal val redirectUri: Url,
    internal val state: String,
    internal val codeVerifier: String,
) {
    private var consumed = false

    internal fun consume() {
        check(!consumed) { "The Microsoft authorization transaction was already consumed" }
        consumed = true
    }

    override fun toString(): String =
        "MicrosoftAuthorizationCodeLogin(authorizationUri=<redacted>, expiresAt=$expiresAt)"
}

/** Display-only Device Code information plus opaque polling state. */
class MicrosoftDeviceCodeLogin internal constructor(
    val userCode: String,
    val verificationUri: Url,
    val verificationUriComplete: Url?,
    val message: String?,
    val expiresAt: Instant,
    val pollingInterval: Duration,
    internal val applicationBinding: MicrosoftApplicationBinding,
    internal val deviceCode: String,
) {
    private var consumed = false

    internal fun consume() {
        check(!consumed) { "The Microsoft device authorization was already consumed" }
        consumed = true
    }

    override fun toString(): String =
        "MicrosoftDeviceCodeLogin(userCode=$userCode, verificationUri=$verificationUri, expiresAt=$expiresAt, deviceCode=<redacted>)"
}

internal data class MicrosoftApplicationBinding(
    val clientId: String,
    val tenant: String,
    val scopes: List<String>,
    val channel: MicrosoftOAuthTransportChannel,
)

/** Microsoft Authorization Code + PKCE, Device Code, and refresh-token service. */
class MicrosoftOAuthService private constructor(
    private val http: MinecraftAuthenticationHttpRoute,
    val application: MicrosoftOAuthApplication,
    private val runtime: MicrosoftOAuthRuntime,
) {
    constructor(
        httpClient: HttpClient,
        application: MicrosoftOAuthApplication,
    ) : this(
        directAuthenticationRoute(httpClient),
        application,
        MicrosoftOAuthRuntime.System,
    )

    constructor(
        httpClient: HttpClient,
        application: MicrosoftOAuthApplication,
        relayEndpoint: Url,
    ) : this(
        relayAuthenticationRoute(httpClient, relayEndpoint),
        application,
        MicrosoftOAuthRuntime.System,
    )

    internal constructor(
        httpClient: HttpClient,
        application: MicrosoftOAuthApplication,
        now: () -> Instant,
        pause: suspend (Duration) -> Unit,
    ) : this(
        directAuthenticationRoute(httpClient),
        application,
        MicrosoftOAuthRuntime(now, pause),
    )

    fun beginAuthorizationCodeLogin(
        redirectUri: Url,
    ): MicrosoftAuthorizationCodeLogin {
        validateRedirectUri(redirectUri)
        val state = secureRandomBytes(OAUTH_STATE_BYTES).base64Url()
        val codeVerifier = secureRandomBytes(PKCE_VERIFIER_BYTES).base64Url()
        val codeChallenge = codeVerifier.encodeToByteArray()
            .toByteString()
            .sha256()
            .toByteArray()
            .base64Url()
        val expiresAt = runtime.now() + AUTHORIZATION_TRANSACTION_LIFETIME
        val authorizationUri = URLBuilder(microsoftAuthorizationEndpoint(application.tenant)).apply {
            parameters.append("client_id", application.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri.toString())
            parameters.append("response_mode", "query")
            parameters.append("scope", application.scopeText)
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.build()
        return MicrosoftAuthorizationCodeLogin(
            authorizationUri = authorizationUri,
            expiresAt = expiresAt,
            applicationBinding = binding(),
            redirectUri = redirectUri,
            state = state,
            codeVerifier = codeVerifier,
        )
    }

    suspend fun completeAuthorizationCodeLogin(
        authorization: MicrosoftAuthorizationCodeLogin,
        redirectedUri: Url,
    ): MicrosoftOAuthTokens {
        requireBinding(authorization.applicationBinding)
        if (runtime.now() >= authorization.expiresAt) {
            throw MicrosoftOAuthException(
                errorCode = "authorization_transaction_expired",
                message = "The Microsoft authorization transaction expired",
            )
        }
        requireSameRedirect(authorization.redirectUri, redirectedUri)
        val returnedState = redirectedUri.parameters.getAll("state")?.singleOrNull()
            ?: throw MicrosoftOAuthException(
                errorCode = "invalid_state",
                message = "Microsoft authorization redirect did not contain exactly one state value",
            )
        if (!constantTimeEquals(authorization.state, returnedState)) {
            throw MicrosoftOAuthException(
                errorCode = "invalid_state",
                message = "Microsoft authorization state did not match",
            )
        }
        redirectedUri.parameters.getAll("error")?.singleOrNull()?.let { error ->
            authorization.consume()
            throw MicrosoftOAuthException(
                errorCode = error,
                message = "Microsoft authorization was rejected",
            )
        }
        val code = redirectedUri.parameters.getAll("code")?.singleOrNull()
            ?.takeIf(String::isNotBlank)
            ?: throw MicrosoftOAuthException(
                errorCode = "invalid_authorization_response",
                message = "Microsoft authorization redirect did not contain exactly one code",
            )
        authorization.consume()
        val response = http.execute(
            MicrosoftTokenOperation(
                tenant = application.tenant,
                microsoftClientId = application.clientId,
                grant = MicrosoftTokenGrant.AuthorizationCode(
                    code = code,
                    redirectUri = authorization.redirectUri.toString(),
                    codeVerifier = authorization.codeVerifier,
                ),
            ),
        )
        return parseTokenResult(
            response = response,
            flow = MicrosoftOAuthFlow.AUTHORIZATION_CODE,
        ).tokensOrThrow()
    }

    suspend fun beginDeviceCodeLogin(): MicrosoftDeviceCodeLogin {
        val response = http.execute(
            MicrosoftDeviceAuthorizationOperation(
                tenant = application.tenant,
                microsoftClientId = application.clientId,
                scopes = application.scopeText,
            ),
        )
        if (!response.status.isSuccess()) throw oauthHttpFailure(response, "device_authorization")
        val body = decodeOAuthJson<MicrosoftDeviceAuthorizationResponse>(response)
        val lifetime = body.expiresIn.seconds
        val pollingInterval = body.interval.seconds
        require(lifetime.isFinite() && lifetime.isPositive()) {
            "Microsoft device authorization expiry must be finite and positive"
        }
        require(pollingInterval.isFinite() && pollingInterval.isPositive()) {
            "Microsoft device authorization polling interval must be finite and positive"
        }
        return MicrosoftDeviceCodeLogin(
            userCode = body.userCode.requireNotBlank("Microsoft user code"),
            verificationUri = Url(body.verificationUri),
            verificationUriComplete = body.verificationUriComplete?.let(::Url),
            message = body.message,
            expiresAt = runtime.now() + lifetime,
            pollingInterval = pollingInterval,
            applicationBinding = binding(),
            deviceCode = body.deviceCode.validateOpaqueToken("Microsoft device code"),
        )
    }

    suspend fun awaitDeviceCodeLogin(
        authorization: MicrosoftDeviceCodeLogin,
    ): MicrosoftOAuthTokens {
        requireBinding(authorization.applicationBinding)
        authorization.consume()
        var interval = authorization.pollingInterval
        while (true) {
            if (runtime.now() >= authorization.expiresAt) {
                throw MicrosoftOAuthException(
                    errorCode = "expired_token",
                    message = "The Microsoft device authorization expired",
                )
            }
            runtime.pause(interval)
            if (runtime.now() >= authorization.expiresAt) {
                throw MicrosoftOAuthException(
                    errorCode = "expired_token",
                    message = "The Microsoft device authorization expired",
                )
            }
            val response = http.execute(
                MicrosoftTokenOperation(
                    tenant = application.tenant,
                    microsoftClientId = application.clientId,
                    grant = MicrosoftTokenGrant.DeviceCode(authorization.deviceCode),
                ),
            )
            when (
                val result = parseTokenResult(
                    response = response,
                    flow = MicrosoftOAuthFlow.DEVICE_CODE,
                )
            ) {
                is MicrosoftTokenResult.Success -> return result.tokens
                MicrosoftTokenResult.AuthorizationPending -> Unit
                MicrosoftTokenResult.SlowDown -> interval += DEVICE_SLOW_DOWN_INCREMENT
            }
        }
    }

    suspend fun refresh(
        refreshToken: MicrosoftRefreshToken,
    ): MicrosoftOAuthTokens {
        requireRefreshBinding(refreshToken.binding)
        val response = http.execute(
            MicrosoftTokenOperation(
                tenant = application.tenant,
                microsoftClientId = application.clientId,
                grant = MicrosoftTokenGrant.RefreshToken(
                    refreshToken = refreshToken.reveal(),
                    scopes = application.scopeText,
                ),
            ),
        )
        return parseTokenResult(
            response = response,
            flow = refreshToken.binding.flow,
            fallbackRefreshToken = refreshToken,
        ).tokensOrThrow()
    }

    /** Restores a refresh credential and verifies its application, scopes, flow, and direct/relay binding. */
    fun importRefreshToken(
        credentials: MicrosoftRefreshTokenCredentials,
    ): MicrosoftRefreshToken {
        val channel = runCatching {
            MicrosoftOAuthTransportChannel.valueOf(credentials.channel)
        }.getOrElse {
            throw IllegalArgumentException("Unknown Microsoft OAuth transport channel")
        }
        val binding = MicrosoftRefreshTokenBinding(
            clientId = credentials.clientId.validateMicrosoftClientId(),
            tenant = credentials.tenant.validateMicrosoftTenant(),
            scopes = credentials.scopes,
            flow = credentials.flow,
            channel = channel,
        )
        requireRefreshBinding(binding)
        return MicrosoftRefreshToken(credentials.token, binding)
    }

    private fun parseTokenResult(
        response: MinecraftAuthenticationHttpResponse,
        flow: MicrosoftOAuthFlow,
        fallbackRefreshToken: MicrosoftRefreshToken? = null,
    ): MicrosoftTokenResult {
        if (!response.status.isSuccess()) {
            val error = decodeOAuthError(response)
            return when (error.error) {
                "authorization_pending" -> MicrosoftTokenResult.AuthorizationPending
                "slow_down" -> MicrosoftTokenResult.SlowDown
                else -> throw oauthError(error.error, response.status.value)
            }
        }
        val body = decodeOAuthJson<MicrosoftTokenResponse>(response)
        if (!body.tokenType.equals("Bearer", ignoreCase = true)) {
            throw MicrosoftOAuthException(
                errorCode = "invalid_token_response",
                message = "Microsoft returned an unsupported token type",
            )
        }
        val lifetime = body.expiresIn.seconds
        require(lifetime.isFinite() && lifetime.isPositive()) {
            "Microsoft access token expiry must be finite and positive"
        }
        val binding = MicrosoftRefreshTokenBinding(
            clientId = application.clientId,
            tenant = application.tenant,
            scopes = application.bindingScopes,
            flow = flow,
            channel = http.channel,
        )
        val refresh = body.refreshToken?.let { MicrosoftRefreshToken(it, binding) }
            ?: fallbackRefreshToken
        val tokenScopes = body.scope
            ?.split(' ')
            ?.filter(String::isNotBlank)
            ?.ifEmpty { null }
            ?: application.bindingScopes
        return MicrosoftTokenResult.Success(
            MicrosoftOAuthTokens(
                accessToken = MicrosoftAccessToken.issued(body.accessToken),
                expiresAt = runtime.now() + lifetime,
                refreshToken = refresh,
                scopes = tokenScopes,
            ),
        )
    }

    private fun binding(): MicrosoftApplicationBinding =
        MicrosoftApplicationBinding(
            clientId = application.clientId,
            tenant = application.tenant,
            scopes = application.bindingScopes,
            channel = http.channel,
        )

    private fun requireBinding(binding: MicrosoftApplicationBinding) {
        require(
            binding == binding()
        ) {
            "The Microsoft authorization belongs to a different application or HTTP channel"
        }
    }

    private fun requireRefreshBinding(binding: MicrosoftRefreshTokenBinding) {
        require(binding.clientId == application.clientId) {
            "The Microsoft refresh token belongs to a different client ID"
        }
        require(binding.tenant == application.tenant) {
            "The Microsoft refresh token belongs to a different tenant"
        }
        require(binding.scopes == application.bindingScopes) {
            "The Microsoft refresh token belongs to different scopes"
        }
        require(binding.channel == http.channel) {
            "The Microsoft refresh token belongs to a different direct/relay channel"
        }
    }
}

private class MicrosoftOAuthRuntime(
    val now: () -> Instant,
    val pause: suspend (Duration) -> Unit,
) {
    companion object {
        val System: MicrosoftOAuthRuntime = MicrosoftOAuthRuntime(
            now = Clock.System::now,
            pause = { duration -> delay(duration) },
        )
    }
}

private sealed interface MicrosoftTokenResult {
    data class Success(val tokens: MicrosoftOAuthTokens) : MicrosoftTokenResult
    data object AuthorizationPending : MicrosoftTokenResult
    data object SlowDown : MicrosoftTokenResult
}

private fun MicrosoftTokenResult.tokensOrThrow(): MicrosoftOAuthTokens =
    when (this) {
        is MicrosoftTokenResult.Success -> tokens
        MicrosoftTokenResult.AuthorizationPending,
        MicrosoftTokenResult.SlowDown,
            -> throw MicrosoftOAuthException(
            errorCode = "invalid_token_response",
            message = "Microsoft returned a polling response outside Device Code login",
        )
    }

private inline fun <reified T> decodeOAuthJson(
    response: MinecraftAuthenticationHttpResponse,
): T = try {
    OAUTH_JSON.decodeFromString(
        response.body.decodeToString(throwOnInvalidSequence = true),
    )
} catch (failure: IllegalArgumentException) {
    throw MicrosoftOAuthException(
        errorCode = "invalid_response",
        message = "Microsoft returned an invalid OAuth response",
        cause = failure,
    )
} catch (failure: SerializationException) {
    throw MicrosoftOAuthException(
        errorCode = "invalid_response",
        message = "Microsoft returned an invalid OAuth response",
        cause = failure,
    )
}

private fun decodeOAuthError(
    response: MinecraftAuthenticationHttpResponse,
): MicrosoftOAuthErrorResponse = try {
    OAUTH_JSON.decodeFromString(
        response.body.decodeToString(throwOnInvalidSequence = true),
    )
} catch (_: Throwable) {
    MicrosoftOAuthErrorResponse(error = "http_${response.status.value}")
}

private fun oauthHttpFailure(
    response: MinecraftAuthenticationHttpResponse,
    operation: String,
): MicrosoftOAuthException {
    val error = decodeOAuthError(response)
    return oauthError(error.error, response.status.value, operation)
}

private fun oauthError(
    errorCode: String,
    statusCode: Int,
    operation: String = "token",
): MicrosoftOAuthException =
    if (statusCode == 429 || statusCode >= 500) {
        MicrosoftOAuthUnavailableException(
            errorCode = errorCode,
            message = "Microsoft OAuth $operation is temporarily unavailable",
        )
    } else {
        MicrosoftOAuthException(
            errorCode = errorCode,
            message = "Microsoft OAuth $operation was rejected",
        )
    }

private fun validateRedirectUri(uri: Url) {
    require(uri.protocol.name.isNotBlank()) { "Microsoft redirect URI requires a scheme" }
    require(uri.fragment.isEmpty()) { "Microsoft redirect URI cannot contain a fragment" }
    require(uri.user.isNullOrEmpty() && uri.password.isNullOrEmpty()) {
        "Microsoft redirect URI cannot contain user information"
    }
}

private fun requireSameRedirect(expected: Url, actual: Url) {
    require(
        expected.protocol == actual.protocol &&
                expected.host == actual.host &&
                expected.port == actual.port &&
                expected.encodedPath == actual.encodedPath &&
                expected.parameters.entries().all { (name, values) ->
                    actual.parameters.getAll(name) == values
                }
    ) {
        "Microsoft authorization redirect URI does not match the pending transaction"
    }
}

private fun constantTimeEquals(expected: String, actual: String): Boolean =
    expected.encodeToByteArray().toByteString().equals(
        actual.encodeToByteArray().toByteString(),
        constantTime = true,
    )

private fun ByteArray.base64Url(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(this)

private fun microsoftAuthorizationEndpoint(tenant: String): Url =
    URLBuilder("https://login.microsoftonline.com").apply {
        pathSegments = listOf(tenant, "oauth2", "v2.0", "authorize")
    }.build()

private fun String.requireNotBlank(name: String): String =
    also { require(isNotBlank()) { "$name cannot be blank" } }

@Serializable
private data class MicrosoftDeviceAuthorizationResponse(
    @SerialName("device_code")
    val deviceCode: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("verification_uri")
    val verificationUri: String,
    @SerialName("verification_uri_complete")
    val verificationUriComplete: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long,
    val interval: Long = 5,
    val message: String? = null,
)

@Serializable
private data class MicrosoftTokenResponse(
    @SerialName("token_type")
    val tokenType: String,
    val scope: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
)

@Serializable
private data class MicrosoftOAuthErrorResponse(
    val error: String,
)

open class MicrosoftOAuthException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause) {
    override fun toString(): String =
        "MicrosoftOAuthException(errorCode=$errorCode, message=${message ?: ""})"
}

class MicrosoftOAuthUnavailableException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : MicrosoftOAuthException(errorCode, message, cause)

private val OAUTH_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
}

private val NEUTRAL_IDENTITY_SCOPES = setOf("openid", "profile", "email", "offline_access")
private val AUTHORIZATION_TRANSACTION_LIFETIME = 10.minutes
private val DEVICE_SLOW_DOWN_INCREMENT = 5.seconds
private const val OAUTH_STATE_BYTES = 32
private const val PKCE_VERIFIER_BYTES = 64
