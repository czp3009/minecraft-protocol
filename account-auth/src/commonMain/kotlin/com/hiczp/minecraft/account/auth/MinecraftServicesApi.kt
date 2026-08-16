package com.hiczp.minecraft.account.auth

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class MinecraftServicesApi(
    private val httpClient: HttpClient,
) {
    suspend fun loginWithXbox(
        request: MinecraftXboxLoginRequest,
    ): MinecraftLoginResponse {
        val response = httpClient.post(MINECRAFT_XBOX_LOGIN_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(jsonContent(request))
        }
        return response.decodeAccountAuthResponse<MinecraftLoginResponse, MinecraftServicesErrorResponse>(::MinecraftServicesResponseException)
    }

    suspend fun getStoreEntitlements(
        accessToken: String,
    ): MinecraftEntitlementsResponse {
        val response = httpClient.get(MINECRAFT_STORE_ENTITLEMENTS_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
        }
        return response.decodeAccountAuthResponse<MinecraftEntitlementsResponse, MinecraftServicesErrorResponse>(::MinecraftServicesResponseException)
    }

    suspend fun getMinecraftProfile(
        accessToken: String,
    ): MinecraftProfileResponse {
        val response = httpClient.get(MINECRAFT_PROFILE_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
        }
        return response.decodeAccountAuthResponse<MinecraftProfileResponse, MinecraftServicesErrorResponse>(::MinecraftServicesResponseException)
    }

    suspend fun getLicenseEntitlements(
        accessToken: String,
    ): MinecraftEntitlementsResponse {
        val response = httpClient.get(MINECRAFT_LICENSE_ENTITLEMENTS_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
        }
        return response.decodeAccountAuthResponse<MinecraftEntitlementsResponse, MinecraftServicesErrorResponse>(::MinecraftServicesResponseException)
    }
}

@Serializable
data class MinecraftXboxLoginRequest(
    val identityToken: String,
)

@Serializable
data class MinecraftLoginResponse(
    val username: String,
    val roles: List<String>,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_in")
    val expiresIn: Long,
)

@Serializable
data class MinecraftEntitlementsResponse(
    val items: List<Item>,
    val signature: String? = null,
    val keyId: String? = null,
) {
    @Serializable
    data class Item(
        val name: String,
        val signature: String? = null,
    )
}

@Serializable
data class MinecraftProfileResponse(
    val id: String,
    val name: String,
    val skins: List<Skin>,
    val capes: List<Cape>,
) {
    @Serializable
    data class Skin(
        val id: String,
        val state: String,
        val url: String,
        val variant: String,
        val alias: String? = null,
    )

    @Serializable
    data class Cape(
        val id: String,
        val state: String,
        val url: String,
        val alias: String,
    )
}

@Serializable
data class MinecraftServicesErrorResponse(
    val path: String? = null,
    val error: String? = null,
    val errorMessage: String? = null,
    val developerMessage: String? = null,
)

object MinecraftServicesTools {
    const val JAVA_EDITION_ENTITLEMENT = "game_minecraft"

    fun xboxLoginRequest(
        xstsToken: XboxTokenResponse,
    ): MinecraftXboxLoginRequest = MinecraftXboxLoginRequest(
        identityToken = "XBL3.0 x=${xstsToken.displayClaims.xui[0].userHash};${xstsToken.token}",
    )

    fun hasJavaEditionEntitlement(
        entitlements: MinecraftEntitlementsResponse,
    ): Boolean = entitlements.items.any { it.name == JAVA_EDITION_ENTITLEMENT }
}

private val MINECRAFT_XBOX_LOGIN_ENDPOINT = Url("https://api.minecraftservices.com/authentication/login_with_xbox")
private val MINECRAFT_STORE_ENTITLEMENTS_ENDPOINT = Url("https://api.minecraftservices.com/entitlements/mcstore")
private val MINECRAFT_PROFILE_ENDPOINT = Url("https://api.minecraftservices.com/minecraft/profile")
private val MINECRAFT_LICENSE_ENTITLEMENTS_ENDPOINT = Url("https://api.minecraftservices.com/entitlements/license")
