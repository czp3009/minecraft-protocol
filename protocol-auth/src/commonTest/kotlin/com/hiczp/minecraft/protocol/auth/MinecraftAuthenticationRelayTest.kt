package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftAuthenticationRelayTest {
    @Test
    fun directAndRelayProduceTheSameFixedUpstreamJoinRequest() = runTest {
        var directRequest: RequestSnapshot? = null
        var relayedUpstreamRequest: RequestSnapshot? = null
        val directClient = HttpClient(
            MockEngine { request ->
                directRequest = request.snapshot()
                respond("", HttpStatusCode.NoContent)
            },
        ) {
            followRedirects = false
        }
        val upstreamClient = HttpClient(
            MockEngine { request ->
                assertNullHeader(request, "X-App-Authorization")
                assertNullHeader(request, HttpHeaders.Cookie)
                relayedUpstreamRequest = request.snapshot()
                respond("", HttpStatusCode.NoContent)
            },
        ) {
            followRedirects = false
        }
        val handler = MinecraftAuthenticationRelayHandler(
            upstreamHttpClient = upstreamClient,
            policy = MinecraftAuthenticationRelayPolicy(
                setOf(MinecraftAuthenticationRelayOperation.SESSION_JOIN),
            ),
        )
        var relayEndpointRequests = 0
        val relayClient = HttpClient(
            MockEngine { request ->
                relayEndpointRequests++
                assertEquals(Url("https://app.example.test/auth/relay"), request.url)
                assertEquals("relay-credential", request.headers["X-App-Authorization"])
                assertEquals("relay-cookie=value", request.headers[HttpHeaders.Cookie])
                val handled = handler.handle(
                    MinecraftAuthenticationRelayRequest(
                        contentType = request.body.contentType?.toString(),
                        body = request.body.toByteArray(),
                    ),
                )
                assertEquals("no-store", handled.cacheControl)
                respond(
                    content = handled.body,
                    status = HttpStatusCode.fromValue(handled.statusCode),
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, handled.contentType)
                        append(HttpHeaders.CacheControl, handled.cacheControl)
                        handled.retryAfter?.let { append(HttpHeaders.RetryAfter, it) }
                    },
                )
            },
        ) {
            followRedirects = false
            defaultRequest {
                header("X-App-Authorization", "relay-credential")
                header(HttpHeaders.Cookie, "relay-cookie=value")
            }
        }
        try {
            val account = MinecraftOnlineAccount.fromExistingCredentials(
                name = "Player",
                id = Uuid.fromLongs(1, 2),
                accessToken = "minecraft-access-secret",
            )
            MinecraftSessionService(directClient).join(account, "server-hash")
            MinecraftSessionService(
                relayClient,
                Url("https://app.example.test/auth/relay"),
            ).join(account, "server-hash")

            assertEquals(1, relayEndpointRequests)
            assertEquals(directRequest, relayedUpstreamRequest)
            assertTrue(directClient.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
            assertTrue(upstreamClient.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
            assertTrue(relayClient.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
        } finally {
            directClient.close()
            upstreamClient.close()
            relayClient.close()
        }
    }

    @Test
    fun relayHandlerRejectsUnknownMalformedAndUnauthorizedOperations() = runTest {
        var upstreamRequests = 0
        val upstreamClient = HttpClient(
            MockEngine {
                upstreamRequests++
                respond("")
            },
        ) {
            followRedirects = false
        }
        val handler = MinecraftAuthenticationRelayHandler(
            upstreamClient,
            MinecraftAuthenticationRelayPolicy(
                setOf(MinecraftAuthenticationRelayOperation.SESSION_HAS_JOINED),
            ),
        )
        try {
            assertEquals(
                415,
                handler.handle(
                    relayRequest(
                        operation = "session_has_joined",
                        payload = buildJsonObject {},
                        contentType = "application/json",
                    ),
                ).statusCode,
            )
            assertEquals(
                426,
                handler.handle(
                    relayRequest(
                        version = 2,
                        operation = "session_has_joined",
                        payload = buildJsonObject {},
                    ),
                ).statusCode,
            )
            assertEquals(
                400,
                handler.handle(
                    relayRequest(
                        operation = "arbitrary_url",
                        payload = buildJsonObject {
                            put("url", "https://attacker.test")
                        },
                    ),
                ).statusCode,
            )
            assertEquals(
                400,
                handler.handle(
                    relayRequest(
                        operation = "session_has_joined",
                        payload = buildJsonObject {
                            put("username", "Player")
                            put("serverHash", "hash")
                            put("url", "https://attacker.test")
                        },
                    ),
                ).statusCode,
            )
            assertEquals(
                400,
                handler.handle(
                    relayRequest(
                        operation = "session_has_joined",
                        payload = buildJsonObject {
                            put("username", 7)
                            put("serverHash", "hash")
                        },
                    ),
                ).statusCode,
            )
            assertEquals(
                413,
                handler.handle(
                    MinecraftAuthenticationRelayRequest(
                        MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE.toString(),
                        ByteArray(MAXIMUM_RELAY_REQUEST_BYTES + 1),
                    ),
                ).statusCode,
            )

            val disallowedHandler = MinecraftAuthenticationRelayHandler(
                upstreamClient,
                MinecraftAuthenticationRelayPolicy(
                    setOf(MinecraftAuthenticationRelayOperation.SESSION_JOIN),
                ),
            )
            assertEquals(
                403,
                disallowedHandler.handle(
                    relayRequest(
                        operation = "session_has_joined",
                        payload = hasJoinedPayload(),
                    ),
                ).statusCode,
            )
            assertEquals(0, upstreamRequests)
        } finally {
            upstreamClient.close()
        }
    }

    @Test
    fun oauthRelayPolicyRequiresAndEnforcesCallerClientIds() = runTest {
        assertFailsWith<IllegalArgumentException> {
            MinecraftAuthenticationRelayPolicy(
                setOf(MinecraftAuthenticationRelayOperation.MICROSOFT_TOKEN),
            )
        }
        var upstreamRequests = 0
        val upstreamClient = HttpClient(
            MockEngine {
                upstreamRequests++
                respond("")
            },
        ) {
            followRedirects = false
        }
        try {
            val handler = MinecraftAuthenticationRelayHandler(
                upstreamClient,
                MinecraftAuthenticationRelayPolicy(
                    allowedOperations = setOf(
                        MinecraftAuthenticationRelayOperation.MICROSOFT_DEVICE_AUTHORIZATION,
                    ),
                    allowedMicrosoftClientIds = setOf("approved-client"),
                ),
            )
            val response = handler.handle(
                relayRequest(
                    operation = "microsoft_device_authorization",
                    payload = deviceAuthorizationPayload(
                        tenant = "consumers",
                        clientId = "unapproved-client",
                        scopes = "xboxlive.signin",
                    ),
                ),
            )

            assertEquals(403, response.statusCode)
            assertEquals("no-store", response.cacheControl)
            assertFalse(response.toString().contains("unapproved-client"))

            val invalidPayloads = listOf(
                deviceAuthorizationPayload("../consumers", "approved-client", "xboxlive.signin"),
                deviceAuthorizationPayload("consumers", "approved/client", "xboxlive.signin"),
                deviceAuthorizationPayload(
                    "consumers",
                    "approved-client",
                    "xboxlive.signin  xboxlive.offline_access",
                ),
                deviceAuthorizationPayload(
                    "consumers",
                    "approved-client",
                    "xboxlive.signin https://graph.microsoft.com/User.Read",
                ),
            )
            invalidPayloads.forEach { payload ->
                assertEquals(
                    400,
                    handler.handle(
                        relayRequest(
                            operation = "microsoft_device_authorization",
                            payload = payload,
                        ),
                    ).statusCode,
                )
            }
            assertEquals(0, upstreamRequests)
        } finally {
            upstreamClient.close()
        }
    }

    @Test
    fun relaySanitizesOversizedUpstreamAndPropagatesRetryMetadata() = runTest {
        var oversized = true
        val upstreamClient = HttpClient(
            MockEngine {
                if (oversized) {
                    respond(ByteArray(MAXIMUM_SESSION_RESPONSE_BYTES + 1))
                } else {
                    respond(
                        "upstream-rate-limit-detail",
                        HttpStatusCode.TooManyRequests,
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            append(HttpHeaders.RetryAfter, "17")
                        },
                    )
                }
            },
        ) {
            followRedirects = false
        }
        val handler = MinecraftAuthenticationRelayHandler(
            upstreamClient,
            MinecraftAuthenticationRelayPolicy(
                setOf(MinecraftAuthenticationRelayOperation.SESSION_HAS_JOINED),
            ),
        )
        try {
            val request = relayRequest(
                operation = "session_has_joined",
                payload = hasJoinedPayload(),
            )
            val oversizedResponse = handler.handle(request)
            assertEquals(502, oversizedResponse.statusCode)
            assertFalse(
                oversizedResponse.body.decodeToString().contains("upstream-rate-limit-detail"),
            )

            oversized = false
            val limitedResponse = handler.handle(request)
            assertEquals(200, limitedResponse.statusCode)
            assertEquals("17", limitedResponse.retryAfter)
            assertEquals("no-store", limitedResponse.cacheControl)
            assertFalse(limitedResponse.toString().contains("upstream-rate-limit-detail"))
        } finally {
            upstreamClient.close()
        }
    }

    @Test
    fun servicesAcceptCallerClientConfigurationAndRequireHttpsRelay() {
        val defaultClient = HttpClient(MockEngine { respond("") })
        try {
            MinecraftSessionService(defaultClient)
            MinecraftAuthenticationRelayHandler(
                defaultClient,
                MinecraftAuthenticationRelayPolicy(
                    setOf(MinecraftAuthenticationRelayOperation.SESSION_JOIN),
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                MinecraftSessionService(
                    defaultClient,
                    Url("http://relay.example.test/auth"),
                )
            }
        } finally {
            defaultClient.close()
        }
    }

    private fun assertNullHeader(request: HttpRequestData, name: String) {
        assertEquals(null, request.headers[name])
    }
}

private data class RequestSnapshot(
    val method: HttpMethod,
    val url: Url,
    val contentType: String?,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RequestSnapshot &&
                method == other.method &&
                url == other.url &&
                contentType == other.contentType &&
                body.contentEquals(other.body)

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + body.contentHashCode()
        return result
    }
}

private suspend fun HttpRequestData.snapshot(): RequestSnapshot =
    RequestSnapshot(
        method = method,
        url = url,
        contentType = body.contentType?.toString(),
        body = body.toByteArray(),
    )

private fun relayRequest(
    operation: String,
    payload: JsonObject,
    version: Int = MINECRAFT_AUTHENTICATION_RELAY_VERSION,
    contentType: String = MINECRAFT_AUTHENTICATION_RELAY_CONTENT_TYPE.toString(),
): MinecraftAuthenticationRelayRequest =
    MinecraftAuthenticationRelayRequest(
        contentType = contentType,
        body = RELAY_JSON.encodeToString(
            MinecraftAuthenticationRelayWireRequest.serializer(),
            MinecraftAuthenticationRelayWireRequest(
                version = version,
                operation = operation,
                payload = payload,
            ),
        ).encodeToByteArray(),
    )

private fun hasJoinedPayload(): JsonObject = buildJsonObject {
    put("username", "Player")
    put("serverHash", "hash")
}

private fun deviceAuthorizationPayload(
    tenant: String,
    clientId: String,
    scopes: String,
): JsonObject = buildJsonObject {
    put("tenant", tenant)
    put("clientId", clientId)
    put("scopes", scopes)
}
