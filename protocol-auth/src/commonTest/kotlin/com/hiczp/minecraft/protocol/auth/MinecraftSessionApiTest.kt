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
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
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

                else -> error("Unexpected request ${request.url}")
            }
        }
        HttpClient(engine).use { client ->
            val api = MinecraftSessionApi(client)
            val profileId = MinecraftOfflineIdentity.minecraftOfflineUuid("Notch")

            api.join(
                MinecraftSessionJoinRequest(
                    accessToken = "access-token",
                    selectedProfile = profileId.toHexString(),
                    serverId = "-server-hash",
                ),
            )
            val joined = assertNotNull(
                api.hasJoined(
                    MinecraftSessionHasJoinedRequest(
                        username = "Notch",
                        serverId = "-server-hash",
                        ip = "127.0.0.1",
                    ),
                ),
            )

            assertEquals(profileId.toHexString(), joined.id)
            assertEquals("texture-signature", joined.properties?.single()?.signature)
            assertEquals("FUTURE_ACTION", joined.profileActions?.single()?.type)
            assertEquals(profileId, joined.toGameProfile("Notch").id)
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
    }

    @Test
    fun convenienceExtensionsBuildEndpointRequests() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { request ->
                requests += request
                respond("", HttpStatusCode.NoContent)
            },
        ).use { client ->
            val identity = MinecraftOnlineIdentity(
                id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player"),
                name = "Player",
                accessToken = "access-token",
            )
            val api = MinecraftSessionApi(client)
            api.join(identity, MinecraftServerHash("server-hash"))
            assertNull(
                api.hasJoined(
                    username = identity.name,
                    serverId = MinecraftServerHash("server-hash"),
                ),
            )
        }

        assertEquals("Player", requests[1].url.parameters["username"])
        assertEquals("server-hash", requests[1].url.parameters["serverId"])
        assertNull(requests[1].url.parameters["ip"])
    }

    @Test
    fun acceptsEverySuccessfulJoinStatusWithoutParsingABody() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NoContent)) {
            HttpClient(MockEngine { respond("not-json", status) }).use { client ->
                MinecraftSessionApi(client).join(
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
        ).use { client ->
            val api = MinecraftSessionApi(client)
            val omitted = assertNotNull(
                api.hasJoined(MinecraftSessionHasJoinedRequest("Player", "hash")),
            )
            val explicitNull = assertNotNull(
                api.hasJoined(MinecraftSessionHasJoinedRequest("Player", "hash")),
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
        ).use { client ->
            val response = assertNotNull(
                MinecraftSessionApi(client).hasJoined(
                    MinecraftSessionHasJoinedRequest("Player", "hash"),
                ),
            )
            assertEquals("", response.properties?.single()?.name)
            assertNull(response.properties?.single()?.signature)
            assertEquals("FUTURE_ACTION", response.profileActions?.single()?.type)
        }
    }

    @Test
    fun structuredFailuresExposeTheRawAndParsedResponse() = runTest {
        val client = HttpClient(
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
        client.use {
            val failure = assertFailsWith<MinecraftSessionResponseException> {
                MinecraftSessionApi(client).join(
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
                response = failure.response,
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
        ).use { client ->
            assertFailsWith<SerializationException> {
                MinecraftSessionApi(client).hasJoined(
                    MinecraftSessionHasJoinedRequest("Player", "hash"),
                )
            }
        }

        HttpClient(
            MockEngine { respond("not-json", HttpStatusCode.BadGateway) },
        ).use { client ->
            assertFailsWith<SerializationException> {
                MinecraftSessionApi(client).join(
                    MinecraftSessionJoinRequest(
                        accessToken = "token",
                        selectedProfile = "profile",
                        serverId = "hash",
                    ),
                )
            }
        }

        val response = MinecraftSessionHasJoinedResponse(id = "not-a-uuid")
        assertFailsWith<IllegalArgumentException> {
            response.toGameProfile("Player")
        }
    }

    @Test
    fun transportFailuresPropagateUnchanged() = runTest {
        val expected = ExpectedSessionTransportFailure()
        HttpClient(MockEngine { throw expected }).use { client ->
            val actual = assertFailsWith<ExpectedSessionTransportFailure> {
                MinecraftSessionApi(client).hasJoined(
                    MinecraftSessionHasJoinedRequest("Player", "hash"),
                )
            }
            assertSame(expected, actual)
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
