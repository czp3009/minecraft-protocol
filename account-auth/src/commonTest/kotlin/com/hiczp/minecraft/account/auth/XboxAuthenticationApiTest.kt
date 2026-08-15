package com.hiczp.minecraft.account.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class XboxAuthenticationApiTest {
    @Test
    fun highLevelToolsDeriveRequestsFromThePreviousTokenResponse() {
        val userRequest = XboxAuthenticationTools.userAuthenticationRequest(
            MicrosoftTokenResponse(
                tokenType = "Bearer",
                expiresIn = 3600,
                accessToken = "microsoft-access-token",
            ),
        )
        assertEquals("RPS", userRequest.properties.authMethod)
        assertEquals("user.auth.xboxlive.com", userRequest.properties.siteName)
        assertEquals("d=microsoft-access-token", userRequest.properties.rpsTicket)
        assertEquals("http://auth.xboxlive.com", userRequest.relyingParty)
        assertEquals("JWT", userRequest.tokenType)

        val xstsRequest = XboxAuthenticationTools.xstsAuthorizationRequest(
            xboxTokenResponse("xbox-user-token"),
        )
        assertEquals("RETAIL", xstsRequest.properties.sandboxId)
        assertEquals(listOf("xbox-user-token"), xstsRequest.properties.userTokens)
        assertEquals("rp://api.minecraftservices.com/", xstsRequest.relyingParty)
        assertEquals("JWT", xstsRequest.tokenType)
    }

    @Test
    fun sendsCallerSuppliedJsonRequestsAndReturnsRawTokenResponses() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respondJson(xboxTokenBody("token-${requests.size}"))
        }
        HttpClient(engine).use { client ->
            val api = XboxAuthenticationApi(client)
            val userToken = api.authenticateUser(
                XboxUserAuthenticationRequest(
                    properties = XboxUserAuthenticationRequest.Properties(
                        authMethod = "custom-method",
                        siteName = "custom-site",
                        rpsTicket = "custom-ticket",
                    ),
                    relyingParty = "custom-relying-party",
                    tokenType = "custom-token-type",
                ),
            )
            val xstsToken = api.authorizeXsts(
                XboxXstsAuthorizationRequest(
                    properties = XboxXstsAuthorizationRequest.Properties(
                        sandboxId = "custom-sandbox",
                        userTokens = listOf("first", "second"),
                    ),
                    relyingParty = "custom-xsts-relying-party",
                    tokenType = "custom-xsts-token-type",
                ),
            )

            assertEquals("token-1", userToken.token)
            assertEquals("not-an-instant", userToken.issueInstant)
            assertEquals("token-2", xstsToken.token)
            assertEquals("raw-user-hash", xstsToken.displayClaims.xui.single().userHash)
        }

        assertEquals(HttpMethod.Post, requests[0].method)
        assertEquals("user.auth.xboxlive.com", requests[0].url.host)
        assertEquals("1", requests[0].headers["x-xbl-contract-version"])
        val userJson = requestJson(requests[0])
        assertEquals(
            "custom-ticket",
            userJson.getValue("Properties").jsonObject
                .getValue("RpsTicket").jsonPrimitive.content,
        )
        assertEquals(
            "custom-relying-party",
            userJson.getValue("RelyingParty").jsonPrimitive.content,
        )

        val xstsJson = requestJson(requests[1])
        assertEquals(
            listOf("first", "second"),
            xstsJson.getValue("Properties").jsonObject
                .getValue("UserTokens").jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun nonSuccessResponsesExposeRawAndParsedErrorBodies() = runTest {
        val errorJson = buildJsonObject {
            put("Identity", "0")
            put("XErr", -1)
            put("Message", "policy")
        }
        HttpClient(
            MockEngine { respondJson(errorJson, HttpStatusCode.Unauthorized) },
        ).use { client ->
            val failure = assertFailsWith<XboxAuthenticationResponseException> {
                XboxAuthenticationApi(client).authenticateUser(userRequest())
            }
            assertEquals(errorJson.toString(), failure.responseBody)
            assertEquals(-1L, failure.parsedErrorBody.xErr)
            assertEquals("policy", failure.parsedErrorBody.message)
            assertNull(failure.parsedErrorBody.redirect)
        }
    }

    @Test
    fun errorBodySerializationFailuresPropagateUnchanged() = runTest {
        HttpClient(
            MockEngine {
                respondJson(
                    buildJsonObject { put("Message", "missing fields") },
                    HttpStatusCode.Unauthorized,
                )
            },
        ).use { client ->
            assertFailsWith<SerializationException> {
                XboxAuthenticationApi(client).authorizeXsts(xstsRequest())
            }
        }
    }

    @Test
    fun successfulJsonIsNotSemanticallyValidated() = runTest {
        val raw = buildJsonObject {
            put("IssueInstant", "invalid")
            put("NotAfter", "invalid")
            put("Token", "")
            put(
                "DisplayClaims",
                buildJsonObject { put("xui", buildJsonArray {}) },
            )
        }
        HttpClient(MockEngine { respondJson(raw) }).use { client ->
            val response = XboxAuthenticationApi(client).authenticateUser(userRequest())
            assertEquals("", response.token)
            assertEquals("invalid", response.notAfter)
            assertEquals(emptyList(), response.displayClaims.xui)
        }
    }

}

private fun userRequest() = XboxUserAuthenticationRequest(
    properties = XboxUserAuthenticationRequest.Properties(
        authMethod = "RPS",
        siteName = "user.auth.xboxlive.com",
        rpsTicket = "d=token",
    ),
    relyingParty = "http://auth.xboxlive.com",
    tokenType = "JWT",
)

private fun xstsRequest() = XboxXstsAuthorizationRequest(
    properties = XboxXstsAuthorizationRequest.Properties(
        sandboxId = "RETAIL",
        userTokens = listOf("token"),
    ),
    relyingParty = "rp://api.minecraftservices.com/",
    tokenType = "JWT",
)

private fun xboxTokenBody(token: String) = buildJsonObject {
    put("IssueInstant", "not-an-instant")
    put("NotAfter", "also-not-an-instant")
    put("Token", token)
    put(
        "DisplayClaims",
        buildJsonObject {
            put(
                "xui",
                buildJsonArray {
                    add(buildJsonObject { put("uhs", "raw-user-hash") })
                },
            )
        },
    )
}

private fun xboxTokenResponse(token: String) =
    XboxTokenResponse(
        issueInstant = "issue-instant",
        notAfter = "not-after",
        token = token,
        displayClaims = XboxTokenResponse.DisplayClaims(
            xui = listOf(
                XboxTokenResponse.DisplayClaims.UserClaim("user-hash"),
            ),
        ),
    )

private fun requestJson(request: HttpRequestData): JsonObject =
    Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject

private fun MockRequestHandleScope.respondJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body.toString(),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
