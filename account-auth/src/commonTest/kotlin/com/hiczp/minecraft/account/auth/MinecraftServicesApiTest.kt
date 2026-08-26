package com.hiczp.minecraft.account.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class MinecraftServicesApiTest {
    @Test
    fun highLevelToolsDeriveTheMinecraftLoginRequestAndEntitlementMeaning() {
        val minecraftXboxLoginRequest = MinecraftServicesTools.xboxLoginRequest(
            XboxTokenResponse(
                issueInstant = "issue-instant",
                notAfter = "not-after",
                token = "xsts-token",
                displayClaims = XboxTokenResponse.DisplayClaims(
                    xui = listOf(
                        XboxTokenResponse.DisplayClaims.UserClaim("xsts-user-hash"),
                    ),
                ),
            ),
        )
        assertEquals("XBL3.0 x=xsts-user-hash;xsts-token", minecraftXboxLoginRequest.identityToken)

        assertEquals(
            true,
            MinecraftServicesTools.hasJavaEditionEntitlement(
                MinecraftEntitlementsResponse(
                    items = listOf(
                        MinecraftEntitlementsResponse.Item("unknown"),
                        MinecraftEntitlementsResponse.Item("game_minecraft"),
                    ),
                ),
            ),
        )
        assertEquals(
            false,
            MinecraftServicesTools.hasJavaEditionEntitlement(
                MinecraftEntitlementsResponse(
                    items = listOf(MinecraftEntitlementsResponse.Item("product_minecraft")),
                ),
            ),
        )
    }

    @Test
    fun exposesEachEndpointWithRawSerializableBodies() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val mockEngine = MockEngine { httpRequestData ->
            requests += httpRequestData
            when (httpRequestData.url.encodedPath) {
                "/authentication/login_with_xbox" -> respondJson(loginResponse())
                "/entitlements/mcstore" -> respondJson(entitlementsResponse("game_minecraft"))
                "/minecraft/profile" -> respondJson(profileResponse())
                "/entitlements/license" -> respondJson(entitlementsResponse("product_minecraft"))
                else -> error("Unexpected request ${httpRequestData.url}")
            }
        }
        HttpClient(mockEngine).use { httpClient ->
            val minecraftServicesApi = MinecraftServicesApi(httpClient)
            val minecraftLoginResponse = minecraftServicesApi.loginWithXbox(
                MinecraftXboxLoginRequest("caller-supplied-identity-token"),
            )
            val storeEntitlementsResponse =
                minecraftServicesApi.getStoreEntitlements(minecraftLoginResponse.accessToken)
            val minecraftProfileResponse = minecraftServicesApi.getMinecraftProfile(minecraftLoginResponse.accessToken)
            val licenseEntitlementsResponse =
                minecraftServicesApi.getLicenseEntitlements(minecraftLoginResponse.accessToken)

            assertEquals("", minecraftLoginResponse.username)
            assertEquals(Long.MAX_VALUE, minecraftLoginResponse.expiresIn)
            assertEquals("game_minecraft", storeEntitlementsResponse.items.single().name)
            assertEquals("not-a-uuid", minecraftProfileResponse.id)
            assertEquals("relative", minecraftProfileResponse.skins.single().url)
            assertEquals("cape-alias", minecraftProfileResponse.capes.single().alias)
            assertEquals("product_minecraft", licenseEntitlementsResponse.items.single().name)
        }

        assertEquals(HttpMethod.Post, requests[0].method)
        assertEquals(
            "caller-supplied-identity-token",
            requestJson(requests[0]).getValue("identityToken").jsonPrimitive.content,
        )
        for (httpRequestData in requests.drop(1)) {
            assertEquals("Bearer raw-access-token", httpRequestData.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun optionalFieldsFollowTheJsonPayloadDirectly() = runTest {
        var responseIndex = 0
        val mockEngine = MockEngine {
            when (responseIndex++) {
                0 -> respondJson(
                    buildJsonObject {
                        put(
                            "items",
                            buildJsonArray {
                                add(buildJsonObject { put("name", "game_minecraft") })
                            },
                        )
                    },
                )

                1 -> respondJson(
                    buildJsonObject {
                        put("id", "id")
                        put("name", "name")
                        put(
                            "skins",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", "skin")
                                        put("state", "ACTIVE")
                                        put("url", "url")
                                        put("variant", "CLASSIC")
                                        put("alias", JsonNull)
                                    },
                                )
                            },
                        )
                        put("capes", buildJsonArray {})
                    },
                )

                else -> error("Unexpected request")
            }
        }
        HttpClient(mockEngine).use { httpClient ->
            val minecraftServicesApi = MinecraftServicesApi(httpClient)
            val minecraftEntitlementsResponse = minecraftServicesApi.getStoreEntitlements("token")
            val minecraftProfileResponse = minecraftServicesApi.getMinecraftProfile("token")

            assertNull(minecraftEntitlementsResponse.items.single().signature)
            assertNull(minecraftEntitlementsResponse.signature)
            assertNull(minecraftEntitlementsResponse.keyId)
            assertNull(minecraftProfileResponse.skins.single().alias)
        }
    }

    @Test
    fun everyNonSuccessStatusThrowsAnEndpointException() = runTest {
        HttpClient(
            MockEngine { respondJson(buildJsonObject {}, HttpStatusCode.NotFound) },
        ).use { httpClient ->
            val failure = assertFailsWith<MinecraftServicesResponseException> {
                MinecraftServicesApi(httpClient).getMinecraftProfile("token")
            }
            assertEquals(HttpStatusCode.NotFound, failure.response.status)
        }

        val errorJson = buildJsonObject {
            put("path", "/minecraft/profile")
            put("error", JsonNull)
            put("errorMessage", "denied")
            put("developerMessage", JsonNull)
        }
        HttpClient(
            MockEngine { respondJson(errorJson, HttpStatusCode.Forbidden) },
        ).use { httpClient ->
            val failure = assertFailsWith<MinecraftServicesResponseException> {
                MinecraftServicesApi(httpClient).getMinecraftProfile("token")
            }
            assertEquals(errorJson.toString(), failure.responseBody)
            assertEquals("/minecraft/profile", failure.parsedErrorBody.path)
            assertEquals("denied", failure.parsedErrorBody.errorMessage)
            assertNull(failure.parsedErrorBody.error)
        }
    }

    @Test
    fun capeAliasIsRequiredAndNonNull() = runTest {
        val malformedResponses = listOf(
            profileResponse(capeAlias = null),
            profileResponse(capeAlias = JsonNull),
        )

        for (responseJson in malformedResponses) {
            HttpClient(MockEngine { respondJson(responseJson) }).use { httpClient ->
                assertFailsWith<SerializationException> {
                    MinecraftServicesApi(httpClient).getMinecraftProfile("token")
                }
            }
        }
    }

    @Test
    fun anEmptyJsonErrorRemainsAParsedRawErrorBody() = runTest {
        HttpClient(
            MockEngine {
                respondJson(buildJsonObject {}, HttpStatusCode.BadGateway)
            },
        ).use { httpClient ->
            val failure = assertFailsWith<MinecraftServicesResponseException> {
                MinecraftServicesApi(httpClient).getStoreEntitlements("token")
            }
            assertNull(failure.parsedErrorBody.path)
            assertNull(failure.parsedErrorBody.error)
        }
    }

}

private fun loginResponse() = buildJsonObject {
    put("username", "")
    put("roles", buildJsonArray {})
    put("access_token", "raw-access-token")
    put("token_type", "anything")
    put("expires_in", Long.MAX_VALUE)
}

private fun entitlementsResponse(name: String) = buildJsonObject {
    put(
        "items",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("name", name)
                    put("signature", JsonNull)
                },
            )
        },
    )
    put("signature", JsonNull)
    put("keyId", JsonNull)
}

private fun profileResponse(
    capeAlias: JsonElement? = JsonPrimitive("cape-alias"),
) = buildJsonObject {
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
    put(
        "capes",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "cape-id")
                    put("state", "ACTIVE")
                    put("url", "cape-url")
                    capeAlias?.let { put("alias", it) }
                },
            )
        },
    )
}

private fun requestJson(httpRequestData: HttpRequestData): JsonObject =
    Json.parseToJsonElement(assertIs<TextContent>(httpRequestData.body).text).jsonObject

private fun MockRequestHandleScope.respondJson(
    body: JsonObject,
    httpStatusCode: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body.toString(),
    status = httpStatusCode,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
