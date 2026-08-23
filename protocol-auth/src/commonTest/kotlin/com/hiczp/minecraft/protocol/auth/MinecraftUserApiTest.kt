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

class MinecraftUserApiTest {
    @Test
    fun fetchesAndUpdatesUserAttributesAndBlockList() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/player/attributes" -> respondUserJson(userAttributesJson())
                    "/privacy/blocklist" -> respondUserJson(
                        buildJsonObject {
                            put(
                                "blockedProfiles",
                                buildJsonArray {
                                    add("b50ad385829d3141a2167e7d7539ba7f")
                                    add("853c80ef3c3749fdaa49938b674adae6")
                                },
                            )
                        },
                    )

                    else -> error("Unexpected request ${request.url}")
                }
            },
        ).use { client ->
            val api = MinecraftUserApi(client)
            val attributes = assertNotNull(api.fetchAttributes("access-token"))
            val updated = assertNotNull(
                api.updateAttributes(
                    accessToken = "access-token",
                    request = MinecraftUserAttributesRequest(
                        friendsPreferences = MinecraftUserAttributesRequest.FriendsPreferences(
                            friends = MinecraftToggleValue.ENABLED,
                            acceptInvites = MinecraftToggleValue.DISABLED,
                        ),
                    ),
                ),
            )
            val blockList = assertNotNull(api.fetchBlockList("access-token"))

            val privileges = assertNotNull(attributes.privileges)
            assertTrue(privileges.onlineChat?.enabled == true)
            assertFalse(privileges.multiplayerServer?.enabled ?: true)
            assertNull(privileges.optionalTelemetry)
            assertEquals(MinecraftChatToggleValue.FRIENDS_ONLY, attributes.chatPreferences?.textCommunication)
            assertNull(attributes.banStatus?.bannedScopes?.get("MULTIPLAYER")?.expires)
            assertEquals(MinecraftToggleValue.ENABLED, updated.friendsPreferences?.friends)
            assertEquals(2, blockList.blockedProfiles?.size)
        }

        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        }
        assertEquals(HttpMethod.Get, requests[0].method)
        assertEquals(HttpMethod.Post, requests[1].method)
        val updateBody = Json.parseToJsonElement(assertIs<TextContent>(requests[1].body).text).jsonObject
        assertFalse(updateBody.containsKey("profanityFilterPreferences"))
        val preferences = updateBody.getValue("friendsPreferences").jsonObject
        assertEquals("ENABLED", preferences.getValue("friends").jsonPrimitive.content)
        assertEquals("DISABLED", preferences.getValue("acceptInvites").jsonPrimitive.content)
        assertEquals(HttpMethod.Get, requests[2].method)
    }

    @Test
    fun identityConveniencesAndEmptySuccessfulBodiesRemainCallerVisible() = runTest {
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
            val api = MinecraftUserApi(client)

            assertNull(api.fetchAttributes(identity))
            assertNull(
                api.updateAttributes(
                    identity,
                    MinecraftUserAttributesRequest(
                        profanityFilterPreferences =
                            MinecraftUserAttributesRequest.ProfanityFilterPreferences(enabled = true),
                    ),
                ),
            )
            assertNull(api.fetchBlockList(identity))
        }

        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        }

        val omittedBlockList = MinecraftServiceJson.decodeFromString<MinecraftBlockListResponse>(
            buildJsonObject {}.toString(),
        )
        assertNull(omittedBlockList.blockedProfiles)
    }

    @Test
    fun structuredFailuresExposeRawAndParsedBodies() = runTest {
        HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("path", "/player/attributes")
                        put("error", "ForbiddenOperationException")
                        put("errorMessage", "Invalid token")
                        put("details", buildJsonObject { put("reason", "expired") })
                    }.toString(),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).use { client ->
            val failure = assertFailsWith<MinecraftUserResponseException> {
                MinecraftUserApi(client).fetchAttributes("expired-token")
            }

            assertEquals(HttpStatusCode.Unauthorized, failure.response.status)
            assertEquals("Invalid token", failure.parsedErrorBody.errorMessage)
            assertEquals("expired", failure.parsedErrorBody.details?.get("reason")?.jsonPrimitive?.content)
            assertIs<ResponseException>(failure)
        }
    }

    @Test
    fun decodingFailuresPropagateUnchanged() = runTest {
        HttpClient(MockEngine { respond("not-json", HttpStatusCode.OK) }).use { client ->
            assertFailsWith<SerializationException> {
                MinecraftUserApi(client).fetchBlockList("access-token")
            }
        }
    }
}

private fun userAttributesJson() = buildJsonObject {
    put(
        "privileges",
        buildJsonObject {
            put("onlineChat", buildJsonObject { put("enabled", true) })
            put("multiplayerServer", buildJsonObject { put("enabled", false) })
            put("multiplayerRealms", buildJsonObject { put("enabled", true) })
            put("telemetry", buildJsonObject { put("enabled", true) })
        },
    )
    put("profanityFilterPreferences", buildJsonObject { put("enabled", true) })
    put(
        "friendsPreferences",
        buildJsonObject {
            put("friends", "ENABLED")
            put("acceptInvites", "DISABLED")
        },
    )
    put(
        "chatPreferences",
        buildJsonObject {
            put("textCommunication", "FRIENDS_ONLY")
        },
    )
    put(
        "banStatus",
        buildJsonObject {
            put(
                "bannedScopes",
                buildJsonObject {
                    put(
                        "MULTIPLAYER",
                        buildJsonObject {
                            put("banId", "b50ad385829d3141a2167e7d7539ba7f")
                            put("expires", JsonNull)
                            put("reason", "1")
                            put("reasonMessage", "Safety violation")
                        },
                    )
                },
            )
        },
    )
}

private fun MockRequestHandleScope.respondUserJson(body: JsonObject) = respond(
    content = body.toString(),
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
