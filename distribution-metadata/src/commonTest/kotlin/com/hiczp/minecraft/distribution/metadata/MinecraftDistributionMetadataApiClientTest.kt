package com.hiczp.minecraft.distribution.metadata

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MinecraftDistributionMetadataApiClientTest {
    @Test
    fun resolvesTheModernMetadataGraphWithTheCallerJsonPlugin() = runTest {
        val apiFixture = ApiFixture()
        val requests = mutableListOf<HttpRequestData>()
        val mockEngine = MockEngine { httpRequestData ->
            requests += httpRequestData
            when (httpRequestData.url.encodedPath) {
                VERSION_MANIFEST_PATH -> respondBytes(apiFixture.versionManifestBytes)
                apiFixture.versionMetadataPath -> respondBytes(apiFixture.versionMetadataBytes)
                apiFixture.assetIndexPath -> respondBytes(apiFixture.assetIndexBytes)
                JAVA_RUNTIME_CATALOG_PATH -> respondBytes(apiFixture.javaRuntimeCatalogBytes)
                apiFixture.javaRuntimeManifestPath -> respondBytes(apiFixture.javaRuntimeManifestBytes)
                AFTER_API_PATH -> respond("still open")
                else -> error("Unexpected request ${httpRequestData.url}")
            }
        }

        createTestHttpClient(mockEngine).use { httpClient ->
            val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(httpClient)
            val minecraftVersionManifest = minecraftDistributionMetadataApi.versionManifest()
            val minecraftVersionReference = minecraftVersionManifest.versions.single()
            val minecraftVersionMetadata =
                minecraftDistributionMetadataApi.versionMetadata(minecraftVersionReference.url)
            val minecraftAssetIndex =
                minecraftDistributionMetadataApi.assetIndex(minecraftVersionMetadata.assetIndex.url)
            val minecraftJavaRuntimeCatalog = minecraftDistributionMetadataApi.javaRuntimeCatalog()
            val minecraftJavaRuntimeManifestReference = minecraftJavaRuntimeCatalog.platforms
                .getValue(TEST_RUNTIME_PLATFORM)
                .getValue(minecraftVersionMetadata.javaVersion.component)
                .single()
                .manifest
            val minecraftJavaRuntimeManifest =
                minecraftDistributionMetadataApi.javaRuntimeManifest(minecraftJavaRuntimeManifestReference.url)

            assertEquals(TEST_VERSION_ID, minecraftVersionManifest.latest.release)
            assertEquals(TEST_VERSION_ID, minecraftVersionMetadata.id)
            assertEquals(apiFixture.assetObject, minecraftAssetIndex.objects.getValue(TEST_ASSET_PATH))
            assertIs<MinecraftJavaRuntimeFile.File>(
                minecraftJavaRuntimeManifest.files.getValue(TEST_RUNTIME_FILE_PATH),
            )
            assertEquals("still open", httpClient.get("https://example.test$AFTER_API_PATH").bodyAsText())
        }

        assertEquals(
            listOf(
                VERSION_MANIFEST_PATH,
                apiFixture.versionMetadataPath,
                apiFixture.assetIndexPath,
                JAVA_RUNTIME_CATALOG_PATH,
                apiFixture.javaRuntimeManifestPath,
                AFTER_API_PATH,
            ),
            requests.map { httpRequestData -> httpRequestData.url.encodedPath },
        )
        requests.take(5).forEach { httpRequestData ->
            assertEquals(HttpMethod.Get, httpRequestData.method)
            assertEquals(ContentType.Application.Json.toString(), httpRequestData.headers[HttpHeaders.Accept])
        }
    }

    @Test
    fun usesTheConfiguredBaseUrlForFixedRootOperations() = runTest {
        val apiFixture = ApiFixture()
        val requestedUrls = mutableListOf<Url>()
        val mockEngine = MockEngine { httpRequestData ->
            requestedUrls += httpRequestData.url
            when (httpRequestData.url.encodedPath) {
                "$TEST_BASE_PATH$VERSION_MANIFEST_PATH" -> respondBytes(apiFixture.versionManifestBytes)
                "$TEST_BASE_PATH$JAVA_RUNTIME_CATALOG_PATH" -> respondBytes(apiFixture.javaRuntimeCatalogBytes)
                else -> error("Unexpected request ${httpRequestData.url}")
            }
        }

        createTestHttpClient(mockEngine).use { httpClient ->
            val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(
                httpClient = httpClient,
                pistonMetaBaseUrl = TEST_PISTON_META_BASE_URL,
            )

            minecraftDistributionMetadataApi.versionManifest()
            minecraftDistributionMetadataApi.javaRuntimeCatalog()
        }

        assertEquals(
            listOf(
                Url("$TEST_PISTON_META_BASE_URL${VERSION_MANIFEST_PATH.removePrefix("/")}"),
                Url("$TEST_PISTON_META_BASE_URL${JAVA_RUNTIME_CATALOG_PATH.removePrefix("/")}"),
            ),
            requestedUrls,
        )
    }

    @Test
    fun followsDynamicUrlsWithoutAddingMetadataValidation() = runTest {
        val apiFixture = ApiFixture()
        var requestedUrl: Url? = null
        val mockEngine = MockEngine { httpRequestData ->
            requestedUrl = httpRequestData.url
            respondBytes(apiFixture.versionMetadataBytes)
        }
        createTestHttpClient(mockEngine).use { httpClient ->
            val url = "http://example.test/custom/version.json"
            val minecraftVersionMetadata = MinecraftDistributionMetadataApiClient(httpClient).versionMetadata(url)

            assertEquals(TEST_VERSION_ID, minecraftVersionMetadata.id)
            assertEquals(Url(url), requestedUrl)
        }
    }

    @Test
    fun honorsCallerResponseValidation() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "sensitive response body",
                status = HttpStatusCode.BadGateway,
            )
        }
        createTestHttpClient(mockEngine, expectSuccessResponses = true).use { httpClient ->
            val failure = assertFailsWith<ResponseException> {
                MinecraftDistributionMetadataApiClient(httpClient).versionManifest()
            }
            assertEquals(HttpStatusCode.BadGateway, failure.response.status)
        }
    }

    @Test
    fun propagatesCancellationUnchanged() = runTest {
        val expectedCancellation = CancellationException("cancelled")
        createTestHttpClient(MockEngine { throw expectedCancellation }).use { httpClient ->
            val actualCancellation = assertFailsWith<CancellationException> {
                MinecraftDistributionMetadataApiClient(httpClient).versionManifest()
            }
            assertEquals(expectedCancellation.message, actualCancellation.message)
        }
    }
}

private class ApiFixture {
    val assetObject = MinecraftAssetObject(
        hash = testSha1('a'),
        size = 17,
    )
    private val minecraftAssetIndex = MinecraftAssetIndex(
        objects = mapOf(TEST_ASSET_PATH to assetObject),
    )
    val assetIndexBytes = TEST_JSON.encodeToString(minecraftAssetIndex).encodeToByteArray()
    private val minecraftAssetIndexReference = MinecraftAssetIndexReference(
        id = "assets",
        sha1 = testSha1('1'),
        size = assetIndexBytes.size.toLong(),
        totalSize = assetObject.size,
        url = metadataUrl(testSha1('1'), "assets.json"),
    )
    val assetIndexPath = Url(minecraftAssetIndexReference.url).encodedPath

    private val minecraftJavaRuntimeManifest = MinecraftJavaRuntimeManifest(
        files = mapOf(
            TEST_RUNTIME_FILE_PATH to MinecraftJavaRuntimeFile.File(
                downloads = MinecraftJavaRuntimeFileDownloads(
                    raw = MinecraftDownload(testSha1('b'), 23, "https://piston-data.mojang.com/runtime"),
                ),
                executable = true,
            ),
            "runtime/lib" to MinecraftJavaRuntimeFile.Directory,
            "runtime/link" to MinecraftJavaRuntimeFile.Link("../target"),
        ),
    )
    val javaRuntimeManifestBytes = TEST_JSON.encodeToString(minecraftJavaRuntimeManifest).encodeToByteArray()
    private val minecraftJavaRuntimeManifestReference = MinecraftJavaRuntimeManifestReference(
        sha1 = testSha1('2'),
        size = javaRuntimeManifestBytes.size.toLong(),
        url = metadataUrl(testSha1('2'), "runtime-manifest.json"),
    )
    val javaRuntimeManifestPath = Url(minecraftJavaRuntimeManifestReference.url).encodedPath

    private val minecraftVersionMetadata = MinecraftVersionMetadata(
        arguments = MinecraftArguments(
            defaultUserJvm = listOf(
                MinecraftArgument.Expanded(
                    value = MinecraftArgumentValue.Multiple(listOf("-Xms2G", "-Xmx4G")),
                ),
            ),
            game = listOf(MinecraftArgument.Literal("--demo")),
            jvm = listOf(
                MinecraftArgument.Expanded(
                    rules = listOf(
                        MinecraftRule(
                            action = "allow",
                            os = MinecraftOperatingSystemRule(name = "windows"),
                        ),
                    ),
                    value = MinecraftArgumentValue.Single("-Xss1M"),
                ),
            ),
        ),
        assetIndex = minecraftAssetIndexReference,
        assets = minecraftAssetIndexReference.id,
        complianceLevel = 1,
        downloads = MinecraftVersionDownloads(
            client = MinecraftDownload(testSha1('c'), 31, "https://piston-data.mojang.com/client.jar"),
            server = MinecraftDownload(testSha1('d'), 37, "https://piston-data.mojang.com/server.jar"),
        ),
        id = TEST_VERSION_ID,
        javaVersion = MinecraftJavaVersion(TEST_RUNTIME_COMPONENT, 25),
        libraries = listOf(
            MinecraftLibrary(
                downloads = MinecraftLibraryDownloads(
                    MinecraftLibraryDownload(
                        path = "example/library.jar",
                        sha1 = testSha1('e'),
                        size = 41,
                        url = "https://libraries.minecraft.net/example/library.jar",
                    ),
                ),
                name = "example:library:1",
            ),
        ),
        logging = MinecraftLoggingConfiguration(
            client = MinecraftClientLoggingConfiguration(
                argument = "-Dlog.configuration=\${path}",
                file = MinecraftLoggingFile(
                    id = "client.xml",
                    sha1 = testSha1('f'),
                    size = 43,
                    url = "https://piston-data.mojang.com/client.xml",
                ),
                type = "log4j2-xml",
            ),
        ),
        mainClass = "example.Main",
        minimumLauncherVersion = 21,
        releaseTime = TEST_TIME,
        time = TEST_TIME,
        type = "release",
    )
    val versionMetadataBytes = TEST_JSON.encodeToString(minecraftVersionMetadata).encodeToByteArray()
    val versionReference = MinecraftVersionReference(
        id = TEST_VERSION_ID,
        type = "release",
        url = metadataUrl(testSha1('3'), "version.json"),
        time = TEST_TIME,
        releaseTime = TEST_TIME,
        sha1 = testSha1('3'),
        complianceLevel = 1,
    )
    val versionMetadataPath = Url(versionReference.url).encodedPath

    private val minecraftVersionManifest = MinecraftVersionManifest(
        latest = MinecraftLatestVersions(TEST_VERSION_ID, TEST_VERSION_ID),
        versions = listOf(versionReference),
    )
    val versionManifestBytes = TEST_JSON.encodeToString(minecraftVersionManifest).encodeToByteArray()

    private val minecraftJavaRuntimeCatalog = MinecraftJavaRuntimeCatalog(
        platforms = mapOf(
            TEST_RUNTIME_PLATFORM to mapOf(
                TEST_RUNTIME_COMPONENT to listOf(
                    MinecraftJavaRuntimeEntry(
                        availability = MinecraftJavaRuntimeAvailability(group = 1, progress = 100),
                        manifest = minecraftJavaRuntimeManifestReference,
                        version = MinecraftJavaRuntimeVersion(name = "25", released = TEST_TIME),
                    ),
                ),
            ),
        ),
    )
    val javaRuntimeCatalogBytes = TEST_JSON.encodeToString(minecraftJavaRuntimeCatalog).encodeToByteArray()
}

private fun metadataUrl(sha1: String, fileName: String): String =
    "https://piston-meta.mojang.com/v1/packages/$sha1/$fileName"

private fun testSha1(character: Char): String = character.toString().repeat(40)

private fun createTestHttpClient(
    mockEngine: MockEngine,
    expectSuccessResponses: Boolean = false,
): HttpClient = HttpClient(mockEngine) {
    expectSuccess = expectSuccessResponses
    install(ContentNegotiation) {
        json(TEST_JSON)
    }
}

private fun MockRequestHandleScope.respondBytes(
    bytes: ByteArray,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = bytes,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

private val TEST_JSON = Json {
    ignoreUnknownKeys = true
}
private const val VERSION_MANIFEST_PATH = "/mc/game/version_manifest_v2.json"
private const val JAVA_RUNTIME_CATALOG_PATH =
    "/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"
private const val TEST_BASE_PATH = "/metadata"
private const val TEST_PISTON_META_BASE_URL = "https://mirror.example.test$TEST_BASE_PATH/"
private const val AFTER_API_PATH = "/after"
private const val TEST_VERSION_ID = "modern-version"
private const val TEST_TIME = "2026-01-01T00:00:00+00:00"
private const val TEST_ASSET_PATH = "minecraft/sounds/example.ogg"
private const val TEST_RUNTIME_PLATFORM = "windows-x64"
private const val TEST_RUNTIME_COMPONENT = "java-runtime-epsilon"
private const val TEST_RUNTIME_FILE_PATH = "runtime/bin/java.exe"
