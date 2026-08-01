package com.hiczp.minecraft.test

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.*

class KtorArtifactDownloaderTest {
    @Test
    fun retriesServerErrorsThroughKtorPlugin() = runTest {
        var requests = 0
        configuredClient(
            MockEngine {
                requests++
                if (requests == 1) {
                    respond("retry", HttpStatusCode.ServiceUnavailable)
                } else {
                    respond("artifact", HttpStatusCode.OK)
                }
            },
        ).use { client ->
            val content = KtorArtifactDownloader(client)
                .getBytes("https://example.invalid/artifact")

            assertContentEquals("artifact".encodeToByteArray(), content)
            assertEquals(2, requests)
        }
    }

    @Test
    fun retriesFailureWhileConsumingResponseBody() = runTest {
        val content = "complete artifact".encodeToByteArray()
        var requests = 0
        configuredClient(
            MockEngine {
                requests++
                if (requests == 1) {
                    respond(
                        FailingBodyChannel("partial".encodeToByteArray()),
                        HttpStatusCode.OK,
                    )
                } else {
                    respond(content, HttpStatusCode.OK)
                }
            },
        ).use { client ->
            val downloader = KtorArtifactDownloader(client)
            val directory = createUniqueDirectory(
                Path("build", "test-downloads"),
                "retry-body-",
            )
            val destination = Path(directory, "artifact.bin")

            downloader.ensureDownload(
                url = "https://example.invalid/artifact",
                destination = destination,
                expectedSize = content.size.toLong(),
                digestAlgorithm = "SHA-1",
                expectedDigest = content.sha1(),
            )

            assertEquals(2, requests)
            assertContentEquals(content, destination.readBytes())
            assertNoTemporaryDownloads(directory)
        }
    }

    @Test
    fun streamsVerifiedArtifactAndRemovesFailedTemporaryFile() = runTest {
        val content = "verified artifact".encodeToByteArray()
        configuredClient(
            MockEngine {
                respond("verified artifact", HttpStatusCode.OK)
            },
        ).use { client ->
            val downloader = KtorArtifactDownloader(client)
            val directory = createUniqueDirectory(
                Path("build", "test-downloads"),
                "verified-",
            )
            val destination = Path(directory, "artifact.bin")

            downloader.ensureDownload(
                url = "https://example.invalid/artifact",
                destination = destination,
                expectedSize = content.size.toLong(),
                digestAlgorithm = "SHA-1",
                expectedDigest = content.sha1(),
            )
            assertContentEquals(content, destination.readBytes())

            assertFailsWith<IllegalStateException> {
                downloader.ensureDownload(
                    url = "https://example.invalid/invalid",
                    destination = Path(directory, "invalid.bin"),
                    expectedSize = content.size.toLong(),
                    digestAlgorithm = "SHA-1",
                    expectedDigest = ByteArray(20).toHexString(),
                )
            }
            assertNoTemporaryDownloads(directory)
        }
    }

    @Test
    fun concurrentVerifiedDownloadsConvergeOnOneImmutableArtifact() = runTest {
        val content = "shared verified artifact".encodeToByteArray()
        val bothRequestsStarted = CompletableDeferred<Unit>()
        var requests = 0
        configuredClient(
            MockEngine {
                requests++
                if (requests == 2) {
                    bothRequestsStarted.complete(Unit)
                } else {
                    bothRequestsStarted.await()
                }
                respond(content, HttpStatusCode.OK)
            },
        ).use { client ->
            val downloader = KtorArtifactDownloader(client)
            val directory = createUniqueDirectory(
                Path("build", "test-downloads"),
                "concurrent-",
            )
            val destination = Path(directory, "artifact.bin")

            List(2) {
                async {
                    downloader.ensureDownload(
                        url = "https://example.invalid/artifact",
                        destination = destination,
                        expectedSize = content.size.toLong(),
                        digestAlgorithm = "SHA-256",
                        expectedDigest = content.sha256(),
                    )
                }
            }.awaitAll()

            assertEquals(2, requests)
            assertContentEquals(content, destination.readBytes())
            assertNoTemporaryDownloads(directory)
        }
    }

    @Test
    fun failedConcurrentDownloadReusesArtifactCommittedByPeer() = runTest {
        val content = "shared verified artifact".encodeToByteArray()
        val failingRequestStarted = CompletableDeferred<Unit>()
        val peerCommitted = CompletableDeferred<Unit>()
        val directory = createUniqueDirectory(
            Path("build", "test-downloads"),
            "concurrent-failure-",
        )
        val destination = Path(directory, "artifact.bin")
        configuredClient(
            MockEngine {
                failingRequestStarted.await()
                respond(content, HttpStatusCode.OK)
            },
        ).use { successfulClient ->
            configuredClient(
                MockEngine {
                    failingRequestStarted.complete(Unit)
                    peerCommitted.await()
                    throw IOException("simulated concurrent network failure")
                },
            ).use { failingClient ->
                val successfulDownload = async {
                    KtorArtifactDownloader(successfulClient).ensureDownload(
                        url = "https://example.invalid/artifact",
                        destination = destination,
                        expectedSize = content.size.toLong(),
                        digestAlgorithm = "SHA-256",
                        expectedDigest = content.sha256(),
                    ).also { peerCommitted.complete(Unit) }
                }
                val convergedDownload = async {
                    KtorArtifactDownloader(failingClient).ensureDownload(
                        url = "https://example.invalid/artifact",
                        destination = destination,
                        expectedSize = content.size.toLong(),
                        digestAlgorithm = "SHA-256",
                        expectedDigest = content.sha256(),
                    )
                }

                assertTrue(successfulDownload.await())
                assertFalse(convergedDownload.await())
            }
        }

        assertContentEquals(content, destination.readBytes())
        assertNoTemporaryDownloads(directory)
    }

    private fun configuredClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            configureVerifiedDownloads("minecraft-protocol tests")
        }

    private fun assertNoTemporaryDownloads(directory: Path) {
        assertTrue(
            SystemFileSystem.list(directory)
                .none {
                    it.name.endsWith(".download") ||
                            it.name.endsWith(".commit-lock")
                },
        )
    }
}

@OptIn(InternalAPI::class)
private class FailingBodyChannel(
    initialContent: ByteArray,
) : ByteReadChannel {
    private val failure = IOException("simulated response body failure")
    private val content = Buffer().apply { write(initialContent) }

    override val closedCause: Throwable = failure

    override val isClosedForRead: Boolean
        get() = false

    override val readBuffer: Source = content

    override suspend fun awaitContent(min: Int): Boolean {
        if (content.size >= min) return true
        throw failure
    }

    override fun cancel(cause: Throwable?) = Unit
}
