package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MinecraftAccountServiceTest {
    @Test
    fun exchangesExternalMicrosoftTokenForTypedMinecraftAccount() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.host) {
                    "user.auth.xboxlive.com" -> xboxToken("xbox-user-token", "user-hash")
                    "xsts.auth.xboxlive.com" -> xboxToken("xsts-token", "user-hash")
                    "api.minecraftservices.com" -> when (request.url.encodedPath) {
                        "/authentication/login_with_xbox" -> respondAccountJson(
                            buildJsonObject {
                                put("access_token", "minecraft-access-secret")
                                put("expires_in", 7_200)
                            },
                        )

                        "/entitlements/mcstore" -> respondAccountJson(
                            buildJsonObject {
                                put(
                                    "items",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("name", "future_product")
                                                put("signature", "item-signature")
                                            },
                                        )
                                    },
                                )
                                put("signature", "envelope-signature")
                                put("keyId", "rotating-key")
                            },
                        )

                        "/minecraft/profile" -> respondAccountJson(
                            buildJsonObject {
                                put("id", "b50ad385829d3141a2167e7d7539ba7f")
                                put("name", "OnlinePlayer")
                                put(
                                    "skins",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("id", "skin-id")
                                                put("state", "ACTIVE")
                                                put(
                                                    "url",
                                                    "https://textures.minecraft.net/texture/skin",
                                                )
                                                put("variant", "SLIM")
                                                put("alias", "default")
                                            },
                                        )
                                    },
                                )
                                put(
                                    "capes",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("id", "cape-id")
                                                put("state", "ACTIVE")
                                                put(
                                                    "url",
                                                    "https://textures.minecraft.net/texture/cape",
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )

                        else -> error("Unexpected Minecraft Services request ${request.url}")
                    }

                    else -> error("Unexpected authentication request ${request.url}")
                }
            },
        ) {
            followRedirects = false
        }
        try {
            val now = Instant.parse("2026-08-11T00:00:00Z")
            val service = MinecraftAccountService(
                client,
                now = { now },
            )
            val result = service.loginWithMicrosoftAccessToken(
                MicrosoftAccessToken.fromExternalProvider("microsoft-access-secret"),
            )

            assertEquals("OnlinePlayer", result.account.name)
            assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", result.account.id.toString())
            assertEquals(now + 7_200.seconds, result.account.expiresAt)
            assertEquals("future_product", result.entitlements.items.single().name)
            assertEquals("SLIM", result.profile.skins.single().variant)
            assertEquals("cape-id", result.profile.capes.single().id)
            assertFalse(result.toString().contains("microsoft-access-secret"))
            assertFalse(result.toString().contains("minecraft-access-secret"))

            val stored = result.account.exportForSecureStorage()
            assertEquals("minecraft-access-secret", stored.accessToken)
            assertFalse(stored.toString().contains("minecraft-access-secret"))
            assertEquals(
                result.account.id,
                MinecraftOnlineAccount.fromSecureStorage(stored).id,
            )

            assertXboxUserRequest(requests[0])
            assertXstsRequest(requests[1])
            assertMinecraftLoginRequest(requests[2])
            assertEquals(
                "Bearer minecraft-access-secret",
                requests[3].headers[HttpHeaders.Authorization],
            )
            assertEquals(
                "Bearer minecraft-access-secret",
                requests[4].headers[HttpHeaders.Authorization],
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun mapsXboxPolicyAndApplicationRegistrationFailuresWithoutSecrets() = runTest {
        val xboxClient = HttpClient(
            MockEngine {
                respondAccountJson(
                    buildJsonObject {
                        put("XErr", 2_148_916_233L)
                        put("Message", "raw-upstream-secret")
                    },
                    HttpStatusCode.Unauthorized,
                )
            },
        ) {
            followRedirects = false
        }
        try {
            val failure = assertFailsWith<XboxAuthenticationException> {
                MinecraftAccountService(xboxClient).loginWithMicrosoftAccessToken(
                    MicrosoftAccessToken.fromExternalProvider("microsoft-secret"),
                )
            }
            assertEquals(MinecraftAuthenticationStage.XBOX_USER, failure.stage)
            assertEquals(2_148_916_233L, failure.xerr)
            assertTrue(failure.message.orEmpty().contains("Xbox profile"))
            assertFalse(failure.message.orEmpty().contains("raw-upstream-secret"))
            assertFalse(failure.toString().contains("microsoft-secret"))
        } finally {
            xboxClient.close()
        }

        var stage = 0
        val registrationClient = HttpClient(
            MockEngine {
                when (stage++) {
                    0 -> xboxToken("xbox-user", "user-hash")
                    1 -> xboxToken("xsts", "user-hash")
                    else -> respondAccountJson(
                        buildJsonObject {
                            put("error", "Invalid app registration")
                            put("token", "raw-secret")
                        },
                        HttpStatusCode.Forbidden,
                    )
                }
            },
        ) {
            followRedirects = false
        }
        try {
            val failure = assertFailsWith<MinecraftApplicationRegistrationException> {
                MinecraftAccountService(registrationClient).loginWithMicrosoftAccessToken(
                    MicrosoftAccessToken.fromExternalProvider("microsoft-secret"),
                )
            }
            assertFalse(failure.message.orEmpty().contains("raw-secret"))
        } finally {
            registrationClient.close()
        }
    }

    @Test
    fun rejectsMismatchedXboxClaimsAndMissingJavaProfile() = runTest {
        var stage = 0
        val mismatchClient = HttpClient(
            MockEngine {
                when (stage++) {
                    0 -> xboxToken("xbox-user", "first-user")
                    else -> xboxToken("xsts", "different-user")
                }
            },
        ) {
            followRedirects = false
        }
        try {
            val failure = assertFailsWith<XboxAuthenticationException> {
                MinecraftAccountService(mismatchClient).loginWithMicrosoftAccessToken(
                    MicrosoftAccessToken.fromExternalProvider("microsoft-secret"),
                )
            }
            assertEquals(MinecraftAuthenticationStage.XBOX_XSTS, failure.stage)
        } finally {
            mismatchClient.close()
        }

        stage = 0
        val missingProfileClient = HttpClient(
            MockEngine { request ->
                when (stage++) {
                    0 -> xboxToken("xbox-user", "user-hash")
                    1 -> xboxToken("xsts", "user-hash")
                    2 -> respondAccountJson(
                        buildJsonObject {
                            put("access_token", "minecraft-token")
                            put("expires_in", 3_600)
                        },
                    )

                    3 -> respondAccountJson(
                        buildJsonObject {
                            put("items", buildJsonArray {})
                        },
                    )

                    else -> {
                        assertEquals("/minecraft/profile", request.url.encodedPath)
                        respondAccountJson(
                            buildJsonObject {
                                put("error", "NOT_FOUND")
                            },
                            HttpStatusCode.NotFound,
                        )
                    }
                }
            },
        ) {
            followRedirects = false
        }
        try {
            assertFailsWith<MinecraftJavaProfileNotFoundException> {
                MinecraftAccountService(missingProfileClient).loginWithMicrosoftAccessToken(
                    MicrosoftAccessToken.fromExternalProvider("microsoft-secret"),
                )
            }
        } finally {
            missingProfileClient.close()
        }
    }

    @Test
    fun existingMinecraftAccountBypassesEveryNetworkStage() {
        val client = HttpClient(MockEngine { error("No HTTP request expected") }) {
            followRedirects = false
        }
        try {
            val account = MinecraftOnlineAccount.fromExistingCredentials(
                name = "ExistingPlayer",
                id = offlineUuid("ExistingPlayer"),
                accessToken = "existing-secret",
            )
            val profile = MinecraftAccountProfile(
                id = account.id,
                name = account.name,
                skins = emptyList(),
                capes = emptyList(),
            )
            val result = MinecraftAccountService(client).existingAccount(account, profile)

            assertEquals(account, result.account)
            assertTrue(result.entitlements.items.isEmpty())
            assertFalse(result.toString().contains("existing-secret"))
        } finally {
            client.close()
        }
    }

    private fun assertXboxUserRequest(request: HttpRequestData) {
        assertEquals("1", request.headers["x-xbl-contract-version"])
        val json = request.jsonBody()
        assertEquals("http://auth.xboxlive.com", json["RelyingParty"]?.jsonPrimitive?.content)
        assertEquals(
            "d=microsoft-access-secret",
            json["Properties"]?.jsonObject
                ?.get("RpsTicket")?.jsonPrimitive?.content,
        )
    }

    private fun assertXstsRequest(request: HttpRequestData) {
        assertEquals("1", request.headers["x-xbl-contract-version"])
        val json = request.jsonBody()
        assertEquals("rp://api.minecraftservices.com/", json["RelyingParty"]?.jsonPrimitive?.content)
        assertEquals(
            "xbox-user-token",
            json["Properties"]?.jsonObject
                ?.get("UserTokens")?.jsonArray
                ?.single()?.jsonPrimitive?.content,
        )
    }

    private fun assertMinecraftLoginRequest(request: HttpRequestData) {
        assertEquals(
            "XBL3.0 x=user-hash;xsts-token",
            request.jsonBody()["identityToken"]?.jsonPrimitive?.content,
        )
    }
}

private fun HttpRequestData.jsonBody() =
    Json.parseToJsonElement((body as TextContent).text).jsonObject

private fun MockRequestHandleScope.xboxToken(
    token: String,
    userHash: String,
) = respondAccountJson(
    buildJsonObject {
        put("Token", token)
        put(
            "DisplayClaims",
            buildJsonObject {
                put(
                    "xui",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("uhs", userHash)
                            },
                        )
                    },
                )
            },
        )
    },
)

private fun MockRequestHandleScope.respondAccountJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = Json.encodeToString(JsonObject.serializer(), body),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
