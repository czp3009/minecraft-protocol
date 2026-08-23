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
import kotlin.uuid.Uuid

class MinecraftFriendsApiTest {
    @Test
    fun exposesFriendAndPresenceConditionalRequestMetadata() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { request ->
                requests += request
                when (requests.size) {
                    1 -> respondFriendsJson(
                        friendsJson(),
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            append(HttpHeaders.ETag, "friends-v1")
                            append(HttpHeaders.RetryAfter, "60")
                        },
                    )

                    2 -> respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.RetryAfter, "300"),
                    )

                    3 -> respondFriendsJson(friendsJson())
                    4 -> respond("", HttpStatusCode.NoContent)
                    5 -> respondFriendsJson(
                        presenceJson(),
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            append(HttpHeaders.ETag, "presence-v2")
                            append(HttpHeaders.RetryAfter, "75")
                        },
                    )

                    else -> error("Unexpected request ${request.url}")
                }
            },
        ).use { client ->
            val api = MinecraftFriendsApi(client)
            val initial = api.fetchFriends("access-token")
            val unchanged = api.fetchFriends("access-token", etag = initial.etag)
            val updated = assertNotNull(
                api.updateFriend(
                    accessToken = "access-token",
                    request = MinecraftFriendActionRequest.byProfileId(
                        Uuid.parse(FRIEND_ID),
                        MinecraftFriendUpdateType.REMOVE,
                    ),
                ),
            )
            val emptyUpdate = api.updateFriend(
                accessToken = "access-token",
                request = MinecraftFriendActionRequest.byName("Notch", MinecraftFriendUpdateType.ADD),
            )
            val presence = api.updatePresence(
                accessToken = "access-token",
                request = MinecraftPresenceRequest(MinecraftPresenceStatus.PLAYING_SERVER),
                etag = "presence-v1",
            )

            assertEquals("Notch", initial.body?.friends?.single()?.name)
            assertNull(initial.body?.incomingRequests)
            assertEquals("friends-v1", initial.etag)
            assertEquals("60", initial.retryAfter)
            assertTrue(unchanged.isNotModified)
            assertNull(unchanged.body)
            assertEquals("friends-v1", unchanged.etag)
            assertEquals("300", unchanged.retryAfter)
            assertEquals("Notch", updated.friends?.single()?.name)
            assertNull(emptyUpdate)
            assertEquals("presence-v2", presence.etag)
            assertEquals("75", presence.retryAfter)
            assertEquals(MinecraftPresenceStatus.PLAYING_SERVER, presence.body?.presence?.single()?.status)
        }

        assertEquals(5, requests.size)
        requests.forEach { request ->
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        }
        assertEquals(HttpMethod.Get, requests[0].method)
        assertEquals("/friends", requests[0].url.encodedPath)
        assertNull(requests[0].headers[HttpHeaders.IfNoneMatch])
        assertEquals("friends-v1", requests[1].headers[HttpHeaders.IfNoneMatch])
        assertEquals(HttpMethod.Put, requests[2].method)
        val byIdBody = Json.parseToJsonElement(assertIs<TextContent>(requests[2].body).text).jsonObject
        assertFalse(byIdBody.containsKey("name"))
        assertEquals(FRIEND_ID, byIdBody.getValue("profileId").jsonPrimitive.content)
        assertEquals("REMOVE", byIdBody.getValue("updateType").jsonPrimitive.content)
        val byNameBody = Json.parseToJsonElement(assertIs<TextContent>(requests[3].body).text).jsonObject
        assertEquals("Notch", byNameBody.getValue("name").jsonPrimitive.content)
        assertFalse(byNameBody.containsKey("profileId"))
        assertEquals(HttpMethod.Post, requests[4].method)
        assertEquals("/presence", requests[4].url.encodedPath)
        assertEquals("presence-v1", requests[4].headers[HttpHeaders.IfNoneMatch])
        val presenceBody = Json.parseToJsonElement(assertIs<TextContent>(requests[4].body).text).jsonObject
        assertEquals("PLAYING_SERVER", presenceBody.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun identityConveniencesPassTheMinecraftAccessToken() = runTest {
        val identity = MinecraftOnlineIdentity(
            id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player"),
            name = "Player",
            accessToken = "access-token",
        )
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { request ->
                requests += request
                respond("", HttpStatusCode.NoContent)
            },
        ).use { client ->
            val api = MinecraftFriendsApi(client)

            assertNull(api.fetchFriends(identity).body)
            assertNull(
                api.updateFriend(
                    identity,
                    MinecraftFriendActionRequest.byName("Notch", MinecraftFriendUpdateType.ADD),
                ),
            )
            assertNull(
                api.updatePresence(
                    identity,
                    MinecraftPresenceRequest(MinecraftPresenceStatus.ONLINE),
                ).body,
            )
        }

        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun structuredFailuresExposeRawAndParsedBodies() = runTest {
        HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("path", "/friends")
                        put("error", "UNKNOWN_PROFILE")
                        put("errorMessage", "Profile does not exist")
                        put("details", buildJsonObject { put("name", "missing") })
                    }.toString(),
                    status = HttpStatusCode.BadRequest,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append(HttpHeaders.RetryAfter, "30")
                    },
                )
            },
        ).use { client ->
            val failure = assertFailsWith<MinecraftFriendsResponseException> {
                MinecraftFriendsApi(client).updateFriend(
                    accessToken = "access-token",
                    request = MinecraftFriendActionRequest.byName("missing", MinecraftFriendUpdateType.ADD),
                )
            }

            assertEquals(HttpStatusCode.BadRequest, failure.response.status)
            assertEquals("UNKNOWN_PROFILE", failure.parsedErrorBody.error)
            assertEquals("30", failure.response.headers[HttpHeaders.RetryAfter])
            assertEquals("missing", failure.parsedErrorBody.details?.get("name")?.jsonPrimitive?.content)
            assertIs<ResponseException>(failure)
        }
    }

    @Test
    fun decodingFailuresPropagateUnchanged() = runTest {
        HttpClient(MockEngine { respond("not-json", HttpStatusCode.OK) }).use { client ->
            assertFailsWith<SerializationException> {
                MinecraftFriendsApi(client).fetchFriends("access-token")
            }
        }
    }
}

private fun friendsJson() = buildJsonObject {
    put(
        "friends",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("profileId", FRIEND_ID)
                    put("name", "Notch")
                },
            )
        },
    )
}

private fun presenceJson() = buildJsonObject {
    put(
        "presence",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("profileId", FRIEND_ID)
                    put("pmid", "b50ad385829d3141a2167e7d7539ba7f")
                    put("status", "PLAYING_SERVER")
                    put("lastUpdated", "2026-08-23T12:34:56Z")
                },
            )
        },
    )
}

private fun MockRequestHandleScope.respondFriendsJson(
    body: JsonObject,
    headers: Headers = headersOf(HttpHeaders.ContentType, "application/json"),
) = respond(
    content = body.toString(),
    status = HttpStatusCode.OK,
    headers = headers,
)

private const val FRIEND_ID = "069a79f444e94726a5befca90e38aaf5"
