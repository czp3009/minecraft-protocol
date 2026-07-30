package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MinecraftSessionServiceTest {
    @Test
    fun joinsAndChecksAProfileThroughTheOfficialHttpShape() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/session/minecraft/join" ->
                    respond("", HttpStatusCode.NoContent)

                "/session/minecraft/hasJoined" ->
                    respond(
                        content =
                            """
                            {
                              "id": "b50ad385829d3141a2167e7d7539ba7f",
                              "properties": [
                                {
                                  "name": "textures",
                                  "value": "texture-value",
                                  "signature": "texture-signature"
                                }
                              ],
                              "profileActions": [
                                {"type": "USING_BANNED_SKIN"}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )

                else -> error("Unexpected request ${request.url}")
            }
        }
        HttpClient(engine).use { client ->
            val service = MinecraftSessionService(client)
            val profileId = offlineUuid("Notch")

            service.join("access-token", profileId, "-server-hash")
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
        val noProfile = MinecraftSessionService(
            HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }),
        )
        assertNull(noProfile.hasJoined("Nobody", "hash"))
        noProfile.httpClient.close()

        val unavailable = MinecraftSessionService(
            HttpClient(
                MockEngine {
                    respond("maintenance", HttpStatusCode.ServiceUnavailable)
                },
            ),
        )
        assertFailsWith<MinecraftAuthenticationUnavailableException> {
            unavailable.hasJoined("Nobody", "hash")
        }
        unavailable.httpClient.close()
    }

    @Test
    fun joinAcceptsBothSuccessStatusesAndClassifiesClientAndServerErrors() =
        runTest {
            for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.OK)) {
                val service = serviceResponding(status)
                service.join("token", offlineUuid("Player"), "hash")
                service.httpClient.close()
            }

            val rejected = serviceResponding(
                HttpStatusCode.BadRequest,
                "invalid token",
            )
            val rejectedFailure =
                assertFailsWith<MinecraftAuthenticationException> {
                    rejected.join("token", offlineUuid("Player"), "hash")
                }
            assertFalse(
                rejectedFailure is MinecraftAuthenticationUnavailableException,
            )
            assertTrue(rejectedFailure.message.orEmpty().contains("invalid token"))
            rejected.httpClient.close()

            val unavailable = serviceResponding(
                HttpStatusCode.InternalServerError,
                "unavailable",
            )
            assertFailsWith<MinecraftAuthenticationUnavailableException> {
                unavailable.join("token", offlineUuid("Player"), "hash")
            }
            unavailable.httpClient.close()
        }

    @Test
    fun hasJoinedTreatsAllOfficialNegativeStatusesAsNoProfile() = runTest {
        for (
        status in listOf(
            HttpStatusCode.NoContent,
            HttpStatusCode.NotFound,
            HttpStatusCode.Forbidden,
        )
        ) {
            val service = serviceResponding(status)
            assertNull(service.hasJoined("Nobody", "hash"))
            service.httpClient.close()
        }
    }

    @Test
    fun hasJoinedOmitsOptionalIpAndDefaultsMissingProfileFields() = runTest {
        var request: HttpRequestData? = null
        val service = MinecraftSessionService(
            HttpClient(
                MockEngine {
                    request = it
                    respond(
                        """
                        {
                          "id": "b50ad385829d3141a2167e7d7539ba7f",
                          "properties": [{"name":"textures","value":"value"}]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

        val joined = assertNotNull(service.hasJoined("Notch", "hash"))

        assertNull(request?.url?.parameters?.get("ip"))
        assertNull(joined.profile.properties.single().signature)
        assertTrue(joined.profileActions.isEmpty())
        service.httpClient.close()
    }

    @Test
    fun hasJoinedUsesTheVerifiedCanonicalProfileNameWhenPresent() = runTest {
        val service = MinecraftSessionService(
            HttpClient(
                MockEngine {
                    respond(
                        """
                        {
                          "id": "b50ad385829d3141a2167e7d7539ba7f",
                          "name": "CanonicalName",
                          "properties": []
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

        val joined = assertNotNull(
            service.hasJoined("canonicalname", "hash"),
        )

        assertEquals("CanonicalName", joined.profile.name)
        service.httpClient.close()
    }

    private fun serviceResponding(
        status: HttpStatusCode,
        body: String = "",
    ): MinecraftSessionService =
        MinecraftSessionService(HttpClient(MockEngine { respond(body, status) }))
}
