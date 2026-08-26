package com.hiczp.minecraft.protocol.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MinecraftProfileKeyApiTest {
    @Test
    fun fetchesProfilePairAndServicesKeysWithoutOwningTheirTiming() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(
            MockEngine { httpRequestData ->
                requests += httpRequestData
                when (httpRequestData.url.encodedPath) {
                    "/player/certificates" -> respondProfileKeyJson(profileKeyPairJson())
                    "/publickeys" -> respondProfileKeyJson(servicesPublicKeysJson())
                    else -> error("Unexpected request ${httpRequestData.url}")
                }
            },
        ).use { httpClient ->
            val minecraftProfileKeyApi = MinecraftProfileKeyApi(httpClient)
            val minecraftProfileKeyPairResponse = minecraftProfileKeyApi.fetchProfileKeyPair("access-token")
            val minecraftServicesPublicKeysResponse = minecraftProfileKeyApi.fetchServicesPublicKeys()
            val minecraftProfileKeyPair = minecraftProfileKeyPairResponse.toMinecraftProfileKeyPair()
            val minecraftServicesPublicKeySet = minecraftServicesPublicKeysResponse.toMinecraftServicesPublicKeySet()

            assertContentEquals(MinecraftChatCryptoFixtures.publicKey(), minecraftProfileKeyPair.minecraftProfilePublicKey.encodedKey)
            assertEquals(PROFILE_KEY_EXPIRY, minecraftProfileKeyPair.profilePublicKeyData.expiresAtEpochMillis)
            assertEquals(PROFILE_KEY_REFRESH, minecraftProfileKeyPair.refreshedAfterEpochMillis)
            assertTrue(minecraftServicesPublicKeySet.verifyProfilePublicKey(PROFILE_ID, minecraftProfileKeyPair.profilePublicKeyData))
            assertEquals(1, minecraftServicesPublicKeySet.profilePropertyKeys.size)
            assertEquals(1, minecraftServicesPublicKeySet.playerCertificateKeys.size)
        }

        assertEquals(HttpMethod.Post, requests[0].method)
        assertEquals("Bearer access-token", requests[0].headers[HttpHeaders.Authorization])
        val requestBody = assertIs<ByteArrayContent>(requests[0].body)
        assertContentEquals(ByteArray(0), requestBody.bytes())
        assertEquals(ContentType.Application.Json, requestBody.contentType)
        assertEquals(HttpMethod.Get, requests[1].method)
        assertNull(requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun preservesNullablePublicKeyLists() = runTest {
        HttpClient(
            MockEngine {
                respondProfileKeyJson(
                    buildJsonObject {
                        put("profilePropertyKeys", JsonNull)
                    },
                )
            },
        ).use { httpClient ->
            val minecraftServicesPublicKeysResponse = MinecraftProfileKeyApi(httpClient).fetchServicesPublicKeys()

            assertNull(minecraftServicesPublicKeysResponse.profilePropertyKeys)
            assertNull(minecraftServicesPublicKeysResponse.playerCertificateKeys)
            assertTrue(minecraftServicesPublicKeysResponse.toMinecraftServicesPublicKeySet().playerCertificateKeys.isEmpty())
        }
    }

    @Test
    fun structuredFailuresExposeRawAndParsedBodies() = runTest {
        HttpClient(
            MockEngine {
                respond(
                    content = buildJsonObject {
                        put("path", "/player/certificates")
                        put("error", "ForbiddenOperationException")
                        put("errorMessage", "Invalid token")
                        put("details", buildJsonObject { put("reason", "expired") })
                    }.toString(),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).use { httpClient ->
            val failure = assertFailsWith<MinecraftProfileKeyResponseException> {
                MinecraftProfileKeyApi(httpClient).fetchProfileKeyPair("expired-token")
            }

            assertEquals(HttpStatusCode.Unauthorized, failure.response.status)
            assertEquals("ForbiddenOperationException", failure.parsedErrorBody.error)
            assertEquals("expired", failure.parsedErrorBody.details?.get("reason")?.toString()?.trim('"'))
            assertIs<ResponseException>(failure)
        }
    }

    @Test
    fun decodingFailuresPropagateUnchanged() = runTest {
        HttpClient(
            MockEngine { respond("not-json", HttpStatusCode.OK) },
        ).use { httpClient ->
            assertFailsWith<SerializationException> {
                MinecraftProfileKeyApi(httpClient).fetchServicesPublicKeys()
            }
        }
    }
}

private fun profileKeyPairJson() = buildJsonObject {
    put(
        "keyPair",
        buildJsonObject {
            put("privateKey", privateKeyPem())
            put("publicKey", publicKeyPem())
        },
    )
    put("publicKeySignatureV2", MinecraftChatCryptoFixtures.credentialSignatureBase64)
    put("expiresAt", Instant.fromEpochMilliseconds(PROFILE_KEY_EXPIRY).toString())
    put("refreshedAfter", Instant.fromEpochMilliseconds(PROFILE_KEY_REFRESH).toString())
}

private fun servicesPublicKeysJson() = buildJsonObject {
    put(
        "profilePropertyKeys",
        buildJsonArray {
            add(buildJsonObject { put("publicKey", MinecraftChatCryptoFixtures.publicKeyBase64) })
        },
    )
    put(
        "playerCertificateKeys",
        buildJsonArray {
            add(buildJsonObject { put("publicKey", MinecraftChatCryptoFixtures.publicKeyBase64) })
        },
    )
}

private fun privateKeyPem(): String = """
    -----BEGIN RSA PRIVATE KEY-----
    ${MinecraftChatCryptoFixtures.privateKeyBase64}
    -----END RSA PRIVATE KEY-----
""".trimIndent()

private fun publicKeyPem(): String = """
    -----BEGIN RSA PUBLIC KEY-----
    ${MinecraftChatCryptoFixtures.publicKeyBase64}
    -----END RSA PUBLIC KEY-----
""".trimIndent()

private fun MockRequestHandleScope.respondProfileKeyJson(body: kotlinx.serialization.json.JsonObject) = respond(
    content = body.toString(),
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private val PROFILE_ID = Uuid.parse("12345678-1234-5678-9abc-def012345678")
private const val PROFILE_KEY_EXPIRY: Long = 1_800_000_000_123
private const val PROFILE_KEY_REFRESH: Long = 1_700_000_000_000
