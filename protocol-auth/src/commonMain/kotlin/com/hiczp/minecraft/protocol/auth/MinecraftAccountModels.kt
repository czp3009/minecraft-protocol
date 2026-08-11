package com.hiczp.minecraft.protocol.auth

import io.ktor.http.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A Java Edition account and its redacted Minecraft Services credential. */
class MinecraftOnlineAccount internal constructor(
    val name: String,
    val id: Uuid,
    accessToken: String,
    val expiresAt: Instant?,
) {
    private val minecraftAccessToken = accessToken.validateOpaqueToken("Minecraft access token")

    internal fun accessToken(): String = minecraftAccessToken

    /** Explicitly exports the credential for caller-owned secure storage. */
    fun exportForSecureStorage(): MinecraftOnlineAccountCredentials =
        MinecraftOnlineAccountCredentials(
            name = name,
            id = id,
            accessToken = minecraftAccessToken,
            expiresAt = expiresAt,
        )

    override fun toString(): String =
        "MinecraftOnlineAccount(name=$name, id=$id, accessToken=<redacted>, expiresAt=$expiresAt)"

    companion object {
        /** Creates an account from a launcher or broker that already completed Minecraft Services login. */
        fun fromExistingCredentials(
            name: String,
            id: Uuid,
            accessToken: String,
            expiresAt: Instant? = null,
        ): MinecraftOnlineAccount {
            require(name.isNotBlank()) { "Minecraft profile name cannot be blank" }
            return MinecraftOnlineAccount(name, id, accessToken, expiresAt)
        }

        /** Restores an envelope previously produced by [exportForSecureStorage]. */
        fun fromSecureStorage(
            credentials: MinecraftOnlineAccountCredentials,
        ): MinecraftOnlineAccount = fromExistingCredentials(
            name = credentials.name,
            id = credentials.id,
            accessToken = credentials.accessToken,
            expiresAt = credentials.expiresAt,
        )
    }
}

/** Explicit secret-bearing envelope intended only for application secure storage. */
class MinecraftOnlineAccountCredentials(
    val name: String,
    val id: Uuid,
    val accessToken: String,
    val expiresAt: Instant?,
) {
    override fun toString(): String =
        "MinecraftOnlineAccountCredentials(name=$name, id=$id, accessToken=<redacted>, expiresAt=$expiresAt)"
}

/** Opaque Microsoft access token obtained from this library or an application broker such as MSAL. */
class MicrosoftAccessToken private constructor(
    token: String,
) {
    private val value = token.validateOpaqueToken("Microsoft access token")

    internal fun reveal(): String = value

    override fun toString(): String = "MicrosoftAccessToken(<redacted>)"

    companion object {
        fun fromExternalProvider(token: String): MicrosoftAccessToken =
            MicrosoftAccessToken(token)

        internal fun issued(token: String): MicrosoftAccessToken =
            MicrosoftAccessToken(token)
    }
}

enum class MicrosoftOAuthFlow {
    AUTHORIZATION_CODE,
    DEVICE_CODE,
}

/** Opaque refresh token bound to the application and direct/relay channel that acquired it. */
class MicrosoftRefreshToken internal constructor(
    token: String,
    internal val binding: MicrosoftRefreshTokenBinding,
) {
    private val value = token.validateOpaqueToken("Microsoft refresh token")

    internal fun reveal(): String = value

    fun exportForSecureStorage(): MicrosoftRefreshTokenCredentials =
        MicrosoftRefreshTokenCredentials(
            token = value,
            clientId = binding.clientId,
            tenant = binding.tenant,
            scopes = binding.scopes,
            flow = binding.flow,
            channel = binding.channel.name,
        )

    override fun toString(): String =
        "MicrosoftRefreshToken(application=<bound>, flow=${binding.flow}, channel=${binding.channel}, token=<redacted>)"
}

/** Explicit secret-bearing envelope intended only for application secure storage. */
class MicrosoftRefreshTokenCredentials(
    val token: String,
    val clientId: String,
    val tenant: String,
    scopes: List<String>,
    val flow: MicrosoftOAuthFlow,
    val channel: String,
) {
    val scopes: List<String> = scopes.toList()

    override fun toString(): String =
        "MicrosoftRefreshTokenCredentials(clientId=$clientId, tenant=$tenant, scopes=$scopes, flow=$flow, channel=$channel, token=<redacted>)"
}

internal data class MicrosoftRefreshTokenBinding(
    val clientId: String,
    val tenant: String,
    val scopes: List<String>,
    val flow: MicrosoftOAuthFlow,
    val channel: MicrosoftOAuthTransportChannel,
)

class MicrosoftOAuthTokens internal constructor(
    val accessToken: MicrosoftAccessToken,
    val expiresAt: Instant,
    val refreshToken: MicrosoftRefreshToken?,
    scopes: List<String>,
) {
    val scopes: List<String> = scopes.toList()

    override fun toString(): String =
        "MicrosoftOAuthTokens(accessToken=<redacted>, expiresAt=$expiresAt, refreshToken=<redacted>, scopes=$scopes)"
}

data class MinecraftEntitlement(
    val name: String,
    val signature: String,
)

data class MinecraftEntitlements(
    val items: List<MinecraftEntitlement>,
    val signature: String? = null,
    val keyId: String? = null,
)

data class MinecraftProfileSkin(
    val id: String,
    val state: String,
    val url: Url,
    val variant: String,
    val alias: String? = null,
)

data class MinecraftProfileCape(
    val id: String,
    val state: String,
    val url: Url,
    val alias: String? = null,
)

data class MinecraftAccountProfile(
    val id: Uuid,
    val name: String,
    val skins: List<MinecraftProfileSkin>,
    val capes: List<MinecraftProfileCape>,
)

class MinecraftAccountLoginResult(
    val account: MinecraftOnlineAccount,
    val refreshToken: MicrosoftRefreshToken?,
    val entitlements: MinecraftEntitlements,
    val profile: MinecraftAccountProfile,
) {
    override fun toString(): String =
        "MinecraftAccountLoginResult(account=$account, refreshToken=<redacted>, entitlements=$entitlements, profile=$profile)"
}

internal fun String.validateOpaqueToken(name: String): String {
    require(isNotBlank()) { "$name cannot be blank" }
    require(length <= MAXIMUM_OPAQUE_TOKEN_CHARACTERS) {
        "$name exceeds its supported length"
    }
    return this
}

private const val MAXIMUM_OPAQUE_TOKEN_CHARACTERS = 32_768
