package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MicrosoftOAuthServiceTest {
    @Test
    fun validatesCallerOwnedApplicationConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            MicrosoftOAuthApplication("", listOf(MicrosoftOAuthScope("xboxlive.signin")))
        }
        assertFailsWith<IllegalArgumentException> {
            MicrosoftOAuthApplication("client-id", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            MicrosoftOAuthApplication(
                "client-id",
                listOf(
                    MicrosoftOAuthScope("xboxlive.signin"),
                    MicrosoftOAuthScope("xboxlive.signin"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MicrosoftOAuthApplication(
                "client-id",
                listOf(
                    MicrosoftOAuthScope("https://graph.microsoft.com/User.Read"),
                    MicrosoftOAuthScope("https://storage.azure.com/user_impersonation"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MicrosoftOAuthScope("XboxLive.signin offline_access")
        }

        val application = application()
        assertEquals("client-id", application.clientId)
        assertEquals("consumers", application.tenant)
        assertEquals(2, application.scopes.size)
    }

    @Test
    fun completesAuthorizationCodePkceOnceAndRedactsTokens() = runTest {
        var tokenForm: FormDataContent? = null
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("login.microsoftonline.com", request.url.host)
                assertEquals("/consumers/oauth2/v2.0/token", request.url.encodedPath)
                tokenForm = request.body as FormDataContent
                respondJson(
                    buildJsonObject {
                        put("token_type", "Bearer")
                        put("expires_in", 3_600)
                        put("access_token", "microsoft-access-secret")
                        put("refresh_token", "microsoft-refresh-secret")
                        put("scope", "xboxlive.signin xboxlive.offline_access")
                    },
                )
            },
        ) {
            followRedirects = false
        }
        try {
            val now = Instant.parse("2026-08-11T00:00:00Z")
            val service = MicrosoftOAuthService(
                client,
                application(),
                now = { now },
                pause = {},
            )
            val redirect = Url("minecraft-auth://oauth/callback?channel=desktop")
            val authorization = service.beginAuthorizationCodeLogin(redirect)
            val state = assertNotNull(authorization.authorizationUri.parameters["state"])
            val challenge = assertNotNull(
                authorization.authorizationUri.parameters["code_challenge"],
            )
            val expectedChallenge = Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode(
                    authorization.codeVerifier.encodeToByteArray()
                        .toByteString()
                        .sha256()
                        .toByteArray(),
                )

            assertEquals("code", authorization.authorizationUri.parameters["response_type"])
            assertEquals("S256", authorization.authorizationUri.parameters["code_challenge_method"])
            assertEquals(expectedChallenge, challenge)
            assertTrue(state.length >= 22)
            assertTrue(authorization.codeVerifier.length in 43..128)
            assertFalse(authorization.toString().contains(authorization.codeVerifier))

            val callback = URLBuilder(redirect).apply {
                parameters.append("code", "one-time-code")
                parameters.append("state", state)
            }.build()
            val tokens = service.completeAuthorizationCodeLogin(
                authorization,
                callback,
            )
            val form = assertNotNull(tokenForm).formData

            assertEquals("client-id", form["client_id"])
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("one-time-code", form["code"])
            assertEquals(redirect.toString(), form["redirect_uri"])
            assertEquals(authorization.codeVerifier, form["code_verifier"])
            assertEquals(now + 3_600.seconds, tokens.expiresAt)
            assertFalse(tokens.toString().contains("microsoft-access-secret"))
            assertFalse(tokens.toString().contains("microsoft-refresh-secret"))
            assertFalse(tokens.accessToken.toString().contains("microsoft-access-secret"))
            val refresh = assertNotNull(tokens.refreshToken)
            val persisted = refresh.exportForSecureStorage()
            assertEquals("microsoft-refresh-secret", persisted.token)
            assertFalse(persisted.toString().contains("microsoft-refresh-secret"))
            assertEquals(
                "microsoft-refresh-secret",
                service.importRefreshToken(persisted).exportForSecureStorage().token,
            )

            assertFailsWith<IllegalStateException> {
                service.completeAuthorizationCodeLogin(authorization, callback)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsMismatchedStateApplicationChannelAndExpiredTransaction() = runTest {
        val client = HttpClient(
            MockEngine { error("Token endpoint must not be called") },
        ) {
            followRedirects = false
        }
        try {
            var now = Instant.parse("2026-08-11T00:00:00Z")
            val service = MicrosoftOAuthService(
                client,
                application(),
                now = { now },
                pause = {},
            )
            val redirect = Url("minecraft-auth://oauth/callback?channel=desktop")
            val authorization = service.beginAuthorizationCodeLogin(redirect)
            val wrongState = URLBuilder(redirect).apply {
                parameters.append("code", "code")
                parameters.append("state", "wrong-state")
            }.build()
            val stateFailure = assertFailsWith<MicrosoftOAuthException> {
                service.completeAuthorizationCodeLogin(authorization, wrongState)
            }
            assertEquals("invalid_state", stateFailure.errorCode)

            val otherApplication = MicrosoftOAuthService(
                client,
                MicrosoftOAuthApplication(
                    "other-client-id",
                    application().scopes,
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                otherApplication.completeAuthorizationCodeLogin(
                    authorization,
                    wrongState,
                )
            }

            now += 11.minutes
            val expired = assertFailsWith<MicrosoftOAuthException> {
                service.completeAuthorizationCodeLogin(authorization, wrongState)
            }
            assertEquals("authorization_transaction_expired", expired.errorCode)

            val credentials = MicrosoftRefreshTokenCredentials(
                token = "refresh-secret",
                clientId = "client-id",
                tenant = "consumers",
                scopes = application().scopes.map(MicrosoftOAuthScope::value),
                flow = MicrosoftOAuthFlow.AUTHORIZATION_CODE,
                channel = "DIRECT",
            )
            val relayService = MicrosoftOAuthService(
                client,
                application(),
                Url("https://relay.example.test/auth"),
            )
            assertFailsWith<IllegalArgumentException> {
                relayService.importRefreshToken(credentials)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun pollsDeviceCodeWithPendingAndSlowDownUsingInjectedTime() = runTest {
        var requestIndex = 0
        val forms = mutableListOf<FormDataContent>()
        val client = HttpClient(
            MockEngine { request ->
                forms += request.body as FormDataContent
                when (requestIndex++) {
                    0 -> respondJson(
                        buildJsonObject {
                            put("device_code", "private-device-code")
                            put("user_code", "ABCD-EFGH")
                            put("verification_uri", "https://microsoft.com/devicelogin")
                            put(
                                "verification_uri_complete",
                                "https://microsoft.com/devicelogin?otc=ABCD-EFGH",
                            )
                            put("expires_in", 60)
                            put("interval", 2)
                            put("message", "Use the displayed code")
                        },
                    )

                    1 -> respondJson(
                        buildJsonObject {
                            put("error", "authorization_pending")
                        },
                        HttpStatusCode.BadRequest,
                    )

                    2 -> respondJson(
                        buildJsonObject {
                            put("error", "slow_down")
                        },
                        HttpStatusCode.BadRequest,
                    )

                    3 -> respondJson(
                        buildJsonObject {
                            put("token_type", "Bearer")
                            put("expires_in", 3_600)
                            put("access_token", "device-access-secret")
                        },
                    )

                    else -> error("Unexpected device-code request")
                }
            },
        ) {
            followRedirects = false
        }
        try {
            var now = Instant.parse("2026-08-11T00:00:00Z")
            val pauses = mutableListOf<Duration>()
            val service = MicrosoftOAuthService(
                client,
                application(),
                now = { now },
                pause = { duration ->
                    pauses += duration
                    now += duration
                },
            )

            val authorization = service.beginDeviceCodeLogin()
            assertEquals("ABCD-EFGH", authorization.userCode)
            assertEquals(2.seconds, authorization.pollingInterval)
            assertFalse(authorization.toString().contains("private-device-code"))
            val tokens = service.awaitDeviceCodeLogin(authorization)

            assertEquals(listOf(2.seconds, 2.seconds, 7.seconds), pauses)
            assertEquals("client-id", forms.first().formData["client_id"])
            assertEquals(
                "urn:ietf:params:oauth:grant-type:device_code",
                forms[1].formData["grant_type"],
            )
            assertEquals("private-device-code", forms[1].formData["device_code"])
            assertFalse(tokens.toString().contains("device-access-secret"))
            assertFailsWith<IllegalStateException> {
                service.awaitDeviceCodeLogin(authorization)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun refreshesWithRotationFallbackAndSanitizedRevocation() = runTest {
        val forms = mutableListOf<FormDataContent>()
        var requestIndex = 0
        val client = HttpClient(
            MockEngine { request ->
                forms += request.body as FormDataContent
                when (requestIndex++) {
                    0 -> respondJson(
                        buildJsonObject {
                            put("token_type", "Bearer")
                            put("expires_in", 3_600)
                            put("access_token", "rotated-access-secret")
                            put("refresh_token", "rotated-refresh-secret")
                        },
                    )

                    1 -> respondJson(
                        buildJsonObject {
                            put("token_type", "Bearer")
                            put("expires_in", 1_800)
                            put("access_token", "fallback-access-secret")
                        },
                    )

                    2 -> respondJson(
                        buildJsonObject {
                            put("error", "invalid_grant")
                        },
                        HttpStatusCode.BadRequest,
                    )

                    else -> error("Unexpected refresh request")
                }
            },
        ) {
            followRedirects = false
        }
        try {
            val now = Instant.parse("2026-08-11T00:00:00Z")
            val service = MicrosoftOAuthService(
                client,
                application(),
                now = { now },
                pause = {},
            )
            val original = service.importRefreshToken(
                MicrosoftRefreshTokenCredentials(
                    token = "original-refresh-secret",
                    clientId = "client-id",
                    tenant = "consumers",
                    scopes = application().scopes.map(MicrosoftOAuthScope::value),
                    flow = MicrosoftOAuthFlow.AUTHORIZATION_CODE,
                    channel = "DIRECT",
                ),
            )

            val rotated = service.refresh(original)
            assertEquals(now + 3_600.seconds, rotated.expiresAt)
            assertEquals(
                "rotated-refresh-secret",
                assertNotNull(rotated.refreshToken).exportForSecureStorage().token,
            )
            assertEquals("refresh_token", forms[0].formData["grant_type"])
            assertEquals("original-refresh-secret", forms[0].formData["refresh_token"])
            assertEquals(
                "xboxlive.signin xboxlive.offline_access",
                forms[0].formData["scope"],
            )

            val fallback = service.refresh(assertNotNull(rotated.refreshToken))
            assertEquals(now + 1_800.seconds, fallback.expiresAt)
            assertEquals(
                "rotated-refresh-secret",
                assertNotNull(fallback.refreshToken).exportForSecureStorage().token,
            )
            assertFalse(fallback.toString().contains("fallback-access-secret"))

            val rejected = assertFailsWith<MicrosoftOAuthException> {
                service.refresh(assertNotNull(fallback.refreshToken))
            }
            assertEquals("invalid_grant", rejected.errorCode)
            assertFalse(rejected.toString().contains("rotated-refresh-secret"))
        } finally {
            client.close()
        }
    }

    @Test
    fun randomAuthorizationTransactionsDoNotReuseStateOrVerifier() {
        val client = HttpClient(MockEngine { error("No HTTP expected") }) {
            followRedirects = false
        }
        try {
            val service = MicrosoftOAuthService(
                client,
                application(),
            )
            val redirect = Url("minecraft-auth://oauth/callback")
            val first = service.beginAuthorizationCodeLogin(redirect)
            val second = service.beginAuthorizationCodeLogin(redirect)

            assertNotEquals(
                first.authorizationUri.parameters["state"],
                second.authorizationUri.parameters["state"],
            )
            assertNotEquals(first.codeVerifier, second.codeVerifier)
        } finally {
            client.close()
        }
    }

    private fun application(): MicrosoftOAuthApplication =
        MicrosoftOAuthApplication(
            clientId = "client-id",
            scopes = listOf(
                MicrosoftOAuthScope("xboxlive.signin"),
                MicrosoftOAuthScope("xboxlive.offline_access"),
            ),
        )
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = Json.encodeToString(JsonObject.serializer(), body),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
