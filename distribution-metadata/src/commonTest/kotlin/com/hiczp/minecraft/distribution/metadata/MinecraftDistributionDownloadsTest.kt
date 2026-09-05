package com.hiczp.minecraft.distribution.metadata

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class MinecraftDistributionDownloadsTest {
    @Test
    fun preparesALazyStreamingRequestWithTheCallerClient() = runTest {
        val prefix = byteArrayOf(0, -1, 1)
        val suffix = byteArrayOf(2, -2, 0)
        val byteChannel = ByteChannel(autoFlush = true)
        val prefixRead = CompletableDeferred<Unit>()
        val producer = backgroundScope.launch {
            byteChannel.writeFully(prefix)
            prefixRead.await()
            byteChannel.writeFully(suffix)
            byteChannel.flushAndClose()
        }
        val requests = mutableListOf<HttpRequestData>()
        val mockEngine = MockEngine { httpRequestData ->
            requests += httpRequestData
            if (httpRequestData.url.encodedPath == "/after") {
                respond("still open")
            } else {
                respond(
                    content = byteChannel,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                )
            }
        }
        HttpClient(mockEngine) {
            defaultRequest { header("X-Caller", "configured") }
        }.use { httpClient ->
            val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(
                httpClient = httpClient,
                pistonMetaBaseUrl = "https://metadata.example.test/base/",
            )
            val httpStatement = minecraftDistributionMetadataApi.download(DOWNLOAD_URL)
            assertTrue(requests.isEmpty())

            val receivedSuffix = httpStatement.execute { httpResponse ->
                assertEquals(HttpStatusCode.OK, httpResponse.status)
                assertEquals(ContentType.Application.OctetStream, httpResponse.contentType())
                val byteReadChannel = httpResponse.bodyAsChannel()
                val receivedPrefix = ByteArray(prefix.size)
                byteReadChannel.readFully(receivedPrefix)
                assertContentEquals(prefix, receivedPrefix)
                prefixRead.complete(Unit)
                byteReadChannel.readRemaining().readByteArray()
            }
            assertContentEquals(suffix, receivedSuffix)
            producer.join()
            assertEquals("still open", httpClient.get("http://download.example.test/after").bodyAsText())
        }

        assertEquals(listOf(Url(DOWNLOAD_URL), Url("http://download.example.test/after")), requests.map { it.url })
        requests.forEach { httpRequestData ->
            assertEquals(HttpMethod.Get, httpRequestData.method)
            assertEquals("configured", httpRequestData.headers["X-Caller"])
        }
    }

    @Test
    fun extensionsDeriveAssetUrlsAndIgnoreDescriptorIntegrityFields() = runTest {
        val bytes = byteArrayOf(0, -1, 42)
        val requestedUrls = mutableListOf<Url>()
        val mockEngine = MockEngine { httpRequestData ->
            requestedUrls += httpRequestData.url
            respond(bytes)
        }
        HttpClient(mockEngine).use { httpClient ->
            val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(httpClient)
            val httpStatements = listOf(
                minecraftDistributionMetadataApi.download(
                    MinecraftDownload(sha1 = "not-a-sha1", size = -1, url = DOWNLOAD_URL),
                ),
                minecraftDistributionMetadataApi.download(
                    MinecraftLibraryDownload("example/library.jar", "UNVERIFIED", -1, LIBRARY_URL).toDownload(),
                ),
                minecraftDistributionMetadataApi.download(
                    MinecraftLoggingFile("client.xml", "UNVERIFIED", -1, LOGGING_URL).toDownload(),
                ),
                minecraftDistributionMetadataApi.downloadAsset(ASSET_HASH),
                minecraftDistributionMetadataApi.download(MinecraftAssetObject(hash = ASSET_HASH, size = -1)),
                minecraftDistributionMetadataApi.downloadAsset("A"),
            )
            assertTrue(requestedUrls.isEmpty())

            httpStatements.forEach { httpStatement ->
                val receivedBytes = httpStatement.execute { httpResponse ->
                    httpResponse.bodyAsChannel().readRemaining().readByteArray()
                }
                assertContentEquals(bytes, receivedBytes)
            }
        }

        assertEquals(
            listOf(
                Url(DOWNLOAD_URL),
                Url(LIBRARY_URL),
                Url(LOGGING_URL),
                Url(ASSET_URL),
                Url(ASSET_URL),
                Url("https://resources.download.minecraft.net/a/a"),
            ),
            requestedUrls,
        )
    }

    @Test
    fun appliesCallerResponseValidationAtExecutionWithoutRetries() = runTest {
        for (expectSuccessResponses in listOf(false, true)) {
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                respond("unavailable", HttpStatusCode.BadGateway)
            }
            HttpClient(mockEngine) {
                expectSuccess = expectSuccessResponses
            }.use { httpClient ->
                val httpStatement = MinecraftDistributionMetadataApiClient(httpClient).download(DOWNLOAD_URL)
                assertEquals(0, requestCount)

                if (expectSuccessResponses) {
                    val responseException = assertFailsWith<ResponseException> {
                        httpStatement.execute { error("Response validation should run before the consumer") }
                    }
                    assertEquals(HttpStatusCode.BadGateway, responseException.response.status)
                } else {
                    httpStatement.execute { httpResponse ->
                        assertEquals(HttpStatusCode.BadGateway, httpResponse.status)
                        assertEquals("unavailable", httpResponse.bodyAsText())
                    }
                }
                assertEquals(1, requestCount)
            }
        }
    }

    @Test
    fun closesTheResponseWhenTheConsumerStopsEarly() = runTest {
        val byteChannel = ByteChannel(autoFlush = true)
        byteChannel.writeFully(byteArrayOf(1, 2))
        HttpClient(MockEngine { respond(byteChannel) }).use { httpClient ->
            MinecraftDistributionMetadataApiClient(httpClient).download(DOWNLOAD_URL).execute { httpResponse ->
                assertEquals(1.toByte(), httpResponse.bodyAsChannel().readByte())
            }

            assertTrue(byteChannel.isClosedForRead)
            assertTrue(byteChannel.isClosedForWrite)
        }
    }

    @Test
    fun preservesConsumerCancellationAndClosesTheResponse() = runTest {
        val expectedCancellation = CancellationException("download cancelled")
        val byteChannel = ByteChannel(autoFlush = true)
        byteChannel.writeFully(byteArrayOf(1, 2))
        HttpClient(MockEngine { respond(byteChannel) }).use { httpClient ->
            val actualCancellation = assertFailsWith<CancellationException> {
                MinecraftDistributionMetadataApiClient(httpClient).download(DOWNLOAD_URL).execute { httpResponse ->
                    httpResponse.bodyAsChannel().readByte()
                    throw expectedCancellation
                }
            }

            assertSame(expectedCancellation, actualCancellation)
            assertTrue(byteChannel.isClosedForRead)
            assertTrue(byteChannel.isClosedForWrite)
        }
    }
}

private const val DOWNLOAD_URL = "http://download.example.test/files/content.bin?source=caller"
private const val LIBRARY_URL = "http://download.example.test/files/library.jar?source=library"
private const val LOGGING_URL = "http://download.example.test/files/client.xml?source=logging"
private const val ASSET_HASH = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
private const val ASSET_URL = "https://resources.download.minecraft.net/ab/abcdef0123456789abcdef0123456789abcdef01"
