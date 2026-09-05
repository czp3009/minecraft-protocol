package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.distribution.metadata.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.fakefilesystem.FakeFileSystem
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.encoding.Base64
import kotlin.test.*

class OfficialAssetRepositoryTest {
    @Test
    fun metadataReferencesLeadToVerifiedClientResources() = runTest {
        val officialAssetFixture = OfficialAssetFixture(this)
        try {
            val assetLoadStatus =
                officialAssetFixture.officialAssetRepository.status.first { it !is AssetLoadStatus.Loading }
            val ready = assertIs<AssetLoadStatus.Ready>(assetLoadStatus)
            val loadedOfficialAssets = officialAssetFixture.officialAssetRepository.awaitLoaded()

            assertEquals(officialAssetFixture.clientSha1, ready.assetRevision)
            assertEquals(officialAssetFixture.resources.keys, loadedOfficialAssets.resources.keys)
            officialAssetFixture.resources.forEach { (path, bytes) ->
                assertContentEquals(bytes, loadedOfficialAssets.resources[path])
            }
            assertEquals(officialAssetFixture.expectedRequests, officialAssetFixture.requests)
            assertTrue(officialAssetFixture.fakeFileSystem.list(FileSystem.SYSTEM_TEMPORARY_DIRECTORY).isEmpty())
        } finally {
            officialAssetFixture.officialAssetRepository.close()
        }
    }

    @Test
    fun corruptClientJarFailsBeforeResourcePublication() = runTest {
        val officialAssetFixture = OfficialAssetFixture(this, corruptClient = true)
        try {
            val assetLoadStatus =
                officialAssetFixture.officialAssetRepository.status.first { it !is AssetLoadStatus.Loading }

            assertEquals(
                "The official client JAR SHA-1 does not match",
                assertIs<AssetLoadStatus.Failed>(assetLoadStatus).message
            )
            assertEquals(officialAssetFixture.expectedRequests, officialAssetFixture.requests)
            assertTrue(officialAssetFixture.fakeFileSystem.list(FileSystem.SYSTEM_TEMPORARY_DIRECTORY).isEmpty())
        } finally {
            officialAssetFixture.officialAssetRepository.close()
        }
    }
}

private class OfficialAssetFixture(coroutineScope: CoroutineScope, corruptClient: Boolean = false) {
    private val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private val metadataUrl = "https://fixture/version.json"
    private val clientUrl = "https://fixture/client.jar"
    val expectedRequests = listOf(manifestUrl, metadataUrl, clientUrl)
    val requests = mutableListOf<String>()
    val fakeFileSystem = FakeFileSystem().also { it.createDirectories(FileSystem.SYSTEM_TEMPORARY_DIRECTORY) }
    val resources = mapOf(
        "assets/minecraft/blockstates/stone.json" to """{"variants":{"":{"model":"minecraft:block/stone"}}}""".encodeToByteArray(),
        "assets/minecraft/models/block/stone.json" to """{"textures":{"particle":"minecraft:block/stone"}}""".encodeToByteArray(),
        "assets/minecraft/textures/block/stone.png" to Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII="),
    )
    private val clientJarBytes = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zipOutputStream ->
            resources.forEach { (path, bytes) ->
                zipOutputStream.putNextEntry(ZipEntry(path))
                zipOutputStream.write(bytes)
                zipOutputStream.closeEntry()
            }
            zipOutputStream.putNextEntry(ZipEntry("example/Main.class"))
            zipOutputStream.write(byteArrayOf(1, 2, 3))
            zipOutputStream.closeEntry()
        }
    }.toByteArray()
    val clientSha1 = clientJarBytes.toByteString().sha1().hex()
    private val minecraftVersionReference = MinecraftVersionReference(
        id = "selected",
        type = "release",
        url = metadataUrl,
        time = "now",
        releaseTime = "now",
        sha1 = "metadata-reference",
        complianceLevel = 1,
    )
    private val manifestJson = Json.encodeToJsonElement(
        MinecraftVersionManifest(MinecraftLatestVersions("selected", "selected"), listOf(minecraftVersionReference)),
    ).jsonObject
    private val manifestBody = JsonObject(manifestJson + ("futureField" to JsonPrimitive(true))).toString()
    private val metadataBody = Json.encodeToString(
        MinecraftVersionMetadata(
            arguments = MinecraftArguments(emptyList(), emptyList(), emptyList()),
            assetIndex = MinecraftAssetIndexReference("assets", "unused", 0, 0, "https://fixture/assets.json"),
            assets = "assets",
            complianceLevel = 1,
            downloads = MinecraftVersionDownloads(
                client = MinecraftDownload(clientSha1, clientJarBytes.size.toLong(), clientUrl),
                server = MinecraftDownload("unused", 0, "https://fixture/server.jar"),
            ),
            id = "embedded",
            javaVersion = MinecraftJavaVersion("fixture-runtime", 21),
            libraries = emptyList(),
            logging = MinecraftLoggingConfiguration(
                MinecraftClientLoggingConfiguration(
                    "unused",
                    MinecraftLoggingFile("client.xml", "unused", 0, "https://fixture/client.xml"),
                    "log4j2-xml",
                ),
            ),
            mainClass = "example.Main",
            minimumLauncherVersion = 1,
            releaseTime = "now",
            time = "now",
            type = "release",
        ),
    )
    private val httpClient = HttpClient(MockEngine { httpRequestData ->
        val url = httpRequestData.url.toString()
        requests += url
        when (url) {
            manifestUrl -> respond(manifestBody, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            metadataUrl -> respond(metadataBody, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            clientUrl -> respond(
                if (corruptClient) ByteArray(clientJarBytes.size) else clientJarBytes,
                headers = headersOf(HttpHeaders.ContentType, "application/java-archive"),
            )

            else -> error("Unexpected request: $url")
        }
    }) { configureOfficialAssetHttpClient() }
    val officialAssetRepository = OfficialAssetRepository(
        "selected",
        coroutineScope,
        KotlinLogging.logger("OfficialAssetRepositoryTest"),
        fakeFileSystem,
        httpClient,
    )
}
