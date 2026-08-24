package com.hiczp.minecraft.account.auth

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class XboxAuthenticationApi(
    private val httpClient: HttpClient,
) {
    suspend fun authenticateUser(
        request: XboxUserAuthenticationRequest,
    ): XboxTokenResponse {
        val response = httpClient.post(XBOX_USER_AUTHENTICATE_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            header(XBOX_CONTRACT_VERSION_HEADER, XBOX_CONTRACT_VERSION)
            setBody(AccountAuthJson.encodeToString(request))
        }
        return response.decodeAccountAuthResponse<XboxTokenResponse, XboxAuthenticationErrorResponse>(::XboxAuthenticationResponseException)
    }

    suspend fun authorizeXsts(
        request: XboxXstsAuthorizationRequest,
    ): XboxTokenResponse {
        val response = httpClient.post(XBOX_XSTS_AUTHORIZE_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            header(XBOX_CONTRACT_VERSION_HEADER, XBOX_CONTRACT_VERSION)
            setBody(AccountAuthJson.encodeToString(request))
        }
        return response.decodeAccountAuthResponse<XboxTokenResponse, XboxAuthenticationErrorResponse>(::XboxAuthenticationResponseException)
    }
}

@Serializable
data class XboxUserAuthenticationRequest(
    @SerialName("Properties")
    val properties: Properties,
    @SerialName("RelyingParty")
    val relyingParty: String,
    @SerialName("TokenType")
    val tokenType: String,
) {
    @Serializable
    data class Properties(
        @SerialName("AuthMethod")
        val authMethod: String,
        @SerialName("SiteName")
        val siteName: String,
        @SerialName("RpsTicket")
        val rpsTicket: String,
    )
}

@Serializable
data class XboxXstsAuthorizationRequest(
    @SerialName("Properties")
    val properties: Properties,
    @SerialName("RelyingParty")
    val relyingParty: String,
    @SerialName("TokenType")
    val tokenType: String,
) {
    @Serializable
    data class Properties(
        @SerialName("SandboxId")
        val sandboxId: String,
        @SerialName("UserTokens")
        val userTokens: List<String>,
    )
}

@Serializable
data class XboxTokenResponse(
    @SerialName("IssueInstant")
    val issueInstant: String,
    @SerialName("NotAfter")
    val notAfter: String,
    @SerialName("Token")
    val token: String,
    @SerialName("DisplayClaims")
    val displayClaims: DisplayClaims,
) {
    @Serializable
    data class DisplayClaims(
        val xui: List<UserClaim>,
    ) {
        @Serializable
        data class UserClaim(
            @SerialName("uhs")
            val userHash: String,
        )
    }
}

@Serializable
data class XboxAuthenticationErrorResponse(
    @SerialName("Identity")
    val identity: String,
    @SerialName("XErr")
    val xErr: Long,
    @SerialName("Message")
    val message: String,
    @SerialName("Redirect")
    val redirect: String? = null,
)

object XboxAuthenticationTools {
    fun userAuthenticationRequest(
        microsoftToken: MicrosoftTokenResponse,
    ): XboxUserAuthenticationRequest = XboxUserAuthenticationRequest(
        properties = XboxUserAuthenticationRequest.Properties(
            authMethod = "RPS",
            siteName = "user.auth.xboxlive.com",
            rpsTicket = "d=${microsoftToken.accessToken}",
        ),
        relyingParty = "http://auth.xboxlive.com",
        tokenType = "JWT",
    )

    fun xstsAuthorizationRequest(
        xboxUserToken: XboxTokenResponse,
    ): XboxXstsAuthorizationRequest = XboxXstsAuthorizationRequest(
        properties = XboxXstsAuthorizationRequest.Properties(
            sandboxId = "RETAIL",
            userTokens = listOf(xboxUserToken.token),
        ),
        relyingParty = "rp://api.minecraftservices.com/",
        tokenType = "JWT",
    )
}

private val XBOX_USER_AUTHENTICATE_ENDPOINT = Url("https://user.auth.xboxlive.com/user/authenticate")
private val XBOX_XSTS_AUTHORIZE_ENDPOINT = Url("https://xsts.auth.xboxlive.com/xsts/authorize")
private const val XBOX_CONTRACT_VERSION_HEADER = "x-xbl-contract-version"
private const val XBOX_CONTRACT_VERSION = "1"
