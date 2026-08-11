package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class MinecraftSessionServiceTest {
    @Test
    fun joinsAndChecksAProfileThroughTheOfficialHttpShape() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/session/minecraft/join" -> respond("", HttpStatusCode.NoContent)
                "/session/minecraft/hasJoined" -> respondSessionJson(
                    buildJsonObject {
                        put("id", "b50ad385829d3141a2167e7d7539ba7f")
                        put("name", "CanonicalName")
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
                                add(
                                    buildJsonObject {
                                        put("type", "USING_BANNED_SKIN")
                                    },
                                )
                            },
                        )
                    },
                )

                else -> error("Unexpected request ${request.url}")
            }
        }
        HttpClient(engine) {
            followRedirects = false
        }.use { client ->
            val service = MinecraftSessionService(client)
            val profileId = offlineUuid("Notch")
            val account = MinecraftOnlineAccount.fromExistingCredentials(
                name = "Notch",
                id = profileId,
                accessToken = "access-token",
            )

            service.join(account, "-server-hash")
            val joined = assertNotNull(
                service.hasJoined("Notch", "-server-hash", "127.0.0.1"),
            )

            assertEquals(profileId, joined.profile.id)
            assertEquals("Notch", joined.profile.name)
            assertEquals("texture-signature", joined.profile.properties.single().signature)
            assertEquals(setOf("USING_BANNED_SKIN"), joined.profileActions)
        }

        assertEquals(HttpMethod.Post, requests[0].method)
        assertEquals(HttpMethod.Get, requests[1].method)
        assertEquals("Notch", requests[1].url.parameters["username"])
        assertEquals("-server-hash", requests[1].url.parameters["serverId"])
        assertEquals("127.0.0.1", requests[1].url.parameters["ip"])
    }

    @Test
    fun distinguishesAnUnverifiedProfileFromAnUnavailableService() = runTest {
        HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }) {
            followRedirects = false
        }.use { client ->
            assertNull(MinecraftSessionService(client).hasJoined("Nobody", "hash"))
        }

        HttpClient(
            MockEngine {
                respond("maintenance-secret", HttpStatusCode.ServiceUnavailable)
            },
        ) {
            followRedirects = false
        }.use { client ->
            val failure = assertFailsWith<MinecraftAuthenticationUnavailableException> {
                MinecraftSessionService(client).hasJoined("Nobody", "hash")
            }
            assertFalse(failure.message.orEmpty().contains("maintenance-secret"))
        }
    }

    @Test
    fun joinAcceptsOfficialSuccessStatusesAndSanitizesFailures() = runTest {
        for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.OK)) {
            HttpClient(MockEngine { respond("", status) }) {
                followRedirects = false
            }.use { client ->
                MinecraftSessionService(client).joinWithExistingCredentials(
                    accessToken = "token",
                    selectedProfile = offlineUuid("Player"),
                    serverHash = "hash",
                )
            }
        }

        HttpClient(
            MockEngine {
                respond("sensitive-upstream-body", HttpStatusCode.BadRequest)
            },
        ) {
            followRedirects = false
        }.use { client ->
            val failure = assertFailsWith<MinecraftAuthenticationRejectedException> {
                MinecraftSessionService(client).joinWithExistingCredentials(
                    accessToken = "token",
                    selectedProfile = offlineUuid("Player"),
                    serverHash = "hash",
                )
            }
            assertEquals(MinecraftAuthenticationStage.SESSION_JOIN, failure.stage)
            assertEquals(400, failure.statusCode)
            assertFalse(failure.message.orEmpty().contains("sensitive-upstream-body"))
        }
    }

    @Test
    fun hasJoinedHandlesNegativeStatusesAndOptionalFields() = runTest {
        for (
        status in listOf(
            HttpStatusCode.NoContent,
            HttpStatusCode.NotFound,
            HttpStatusCode.Forbidden,
        )
        ) {
            HttpClient(MockEngine { respond("", status) }) {
                followRedirects = false
            }.use { client ->
                assertNull(MinecraftSessionService(client).hasJoined("Nobody", "hash"))
            }
        }

        var request: HttpRequestData? = null
        HttpClient(
            MockEngine {
                request = it
                respondSessionJson(
                    buildJsonObject {
                        put("id", "b50ad385829d3141a2167e7d7539ba7f")
                        put(
                            "properties",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "textures")
                                        put("value", "value")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        ) {
            followRedirects = false
        }.use { client ->
            val joined = assertNotNull(
                MinecraftSessionService(client).hasJoined("RequestedName", "hash"),
            )

            assertNull(request?.url?.parameters?.get("ip"))
            assertNull(joined.profile.properties.single().signature)
            assertTrue(joined.profileActions.isEmpty())
            assertEquals("RequestedName", joined.profile.name)
        }
    }

    @Test
    fun rejectsMalformedSuccessfulProfileAndRetainsItsSerializationCause() = runTest {
        HttpClient(
            MockEngine {
                respond(
                    "credential-shaped-invalid-json",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            followRedirects = false
        }.use { client ->
            val failure = assertFailsWith<MinecraftAuthenticationException> {
                MinecraftSessionService(client).hasJoined("Player", "hash")
            }
            assertIs<MinecraftAuthenticationException>(failure)
            assertFalse(failure.message.orEmpty().contains("credential-shaped-invalid-json"))
            assertNotNull(failure.cause)
        }
    }
}

private fun MockRequestHandleScope.respondSessionJson(
    body: JsonObject,
) = respond(
    content = Json.encodeToString(JsonObject.serializer(), body),
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
