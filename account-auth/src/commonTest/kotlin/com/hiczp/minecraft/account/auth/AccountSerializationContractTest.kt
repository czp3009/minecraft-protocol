package com.hiczp.minecraft.account.auth

import kotlinx.serialization.json.*
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.encodeToStringMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountSerializationContractTest {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun publicFormRequestModelsExposeKotlinxSerializersAndWireFieldNames() {
        val authorizationCode = Properties.encodeToStringMap(
            MicrosoftAuthorizationCodeTokenRequest(
                grantType = "authorization_code",
                code = "code",
                clientId = "client-id",
                redirectUri = "https://example.com/callback",
                codeVerifier = "code-verifier",
                scope = "scope",
            ),
        )
        assertEquals("authorization_code", authorizationCode["grant_type"])
        assertEquals("client-id", authorizationCode["client_id"])
        assertEquals("https://example.com/callback", authorizationCode["redirect_uri"])
        assertEquals("code-verifier", authorizationCode["code_verifier"])

        val authorizationCodeWithoutOptionalFields = Properties.encodeToStringMap(
            MicrosoftAuthorizationCodeTokenRequest(
                grantType = "authorization_code",
                code = "code",
                clientId = "client-id",
                redirectUri = "https://example.com/callback",
            ),
        )
        assertNull(authorizationCodeWithoutOptionalFields["code_verifier"])
        assertNull(authorizationCodeWithoutOptionalFields["scope"])

        val deviceCode = Properties.encodeToStringMap(
            MicrosoftDeviceCodeTokenRequest(
                grantType = "device-code-grant",
                clientId = "client-id",
                deviceCode = "device-code",
            ),
        )
        assertEquals("device-code-grant", deviceCode["grant_type"])
        assertEquals("device-code", deviceCode["device_code"])

        val refresh = Properties.encodeToStringMap(
            MicrosoftRefreshTokenRequest(
                clientId = "client-id",
                grantType = "refresh_token",
                refreshToken = "refresh-token",
                scope = "scope",
            ),
        )
        assertEquals("refresh-token", refresh["refresh_token"])
        assertEquals("scope", refresh["scope"])

        val refreshWithoutScope = Properties.encodeToStringMap(
            MicrosoftRefreshTokenRequest(
                clientId = "client-id",
                grantType = "refresh_token",
                refreshToken = "refresh-token",
            ),
        )
        assertNull(refreshWithoutScope["scope"])
    }

    @Test
    fun publicJsonModelsExposeKotlinxSerializersAndWireFieldNames() {
        val xboxRequest = XboxUserAuthenticationRequest(
            properties = XboxUserAuthenticationRequest.Properties(
                authMethod = "RPS",
                siteName = "user.auth.xboxlive.com",
                rpsTicket = "d=token",
            ),
            relyingParty = "http://auth.xboxlive.com",
            tokenType = "JWT",
        )
        val xboxJson = Json.parseToJsonElement(
            Json.encodeToString(xboxRequest),
        ).jsonObject
        assertEquals(
            "d=token",
            xboxJson.getValue("Properties").jsonObject
                .getValue("RpsTicket").jsonPrimitive.content,
        )
        assertEquals(
            "http://auth.xboxlive.com",
            xboxJson.getValue("RelyingParty").jsonPrimitive.content,
        )

        val minecraftRequest = MinecraftXboxLoginRequest("XBL3.0 x=uhs;token")
        val minecraftJson = Json.parseToJsonElement(
            Json.encodeToString(minecraftRequest),
        ).jsonObject
        assertEquals(
            "XBL3.0 x=uhs;token",
            minecraftJson.getValue("identityToken").jsonPrimitive.content,
        )
    }

    @Test
    fun responseModelsPreserveRawValuesWithoutSemanticValidation() {
        val microsoft = Json.decodeFromString<MicrosoftTokenResponse>(
            buildJsonObject {
                put("token_type", "unexpected")
                put("expires_in", Long.MAX_VALUE)
                put("access_token", "")
            }.toString(),
        )
        assertEquals("unexpected", microsoft.tokenType)
        assertEquals(Long.MAX_VALUE, microsoft.expiresIn)
        assertEquals("", microsoft.accessToken)

        val xbox = Json.decodeFromString<XboxTokenResponse>(
            buildJsonObject {
                put("IssueInstant", "not-an-instant")
                put("NotAfter", "also-not-an-instant")
                put("Token", "")
                put(
                    "DisplayClaims",
                    buildJsonObject { put("xui", buildJsonArray {}) },
                )
            }.toString(),
        )
        assertEquals("not-an-instant", xbox.issueInstant)
        assertEquals("", xbox.token)
        assertEquals(emptyList(), xbox.displayClaims.xui)

        val profile = Json.decodeFromString<MinecraftProfileResponse>(
            buildJsonObject {
                put("id", "not-a-uuid")
                put("name", "")
                put(
                    "skins",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "")
                                put("state", "")
                                put("url", "relative")
                                put("variant", "")
                            },
                        )
                    },
                )
                put("capes", buildJsonArray {})
            }.toString(),
        )
        assertEquals("not-a-uuid", profile.id)
        assertEquals("relative", profile.skins.single().url)
    }

    @Test
    fun documentedOptionalFieldsRemainNullable() {
        val token = Json.decodeFromString<MicrosoftTokenResponse>(
            buildJsonObject {
                put("token_type", "Bearer")
                put("expires_in", 3600)
                put("access_token", "token")
            }.toString(),
        )
        assertNull(token.scope)
        assertNull(token.extExpiresIn)
        assertNull(token.refreshToken)

        val entitlements = Json.decodeFromString<MinecraftEntitlementsResponse>(
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        add(buildJsonObject { put("name", "game_minecraft") })
                    },
                )
            }.toString(),
        )
        assertNull(entitlements.items.single().signature)
        assertNull(entitlements.signature)
        assertNull(entitlements.keyId)
    }
}
