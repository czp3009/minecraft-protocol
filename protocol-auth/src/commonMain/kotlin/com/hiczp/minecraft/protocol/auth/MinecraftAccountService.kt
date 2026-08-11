package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Microsoft → Xbox Live → XSTS → Minecraft Services account exchange. */
class MinecraftAccountService internal constructor(
    private val http: MinecraftAuthenticationHttpRoute,
    private val now: () -> Instant,
) {
    constructor(
        httpClient: HttpClient,
    ) : this(directAuthenticationRoute(httpClient), Clock.System::now)

    constructor(
        httpClient: HttpClient,
        relayEndpoint: Url,
    ) : this(
        relayAuthenticationRoute(httpClient, relayEndpoint),
        Clock.System::now,
    )

    internal constructor(
        httpClient: HttpClient,
        now: () -> Instant,
    ) : this(directAuthenticationRoute(httpClient), now)

    suspend fun loginWithMicrosoftTokens(
        tokens: MicrosoftOAuthTokens,
    ): MinecraftAccountLoginResult = loginWithMicrosoftAccessToken(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
    )

    suspend fun loginWithMicrosoftAccessToken(
        accessToken: MicrosoftAccessToken,
    ): MinecraftAccountLoginResult = loginWithMicrosoftAccessToken(
        accessToken = accessToken,
        refreshToken = null,
    )

    /** Combines an already restored Minecraft credential with caller-restored profile metadata without network I/O. */
    fun existingAccount(
        account: MinecraftOnlineAccount,
        profile: MinecraftAccountProfile,
        entitlements: MinecraftEntitlements = MinecraftEntitlements(emptyList()),
    ): MinecraftAccountLoginResult {
        require(account.id == profile.id && account.name == profile.name) {
            "Existing Minecraft account and profile do not identify the same player"
        }
        return MinecraftAccountLoginResult(
            account = account,
            refreshToken = null,
            entitlements = entitlements,
            profile = profile,
        )
    }

    private suspend fun loginWithMicrosoftAccessToken(
        accessToken: MicrosoftAccessToken,
        refreshToken: MicrosoftRefreshToken?,
    ): MinecraftAccountLoginResult {
        val xboxUser = authenticateXboxUser(accessToken)
        val xsts = authorizeXboxServices(xboxUser)
        if (!constantTimeEquals(xboxUser.userHash, xsts.userHash)) {
            throw XboxAuthenticationException(
                stage = MinecraftAuthenticationStage.XBOX_XSTS,
                xerr = null,
                message = "Xbox XSTS returned a different user hash",
            )
        }
        val minecraftToken = loginToMinecraftServices(xsts)
        val entitlements = getEntitlements(minecraftToken.accessToken)
        val profile = getProfile(minecraftToken.accessToken)
        val account = MinecraftOnlineAccount.fromExistingCredentials(
            name = profile.name,
            id = profile.id,
            accessToken = minecraftToken.accessToken,
            expiresAt = now() + minecraftToken.lifetime,
        )
        return MinecraftAccountLoginResult(
            account = account,
            refreshToken = refreshToken,
            entitlements = entitlements,
            profile = profile,
        )
    }

    private suspend fun authenticateXboxUser(
        microsoftAccessToken: MicrosoftAccessToken,
    ): XboxToken {
        val response = http.execute(
            XboxUserAuthenticationOperation(microsoftAccessToken.reveal()),
        )
        if (!response.status.isSuccess()) {
            throw xboxFailure(response, MinecraftAuthenticationStage.XBOX_USER)
        }
        return decodeXboxToken(response, MinecraftAuthenticationStage.XBOX_USER)
    }

    private suspend fun authorizeXboxServices(
        xboxUser: XboxToken,
    ): XboxToken {
        val response = http.execute(
            XboxXstsAuthorizationOperation(xboxUser.token),
        )
        if (!response.status.isSuccess()) {
            throw xboxFailure(response, MinecraftAuthenticationStage.XBOX_XSTS)
        }
        return decodeXboxToken(response, MinecraftAuthenticationStage.XBOX_XSTS)
    }

    private suspend fun loginToMinecraftServices(
        xsts: XboxToken,
    ): MinecraftServicesToken {
        val response = http.execute(
            MinecraftXboxLoginOperation(
                userHash = xsts.userHash,
                xstsToken = xsts.token,
            ),
        )
        if (!response.status.isSuccess()) {
            if (response.status.value == 403) {
                throw MinecraftApplicationRegistrationException(
                    "Minecraft Services rejected the application registration",
                )
            }
            throw serviceFailure(
                response,
                MinecraftAuthenticationStage.MINECRAFT_XBOX_LOGIN,
            )
        }
        val body = decodeServiceJson<MinecraftLoginResponse>(
            response,
            MinecraftAuthenticationStage.MINECRAFT_XBOX_LOGIN,
        )
        val lifetime = body.expiresIn.seconds
        require(lifetime.isFinite() && lifetime.isPositive()) {
            "Minecraft access token expiry must be finite and positive"
        }
        return MinecraftServicesToken(
            accessToken = body.accessToken.validateOpaqueToken("Minecraft access token"),
            lifetime = lifetime,
        )
    }

    private suspend fun getEntitlements(
        minecraftAccessToken: String,
    ): MinecraftEntitlements {
        val response = http.execute(
            MinecraftEntitlementsOperation(minecraftAccessToken),
        )
        if (!response.status.isSuccess()) {
            throw serviceFailure(
                response,
                MinecraftAuthenticationStage.MINECRAFT_ENTITLEMENTS,
            )
        }
        val body = decodeServiceJson<MinecraftEntitlementsResponse>(
            response,
            MinecraftAuthenticationStage.MINECRAFT_ENTITLEMENTS,
        )
        return MinecraftEntitlements(
            items = body.items.map { item ->
                MinecraftEntitlement(
                    name = item.name,
                    signature = item.signature,
                )
            },
            signature = body.signature,
            keyId = body.keyId,
        )
    }

    private suspend fun getProfile(
        minecraftAccessToken: String,
    ): MinecraftAccountProfile {
        val response = http.execute(
            MinecraftProfileOperation(minecraftAccessToken),
        )
        if (response.status.value == 404) {
            throw MinecraftJavaProfileNotFoundException(
                "The Microsoft account does not have a Minecraft Java profile",
            )
        }
        if (!response.status.isSuccess()) {
            throw serviceFailure(
                response,
                MinecraftAuthenticationStage.MINECRAFT_PROFILE,
            )
        }
        val body = decodeServiceJson<MinecraftProfileResponse>(
            response,
            MinecraftAuthenticationStage.MINECRAFT_PROFILE,
        )
        return MinecraftAccountProfile(
            id = parseMinecraftUuid(body.id),
            name = body.name.requireNotBlank("Minecraft profile name"),
            skins = body.skins.map { skin ->
                MinecraftProfileSkin(
                    id = skin.id,
                    state = skin.state,
                    url = Url(skin.url),
                    variant = skin.variant,
                    alias = skin.alias,
                )
            },
            capes = body.capes.map { cape ->
                MinecraftProfileCape(
                    id = cape.id,
                    state = cape.state,
                    url = Url(cape.url),
                    alias = cape.alias,
                )
            },
        )
    }
}

private fun decodeXboxToken(
    response: MinecraftAuthenticationHttpResponse,
    stage: MinecraftAuthenticationStage,
): XboxToken {
    val body = decodeServiceJson<XboxTokenResponse>(response, stage)
    val claims = body.displayClaims.xui
    if (claims.size != 1 || claims.single().userHash.isBlank()) {
        throw XboxAuthenticationException(
            stage = stage,
            xerr = null,
            message = "Xbox authentication returned invalid user claims",
        )
    }
    return XboxToken(
        token = body.token.validateOpaqueToken("Xbox token"),
        userHash = claims.single().userHash,
    )
}

private fun xboxFailure(
    response: MinecraftAuthenticationHttpResponse,
    stage: MinecraftAuthenticationStage,
): MinecraftAuthenticationException {
    val xerr = runCatching {
        ACCOUNT_JSON.decodeFromString<XboxErrorResponse>(
            response.body.decodeToString(throwOnInvalidSequence = true),
        ).xerr
    }.getOrNull()
    if (response.status.value == 429 || response.status.value >= 500) {
        return MinecraftAuthenticationUnavailableException(
            "Xbox authentication is temporarily unavailable at stage $stage",
        )
    }
    return XboxAuthenticationException(
        stage = stage,
        xerr = xerr,
        message = xboxErrorMessage(xerr, stage),
    )
}

private fun xboxErrorMessage(
    xerr: Long?,
    stage: MinecraftAuthenticationStage,
): String = when (xerr) {
    2_148_916_233L -> "The Microsoft account does not have an Xbox profile"
    2_148_916_235L -> "Xbox services are unavailable in the account region"
    2_148_916_236L,
    2_148_916_237L,
        -> "The Xbox account requires adult-family authorization"

    2_148_916_238L -> "The Xbox account is restricted by age policy"
    else -> "Xbox authentication was rejected at stage $stage"
}

private fun serviceFailure(
    response: MinecraftAuthenticationHttpResponse,
    stage: MinecraftAuthenticationStage,
): MinecraftAuthenticationException =
    if (response.status.value == 429 || response.status.value >= 500) {
        MinecraftAuthenticationUnavailableException(
            "Authentication service is temporarily unavailable at stage $stage",
        )
    } else {
        MinecraftAuthenticationRejectedException(
            stage = stage,
            statusCode = response.status.value,
            message = "Authentication service rejected the request at stage $stage",
        )
    }

private inline fun <reified T> decodeServiceJson(
    response: MinecraftAuthenticationHttpResponse,
    stage: MinecraftAuthenticationStage,
): T = try {
    ACCOUNT_JSON.decodeFromString(
        response.body.decodeToString(throwOnInvalidSequence = true),
    )
} catch (failure: IllegalArgumentException) {
    throw MinecraftAuthenticationException(
        "Authentication service returned invalid JSON at stage $stage",
        failure,
    )
} catch (failure: SerializationException) {
    throw MinecraftAuthenticationException(
        "Authentication service returned invalid JSON at stage $stage",
        failure,
    )
}

private fun constantTimeEquals(expected: String, actual: String): Boolean =
    expected.encodeToByteArray().toByteString().equals(
        actual.encodeToByteArray().toByteString(),
        constantTime = true,
    )

private fun String.requireNotBlank(name: String): String =
    also { require(isNotBlank()) { "$name cannot be blank" } }

private data class XboxToken(
    val token: String,
    val userHash: String,
)

private data class MinecraftServicesToken(
    val accessToken: String,
    val lifetime: Duration,
)

@Serializable
private data class XboxTokenResponse(
    @SerialName("Token")
    val token: String,
    @SerialName("DisplayClaims")
    val displayClaims: XboxDisplayClaims,
)

@Serializable
private data class XboxDisplayClaims(
    val xui: List<XboxUserClaim>,
)

@Serializable
private data class XboxUserClaim(
    @SerialName("uhs")
    val userHash: String,
)

@Serializable
private data class XboxErrorResponse(
    @SerialName("XErr")
    val xerr: Long? = null,
)

@Serializable
private data class MinecraftLoginResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
)

@Serializable
private data class MinecraftEntitlementsResponse(
    val items: List<MinecraftEntitlementResponse> = emptyList(),
    val signature: String? = null,
    val keyId: String? = null,
)

@Serializable
private data class MinecraftEntitlementResponse(
    val name: String,
    val signature: String,
)

@Serializable
private data class MinecraftProfileResponse(
    val id: String,
    val name: String,
    val skins: List<MinecraftSkinResponse> = emptyList(),
    val capes: List<MinecraftCapeResponse> = emptyList(),
)

@Serializable
private data class MinecraftSkinResponse(
    val id: String,
    val state: String,
    val url: String,
    val variant: String = "CLASSIC",
    val alias: String? = null,
)

@Serializable
private data class MinecraftCapeResponse(
    val id: String,
    val state: String,
    val url: String,
    val alias: String? = null,
)

enum class MinecraftAuthenticationStage {
    MICROSOFT_OAUTH,
    XBOX_USER,
    XBOX_XSTS,
    MINECRAFT_XBOX_LOGIN,
    MINECRAFT_ENTITLEMENTS,
    MINECRAFT_PROFILE,
    SESSION_JOIN,
    SESSION_HAS_JOINED,
}

class XboxAuthenticationException(
    val stage: MinecraftAuthenticationStage,
    val xerr: Long?,
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)

class MinecraftApplicationRegistrationException(
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)

class MinecraftJavaProfileNotFoundException(
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)

class MinecraftAuthenticationRejectedException(
    val stage: MinecraftAuthenticationStage,
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)

private val ACCOUNT_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
}
