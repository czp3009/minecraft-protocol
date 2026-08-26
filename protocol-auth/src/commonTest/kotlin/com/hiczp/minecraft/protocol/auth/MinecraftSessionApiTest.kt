package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class MinecraftSessionApiTest {
    @Test
    fun sendsAndReceivesTheSerializableEndpointModels() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val mockEngine = MockEngine { httpRequestData ->
            requests += httpRequestData
            when (httpRequestData.url.encodedPath) {
                "/session/minecraft/join" -> respond("", HttpStatusCode.NoContent)
                "/session/minecraft/hasJoined" -> respondSessionJson(
                    buildJsonObject {
                        put("id", "b50ad385829d3141a2167e7d7539ba7f")
                        put(
                            "properties",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "textures")
                                        put("value", "texture-value")
                                        put("signature", "texture-signature")
                                    },
                                )
                            },
                        )
                        put(
                            "profileActions",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "FUTURE_ACTION") })
                            },
                        )
                    },
                )

                "/session/minecraft/profile/b50ad385829d3141a2167e7d7539ba7f" -> respondSessionJson(
                    buildJsonObject {
                        put("id", "b50ad385829d3141a2167e7d7539ba7f")
                        put("name", "Notch")
                        put(
                            "properties",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "textures")
                                        put("value", "profile-texture-value")
                                    },
                                )
                            },
                        )
                    },
                )

                else -> error("Unexpected request ${httpRequestData.url}")
            }
        }
        HttpClient(mockEngine).use { httpClient ->
            val minecraftSessionApi = MinecraftSessionApi(httpClient)
            val profileId = MinecraftOfflineIdentity.minecraftOfflineUuid("Notch")

            minecraftSessionApi.join(
                MinecraftSessionJoinRequest(
                    accessToken = "access-token",
                    selectedProfile = profileId.toHexString(),
                    serverId = "-server-hash",
                ),
            )
            val minecraftSessionHasJoinedResponse = assertNotNull(
                minecraftSessionApi.hasJoined(
                    MinecraftSessionHasJoinedRequest(
                        username = "Notch",
                        serverId = "-server-hash",
                        ip = "127.0.0.1",
                    ),
                ),
            )

            assertEquals(profileId.toHexString(), minecraftSessionHasJoinedResponse.id)
            assertEquals("texture-signature", minecraftSessionHasJoinedResponse.properties?.single()?.signature)
            assertEquals("FUTURE_ACTION", minecraftSessionHasJoinedResponse.profileActions?.single()?.type)
            assertEquals(profileId, minecraftSessionHasJoinedResponse.toGameProfile("Notch").id)

            val minecraftSessionProfileResponse = assertNotNull(
                minecraftSessionApi.fetchProfile(
                    profileId = profileId.toHexString(),
                    minecraftSessionProfileRequest = MinecraftSessionProfileRequest(unsigned = false),
                ),
            )
            assertEquals("Notch", minecraftSessionProfileResponse.name)
            assertEquals("profile-texture-value", minecraftSessionProfileResponse.properties?.single()?.value)
            assertNull(minecraftSessionProfileResponse.properties?.single()?.signature)
            assertEquals(profileId, minecraftSessionProfileResponse.toGameProfile().id)
        }

        assertEquals(HttpMethod.Post, requests[0].method)
        val joinBody = assertIs<TextContent>(requests[0].body).text
        val joinJson = Json.parseToJsonElement(joinBody).jsonObject
        assertEquals("access-token", joinJson.getValue("accessToken").jsonPrimitive.content)
        assertEquals(
            "b50ad385829d3141a2167e7d7539ba7f",
            joinJson.getValue("selectedProfile").jsonPrimitive.content,
        )
        assertEquals("-server-hash", joinJson.getValue("serverId").jsonPrimitive.content)
        assertEquals(HttpMethod.Get, requests[1].method)
        assertEquals("Notch", requests[1].url.parameters["username"])
        assertEquals("-server-hash", requests[1].url.parameters["serverId"])
        assertEquals("127.0.0.1", requests[1].url.parameters["ip"])
        assertEquals(HttpMethod.Get, requests[2].method)
        assertEquals("false", requests[2].url.parameters["unsigned"])
    }

    @Test
    fun convenienceExtensionsBuildEndpointRequests() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { httpRequestData ->
                requests += httpRequestData
                respond("", HttpStatusCode.NoContent)
            },
        ).use { httpClient ->
            val minecraftOnlineIdentity = MinecraftOnlineIdentity(
                id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player"),
                name = "Player",
                accessToken = "access-token",
            )
            val minecraftSessionApi = MinecraftSessionApi(httpClient)
            minecraftSessionApi.join(minecraftOnlineIdentity, MinecraftServerHash("server-hash"))
            assertNull(
                minecraftSessionApi.hasJoined(
                    username = minecraftOnlineIdentity.name,
                    serverId = MinecraftServerHash("server-hash"),
                ),
            )
            assertNull(minecraftSessionApi.fetchProfile(minecraftOnlineIdentity.id, requireSecure = true))
        }

        assertEquals("Player", requests[1].url.parameters["username"])
        assertEquals("server-hash", requests[1].url.parameters["serverId"])
        assertNull(requests[1].url.parameters["ip"])
        assertEquals(
            "/session/minecraft/profile/${MinecraftOfflineIdentity.minecraftOfflineUuid("Player").toHexString()}",
            requests[2].url.encodedPath,
        )
        assertEquals("false", requests[2].url.parameters["unsigned"])
    }

    @Test
    fun acceptsEverySuccessfulJoinStatusWithoutParsingABody() = runTest {
        for (httpStatusCode in listOf(HttpStatusCode.OK, HttpStatusCode.NoContent)) {
            HttpClient(MockEngine { respond("not-json", httpStatusCode) }).use { httpClient ->
                MinecraftSessionApi(httpClient).join(
                    MinecraftSessionJoinRequest(
                        accessToken = "token",
                        selectedProfile = "b50ad385829d3141a2167e7d7539ba7f",
                        serverId = "hash",
                    ),
                )
            }
        }
    }

    @Test
    fun preservesNullableHasJoinedCollections() = runTest {
        var responseIndex = 0
        HttpClient(
            MockEngine {
                respondSessionJson(
                    buildJsonObject {
                        put("id", "b50ad385829d3141a2167e7d7539ba7f")
                        if (responseIndex++ != 0) {
                            put("properties", JsonNull)
                            put("profileActions", JsonNull)
                        }
                    },
                )
            },
        ).use { httpClient ->
            val minecraftSessionApi = MinecraftSessionApi(httpClient)
            val omitted = assertNotNull(
                minecraftSessionApi.hasJoined(MinecraftSessionHasJoinedRequest("Player", "hash")),
            )
            val explicitNull = assertNotNull(
                minecraftSessionApi.hasJoined(MinecraftSessionHasJoinedRequest("Player", "hash")),
            )

            assertNull(omitted.properties)
            assertNull(omitted.profileActions)
            assertNull(explicitNull.properties)
            assertNull(explicitNull.profileActions)
            assertEquals(emptyList(), omitted.toGameProfile("Player").properties)
        }
    }

    @Test
    fun preservesResponseStringsWithoutSemanticValidation() = runTest {
        HttpClient(
            MockEngine {
                respondSessionJson(
                    buildJsonObject {
                        put("id", "b50ad385829d3141a2167e7d7539ba7f")
                        put(
                            "properties",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "")
                                        put("value", "")
                                    },
                                )
                            },
                        )
                        put(
                            "profileActions",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "FUTURE_ACTION") })
                            },
                        )
                    },
                )
            },
        ).use { httpClient ->
            val minecraftSessionHasJoinedResponse = assertNotNull(
                MinecraftSessionApi(httpClient).hasJoined(
                    MinecraftSessionHasJoinedRequest("Player", "hash"),
                ),
            )
            assertEquals("", minecraftSessionHasJoinedResponse.properties?.single()?.name)
            assertNull(minecraftSessionHasJoinedResponse.properties?.single()?.signature)
            assertEquals("FUTURE_ACTION", minecraftSessionHasJoinedResponse.profileActions?.single()?.type)
        }
    }

    @Test
    fun structuredFailuresExposeTheRawAndParsedResponse() = runTest {
        val httpClient = HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("error", "maintenance")
                        put("errorMessage", "later")
                    }.toString(),
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            expectSuccess = true
        }
        httpClient.use {
            val failure = assertFailsWith<MinecraftSessionResponseException> {
                MinecraftSessionApi(httpClient).join(
                    MinecraftSessionJoinRequest(
                        accessToken = "token",
                        selectedProfile = "b50ad385829d3141a2167e7d7539ba7f",
                        serverId = "hash",
                    ),
                )
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, failure.response.status)
            assertEquals("maintenance", failure.parsedErrorBody.error)
            assertNull(failure.parsedErrorBody.path)
            assertIs<ResponseException>(failure)

            val reconstructed = MinecraftSessionResponseException(
                httpResponse = failure.response,
                responseBody = failure.responseBody,
                parsedErrorBody = failure.parsedErrorBody,
            )
            assertEquals(failure.responseBody, reconstructed.responseBody)
            assertEquals(failure.parsedErrorBody, reconstructed.parsedErrorBody)
        }
    }

    @Test
    fun decodingFailuresPropagateUnchanged() = runTest {
        HttpClient(
            MockEngine { respond("not-json", HttpStatusCode.OK) },
        ).use { httpClient ->
            assertFailsWith<SerializationException> {
                MinecraftSessionApi(httpClient).hasJoined(
                    MinecraftSessionHasJoinedRequest("Player", "hash"),
                )
            }
            assertFailsWith<SerializationException> {
                MinecraftSessionApi(httpClient).fetchProfile(
                    profileId = "b50ad385829d3141a2167e7d7539ba7f",
                    minecraftSessionProfileRequest = MinecraftSessionProfileRequest(unsigned = true),
                )
            }
        }

        HttpClient(
            MockEngine { respond("not-json", HttpStatusCode.BadGateway) },
        ).use { httpClient ->
            assertFailsWith<SerializationException> {
                MinecraftSessionApi(httpClient).join(
                    MinecraftSessionJoinRequest(
                        accessToken = "token",
                        selectedProfile = "profile",
                        serverId = "hash",
                    ),
                )
            }
        }

        val minecraftSessionHasJoinedResponse = MinecraftSessionHasJoinedResponse(id = "not-a-uuid")
        assertFailsWith<IllegalArgumentException> {
            minecraftSessionHasJoinedResponse.toGameProfile("Player")
        }
    }

    @Test
    fun transportFailuresPropagateUnchanged() = runTest {
        val expectedSessionTransportFailure = ExpectedSessionTransportFailure()
        HttpClient(MockEngine { throw expectedSessionTransportFailure }).use { httpClient ->
            val actual = assertFailsWith<ExpectedSessionTransportFailure> {
                MinecraftSessionApi(httpClient).hasJoined(
                    MinecraftSessionHasJoinedRequest("Player", "hash"),
                )
            }
            assertSame(expectedSessionTransportFailure, actual)
        }
    }
}

private fun MockRequestHandleScope.respondSessionJson(
    body: JsonObject,
) = respond(
    content = body.toString(),
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private class ExpectedSessionTransportFailure : RuntimeException()
