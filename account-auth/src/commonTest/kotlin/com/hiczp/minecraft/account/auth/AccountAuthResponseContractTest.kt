package com.hiczp.minecraft.account.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class AccountAuthResponseContractTest {
    @Test
    fun stageFailuresUseTheSharedPublicResponseExceptionHierarchy() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("error", "invalid_request")
                    }.toString(),
                    status = HttpStatusCode.BadRequest,
                )
            },
        ) {
            expectSuccess = true
        }
        client.use {
            val failure = assertFailsWith<MicrosoftOAuthResponseException> {
                MicrosoftOAuthApi(client).deviceCode(contractDeviceAuthorizationRequest())
            }

            assertIs<ResponseException>(failure)
            assertIs<AccountAuthResponseException>(failure)
            assertEquals("invalid_request", failure.parsedErrorBody.error)

            val reconstructed = MicrosoftOAuthResponseException(
                response = failure.response,
                responseBody = failure.responseBody,
                parsedErrorBody = failure.parsedErrorBody,
            )
            assertEquals(failure.responseBody, reconstructed.responseBody)
            assertEquals(failure.parsedErrorBody, reconstructed.parsedErrorBody)
        }
    }

    @Test
    fun everyEndpointOverridesCallerDefaultResponseValidation() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("error", "invalid_request")
                        put("Identity", "identity")
                        put("XErr", 0)
                        put("Message", "message")
                    }.toString(),
                    status = HttpStatusCode.BadRequest,
                )
            },
        ) {
            expectSuccess = true
        }
        client.use {
            val microsoftOAuth = MicrosoftOAuthApi(client)
            assertFailsWith<MicrosoftOAuthResponseException> {
                microsoftOAuth.deviceCode(contractDeviceAuthorizationRequest())
            }
            assertFailsWith<MicrosoftOAuthResponseException> {
                microsoftOAuth.tokenWithAuthorizationCode(
                    contractAuthorizationCodeTokenRequest(),
                )
            }
            assertFailsWith<MicrosoftOAuthResponseException> {
                microsoftOAuth.tokenWithDeviceCode(contractDeviceCodeTokenRequest())
            }
            assertFailsWith<MicrosoftOAuthResponseException> {
                microsoftOAuth.tokenWithRefreshToken(contractRefreshTokenRequest())
            }

            val xboxAuthentication = XboxAuthenticationApi(client)
            assertFailsWith<XboxAuthenticationResponseException> {
                xboxAuthentication.authenticateUser(
                    XboxUserAuthenticationRequest(
                        properties = XboxUserAuthenticationRequest.Properties(
                            authMethod = "RPS",
                            siteName = "site",
                            rpsTicket = "ticket",
                        ),
                        relyingParty = "relying-party",
                        tokenType = "JWT",
                    ),
                )
            }
            assertFailsWith<XboxAuthenticationResponseException> {
                xboxAuthentication.authorizeXsts(
                    XboxXstsAuthorizationRequest(
                        properties = XboxXstsAuthorizationRequest.Properties(
                            sandboxId = "RETAIL",
                            userTokens = listOf("xbox-user-token"),
                        ),
                        relyingParty = "relying-party",
                        tokenType = "JWT",
                    ),
                )
            }

            val minecraftServices = MinecraftServicesApi(client)
            assertFailsWith<MinecraftServicesResponseException> {
                minecraftServices.loginWithXbox(
                    MinecraftXboxLoginRequest("identity-token"),
                )
            }
            assertFailsWith<MinecraftServicesResponseException> {
                minecraftServices.getStoreEntitlements("minecraft-access-token")
            }
            assertFailsWith<MinecraftServicesResponseException> {
                minecraftServices.getMinecraftProfile("minecraft-access-token")
            }
            assertFailsWith<MinecraftServicesResponseException> {
                minecraftServices.getLicenseEntitlements("minecraft-access-token")
            }
        }
    }

    @Test
    fun transportFailuresPropagateUnchanged() = runTest {
        val expected = ExpectedTransportFailure()
        HttpClient(MockEngine { throw expected }).use { client ->
            val actual = assertFailsWith<ExpectedTransportFailure> {
                MinecraftServicesApi(client).getMinecraftProfile("token")
            }
            assertSame(expected, actual)
        }
    }

    @Test
    fun successfulBodySerializationFailuresPropagateUnchanged() = runTest {
        HttpClient(MockEngine { respond(buildJsonObject {}.toString()) }).use { client ->
            assertFailsWith<SerializationException> {
                MicrosoftOAuthApi(client).deviceCode(contractDeviceAuthorizationRequest())
            }
        }
    }

    @Test
    fun apiObjectsNeverCloseTheCallerHttpClient() = runTest {
        var requestIndex = 0
        val client = HttpClient(
            MockEngine {
                if (requestIndex++ == 0) {
                    respond(
                        buildJsonObject {
                            put("user_code", "CODE")
                            put("device_code", "device")
                            put("verification_uri", "https://microsoft.com/devicelogin")
                            put("expires_in", 900)
                            put("interval", 5)
                            put("message", "Follow the displayed instructions")
                        }.toString(),
                    )
                } else {
                    respond("caller-request")
                }
            },
        )
        try {
            MicrosoftOAuthApi(client).deviceCode(contractDeviceAuthorizationRequest())
            assertEquals(
                "caller-request",
                client.get("https://example.invalid/caller").bodyAsText(),
            )
        } finally {
            client.close()
        }
    }
}

private class ExpectedTransportFailure : RuntimeException()

private fun contractDeviceAuthorizationRequest() =
    MicrosoftDeviceAuthorizationRequest(
        clientId = "client-id",
        scope = "xboxlive.signin xboxlive.offline_access",
    )

private fun contractAuthorizationCodeTokenRequest() =
    MicrosoftAuthorizationCodeTokenRequest(
        grantType = "authorization_code",
        code = "authorization-code",
        clientId = "client-id",
        redirectUri = "https://example.com/callback",
        codeVerifier = "code-verifier",
        scope = "xboxlive.signin xboxlive.offline_access",
    )

private fun contractDeviceCodeTokenRequest() =
    MicrosoftDeviceCodeTokenRequest(
        grantType = "urn:ietf:params:oauth:grant-type:device_code",
        clientId = "client-id",
        deviceCode = "device-code",
    )

private fun contractRefreshTokenRequest() =
    MicrosoftRefreshTokenRequest(
        clientId = "client-id",
        grantType = "refresh_token",
        refreshToken = "refresh-token",
        scope = "xboxlive.signin xboxlive.offline_access",
    )
