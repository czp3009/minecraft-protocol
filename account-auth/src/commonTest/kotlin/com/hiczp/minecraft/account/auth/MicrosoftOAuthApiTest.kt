package com.hiczp.minecraft.account.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class MicrosoftOAuthApiTest {
    @Test
    fun oauthToolsExposeIndependentStateAndPkceOperations() {
        val firstState = MicrosoftOAuthTools.generateState()
        val secondState = MicrosoftOAuthTools.generateState()
        val codeVerifier = MicrosoftOAuthTools.generateCodeVerifier()

        assertEquals(43, firstState.length)
        assertEquals(43, codeVerifier.length)
        assertNotEquals(firstState, secondState)
        assertEquals(
            RFC_7636_CODE_CHALLENGE,
            MicrosoftOAuthTools.s256CodeChallenge(RFC_7636_CODE_VERIFIER),
        )
    }

    @Test
    fun authorizationUrlDerivesLinkedAuthorizationCodeAndPkceParameters() {
        val url = MicrosoftOAuthTools.authorizationUrl(
            clientId = "client id",
            redirectUri = "http://127.0.0.1:12345/oauth/callback?source=test",
            state = "state value",
            codeVerifier = RFC_7636_CODE_VERIFIER,
        )

        assertEquals("https", url.protocol.name)
        assertEquals("login.microsoftonline.com", url.host)
        assertEquals("/consumers/oauth2/v2.0/authorize", url.encodedPath)
        assertEquals("client id", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals(
            "http://127.0.0.1:12345/oauth/callback?source=test",
            url.parameters["redirect_uri"],
        )
        assertEquals("query", url.parameters["response_mode"])
        assertEquals(MicrosoftOAuthTools.MINECRAFT_SCOPE, url.parameters["scope"])
        assertEquals("state value", url.parameters["state"])
        assertEquals(RFC_7636_CODE_CHALLENGE, url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])
    }

    @Test
    fun highLevelRequestToolsDeriveFixedProtocolValues() {
        val authorizationCode = MicrosoftOAuthTools.authorizationCodeTokenRequest(
            clientId = "client-id",
            authorizationCode = "authorization-code",
            redirectUri = "http://127.0.0.1:12345/oauth/callback",
            codeVerifier = "code-verifier",
        )
        assertEquals(MicrosoftOAuthTools.AUTHORIZATION_CODE_GRANT_TYPE, authorizationCode.grantType)
        assertEquals(MicrosoftOAuthTools.MINECRAFT_SCOPE, authorizationCode.scope)
        assertEquals("authorization-code", authorizationCode.code)

        val deviceAuthorization = MicrosoftOAuthTools.deviceAuthorizationRequest("client-id")
        assertEquals("client-id", deviceAuthorization.clientId)
        assertEquals(MicrosoftOAuthTools.MINECRAFT_SCOPE, deviceAuthorization.scope)

        val deviceToken = MicrosoftOAuthTools.deviceCodeTokenRequest(
            clientId = "client-id",
            deviceCode = "device-code",
        )
        assertEquals(MicrosoftOAuthTools.DEVICE_CODE_GRANT_TYPE, deviceToken.grantType)
        assertEquals("device-code", deviceToken.deviceCode)

        val refresh = MicrosoftOAuthTools.refreshTokenRequest(
            clientId = "client-id",
            refreshToken = "refresh-token",
        )
        assertEquals(MicrosoftOAuthTools.REFRESH_TOKEN_GRANT_TYPE, refresh.grantType)
        assertEquals(MicrosoftOAuthTools.MINECRAFT_SCOPE, refresh.scope)
        assertEquals("refresh-token", refresh.refreshToken)
    }

    @Test
    fun deviceCodeSerializesTheRequestAndReturnsTheRawResponse() = runTest {
        var form: FormDataContent? = null
        val responseJson = buildJsonObject {
            put("user_code", "ABCD-EFGH")
            put("device_code", "device-secret")
            put("verification_uri", "https://microsoft.com/devicelogin")
            put("expires_in", Long.MAX_VALUE)
            put("interval", 0)
            put("message", "Follow the displayed instructions")
            put("future_field", "ignored")
        }
        HttpClient(
            MockEngine { request ->
                form = assertIs<FormDataContent>(request.body)
                respondJson(responseJson)
            },
        ).use { client ->
            val response = MicrosoftOAuthApi(client).deviceCode(
                MicrosoftDeviceAuthorizationRequest(
                    clientId = "client-id",
                    scope = MICROSOFT_TEST_SCOPE,
                ),
            )

            assertEquals("device-secret", response.deviceCode)
            assertEquals("ABCD-EFGH", response.userCode)
            assertEquals("https://microsoft.com/devicelogin", response.verificationUri)
            assertEquals(Long.MAX_VALUE, response.expiresIn)
            assertEquals(0L, response.interval)
            assertEquals("Follow the displayed instructions", response.message)
        }

        assertEquals("client-id", form?.formData?.get("client_id"))
        assertEquals(MICROSOFT_TEST_SCOPE, form?.formData?.get("scope"))
    }

    @Test
    fun tokenMethodsSerializeTheirRequestsAndReturnRawResponses() = runTest {
        val forms = mutableListOf<FormDataContent>()
        var requestIndex = 0
        val engine = MockEngine { request ->
            forms += assertIs<FormDataContent>(request.body)
            respondJson(
                tokenResponse(
                    accessToken = "access-${requestIndex++}",
                    refreshToken = if (requestIndex == 1) "refresh" else null,
                ),
            )
        }
        HttpClient(engine).use { client ->
            val api = MicrosoftOAuthApi(client)
            val authorizationCode = api.tokenWithAuthorizationCode(
                MicrosoftAuthorizationCodeTokenRequest(
                    grantType = "authorization_code",
                    code = "authorization-code",
                    clientId = "client-id",
                    redirectUri = "https://example.com/callback",
                    codeVerifier = "caller-code-verifier",
                    scope = MICROSOFT_TEST_SCOPE,
                ),
            )
            val deviceCode = api.tokenWithDeviceCode(
                MicrosoftDeviceCodeTokenRequest(
                    grantType = "urn:ietf:params:oauth:grant-type:device_code",
                    clientId = "client-id",
                    deviceCode = "device-code",
                ),
            )
            val refresh = api.tokenWithRefreshToken(
                MicrosoftRefreshTokenRequest(
                    clientId = "client-id",
                    grantType = "refresh_token",
                    refreshToken = "refresh-token",
                    scope = MICROSOFT_TEST_SCOPE,
                ),
            )

            assertEquals("access-0", authorizationCode.accessToken)
            assertEquals("refresh", authorizationCode.refreshToken)
            assertEquals("access-1", deviceCode.accessToken)
            assertEquals("access-2", refresh.accessToken)
            assertEquals(Long.MAX_VALUE, refresh.expiresIn)
        }

        assertEquals("authorization_code", forms[0].formData["grant_type"])
        assertEquals("authorization-code", forms[0].formData["code"])
        assertEquals("https://example.com/callback", forms[0].formData["redirect_uri"])
        assertEquals("caller-code-verifier", forms[0].formData["code_verifier"])
        assertEquals(
            "urn:ietf:params:oauth:grant-type:device_code",
            forms[1].formData["grant_type"],
        )
        assertEquals("device-code", forms[1].formData["device_code"])
        assertEquals("refresh_token", forms[2].formData["grant_type"])
        assertEquals("refresh-token", forms[2].formData["refresh_token"])
    }

    @Test
    fun rawTokenRequestsOmitNullableFormFields() = runTest {
        val forms = mutableListOf<FormDataContent>()
        HttpClient(
            MockEngine { request ->
                forms += assertIs<FormDataContent>(request.body)
                respondJson(tokenResponse("access-token", null))
            },
        ).use { client ->
            val api = MicrosoftOAuthApi(client)
            api.tokenWithAuthorizationCode(
                MicrosoftAuthorizationCodeTokenRequest(
                    grantType = "authorization_code",
                    code = "authorization-code",
                    clientId = "client-id",
                    redirectUri = "https://example.com/callback",
                ),
            )
            api.tokenWithRefreshToken(
                MicrosoftRefreshTokenRequest(
                    clientId = "client-id",
                    grantType = "refresh_token",
                    refreshToken = "refresh-token",
                ),
            )
        }

        assertNull(forms[0].formData["code_verifier"])
        assertNull(forms[0].formData["scope"])
        assertNull(forms[1].formData["scope"])
    }

    @Test
    fun deviceAuthorizationRequiresMicrosoftIntervalAndMessage() = runTest {
        val malformedResponses = listOf(
            deviceAuthorizationResponse(interval = null),
            deviceAuthorizationResponse(interval = JsonNull),
            deviceAuthorizationResponse(message = null),
            deviceAuthorizationResponse(message = JsonNull),
        )

        for (responseJson in malformedResponses) {
            HttpClient(MockEngine { respondJson(responseJson) }).use { client ->
                assertFailsWith<SerializationException> {
                    MicrosoftOAuthApi(client).deviceCode(
                        MicrosoftDeviceAuthorizationRequest(
                            clientId = "client-id",
                            scope = MICROSOFT_TEST_SCOPE,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun devicePollingStatusIsExposedOnlyThroughTheExceptionBody() = runTest {
        val errorJson = buildJsonObject {
            put("error", "authorization_pending")
            put("error_description", "not complete")
            put("error_codes", buildJsonArray { add(70016) })
        }
        HttpClient(
            MockEngine { respondJson(errorJson, HttpStatusCode.BadRequest) },
        ).use { client ->
            val failure = assertFailsWith<MicrosoftOAuthResponseException> {
                MicrosoftOAuthApi(client).tokenWithDeviceCode(
                    MicrosoftDeviceCodeTokenRequest(
                        grantType = "urn:ietf:params:oauth:grant-type:device_code",
                        clientId = "client-id",
                        deviceCode = "device-code",
                    ),
                )
            }

            assertEquals(errorJson.toString(), failure.responseBody)
            assertEquals("authorization_pending", failure.parsedErrorBody.error)
            assertEquals(listOf(70016L), failure.parsedErrorBody.errorCodes)
        }
    }

    @Test
    fun errorBodySerializationFailuresPropagateUnchanged() = runTest {
        HttpClient(
            MockEngine { respond("not-json", HttpStatusCode.BadGateway) },
        ).use { client ->
            assertFailsWith<SerializationException> {
                MicrosoftOAuthApi(client).deviceCode(
                    MicrosoftDeviceAuthorizationRequest(
                        clientId = "client-id",
                        scope = MICROSOFT_TEST_SCOPE,
                    ),
                )
            }
        }
    }
}

private fun tokenResponse(
    accessToken: String,
    refreshToken: String?,
): JsonObject = buildJsonObject {
    put("token_type", "unexpected-token-type")
    put("scope", JsonNull)
    put("expires_in", Long.MAX_VALUE)
    put("ext_expires_in", JsonNull)
    put("access_token", accessToken)
    refreshToken?.let { put("refresh_token", it) }
}

private fun deviceAuthorizationResponse(
    interval: JsonElement? = JsonPrimitive(5),
    message: JsonElement? = JsonPrimitive("Follow the displayed instructions"),
): JsonObject = buildJsonObject {
    put("user_code", "ABCD-EFGH")
    put("device_code", "device-secret")
    put("verification_uri", "https://microsoft.com/devicelogin")
    put("expires_in", 900)
    interval?.let { put("interval", it) }
    message?.let { put("message", it) }
}

private fun MockRequestHandleScope.respondJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body.toString(),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private const val MICROSOFT_TEST_SCOPE =
    "xboxlive.signin xboxlive.offline_access"
private const val RFC_7636_CODE_VERIFIER =
    "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
private const val RFC_7636_CODE_CHALLENGE =
    "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
