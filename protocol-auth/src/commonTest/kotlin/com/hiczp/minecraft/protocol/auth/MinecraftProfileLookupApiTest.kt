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

class MinecraftProfileLookupApiTest {
    @Test
    fun sendsAndReceivesSingleAndBulkProfileLookups() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/minecraft/profile/lookup/name/notch" -> respondLookupJson(
                        profileJson(NOTCH_ID, "Notch"),
                    )

                    "/minecraft/profile/lookup/bulk/byname" -> respondLookupJson(
                        buildJsonArray {
                            add(profileJson(NOTCH_ID, "Notch"))
                            add(profileJson(JEB_ID, "jeb_"))
                        },
                    )

                    else -> error("Unexpected request ${request.url}")
                }
            },
        ).use { client ->
            val api = MinecraftProfileLookupApi(client)
            val notch = assertNotNull(api.findProfileByName("NoTcH"))
            val profiles = api.findProfilesByNames(
                MinecraftProfileLookupRequest(listOf("Notch", "jeb_")),
            )

            assertEquals("Notch", notch.name)
            assertEquals(Uuid.parse(NOTCH_ID), notch.toGameProfile().id)
            assertEquals(listOf("Notch", "jeb_"), profiles.map { it.name })
        }

        assertEquals(HttpMethod.Get, requests[0].method)
        assertNull(requests[0].headers[HttpHeaders.Authorization])
        assertEquals(HttpMethod.Post, requests[1].method)
        val bulkBody = assertIs<TextContent>(requests[1].body).text
        val bulkJson = Json.parseToJsonElement(bulkBody).jsonArray
        assertEquals(listOf("Notch", "jeb_"), bulkJson.map { it.jsonPrimitive.content })
        assertEquals(
            MinecraftProfileLookupRequest(listOf("Notch", "jeb_")),
            Json.decodeFromString<MinecraftProfileLookupRequest>(bulkBody),
        )
    }

    @Test
    fun emptySuccessfulBodiesRepresentMissingProfiles() = runTest {
        HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }).use { client ->
            val api = MinecraftProfileLookupApi(client)

            assertNull(api.findProfileByName("missing"))
            assertEquals(emptyList(), api.findProfilesByNames(MinecraftProfileLookupRequest(listOf("missing"))))
        }
    }

    @Test
    fun structuredFailuresExposeRawAndParsedBodies() = runTest {
        HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("path", "/minecraft/profile/lookup/name/missing")
                        put("error", "NOT_FOUND")
                        put("errorMessage", "Profile not found")
                        put("details", buildJsonObject { put("name", "missing") })
                    }.toString(),
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).use { client ->
            val failure = assertFailsWith<MinecraftProfileLookupResponseException> {
                MinecraftProfileLookupApi(client).findProfileByName("missing")
            }

            assertEquals(HttpStatusCode.NotFound, failure.response.status)
            assertEquals("NOT_FOUND", failure.parsedErrorBody.error)
            assertEquals("missing", failure.parsedErrorBody.details?.get("name")?.jsonPrimitive?.content)
            assertIs<ResponseException>(failure)
        }
    }

    @Test
    fun decodingFailuresPropagateUnchanged() = runTest {
        HttpClient(MockEngine { respond("not-json", HttpStatusCode.OK) }).use { client ->
            assertFailsWith<SerializationException> {
                MinecraftProfileLookupApi(client).findProfileByName("Notch")
            }
        }

        HttpClient(MockEngine { respond("not-json", HttpStatusCode.BadGateway) }).use { client ->
            assertFailsWith<SerializationException> {
                MinecraftProfileLookupApi(client).findProfileByName("Notch")
            }
        }
    }
}

private fun profileJson(id: String, name: String) = buildJsonObject {
    put("id", id)
    put("name", name)
}

private fun MockRequestHandleScope.respondLookupJson(body: JsonElement) = respond(
    content = body.toString(),
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private const val NOTCH_ID = "069a79f444e94726a5befca90e38aaf5"
private const val JEB_ID = "853c80ef3c3749fdaa49938b674adae6"
