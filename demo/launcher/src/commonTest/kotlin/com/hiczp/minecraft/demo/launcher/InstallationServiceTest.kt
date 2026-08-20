package com.hiczp.minecraft.demo.launcher

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallationServiceTest {
    @Test
    fun loadsManifestThroughConfiguredKtorClient() = runTest {
        val fixture = InstallFixture()

        val manifest = fixture.service.loadManifest()

        assertEquals(listOf(fixture.entry), manifest.versions)
        assertEquals(1, fixture.countRequests(fixture.manifestUrl))
        fixture.close()
    }

    @Test
    fun miniatureInstallIsIsolatedIndexedLastAndOverwritten() = runTest {
        val fixture = InstallFixture()

        val first = fixture.service.prepareInstallation(fixture.entry)
        assertTrue(fixture.store.loadInstalled().installations.isEmpty())
        assertEquals(InstallProgress(totalFiles = 3), fixture.service.progress.value)
        fixture.service.install(first)
        assertEquals(InstallProgress(completedFiles = 3, totalFiles = 3), fixture.service.progress.value)

        val second = fixture.service.prepareInstallation(fixture.entry)
        fixture.service.install(second)

        val gameRoot = fixture.store.gameRoot("demo")
        assertTrue(fixture.fileSystem.exists(gameRoot / "client.jar"))
        assertTrue(fixture.fileSystem.exists(gameRoot / "libraries/example/library.jar"))
        assertTrue(fixture.fileSystem.exists(gameRoot / "assets/indexes/assets-id.json"))
        val assetPath = gameRoot / "assets/objects/${fixture.assetHash.take(2)}/${fixture.assetHash}"
        assertTrue(fixture.fileSystem.exists(assetPath))
        val expectedInstallations = listOf(InstalledVersion("demo", fixture.platform.platformKey))
        assertEquals(expectedInstallations, fixture.store.loadInstalled().installations)
        assertEquals(2, fixture.countRequests(fixture.metadataUrl))
        assertEquals(2, fixture.countRequests(fixture.clientUrl))
        assertEquals(2, fixture.countRequests(fixture.libraryUrl))
        assertEquals(2, fixture.countRequests(fixture.assetIndexUrl))
        assertEquals(2, fixture.countRequests(fixture.assetUrl))
        assertFalse(fixture.fileSystem.listRecursively(fixture.root).any { it.name.endsWith(".download") })
        fixture.close()
    }

    @Test
    fun failedResourceDownloadRetriesUntilItSucceeds() = runTest {
        val fixture = InstallFixture(corruptClientAttempts = 2)
        val prepared = fixture.service.prepareInstallation(fixture.entry)

        fixture.service.install(prepared)

        assertEquals(3, fixture.countRequests(fixture.clientUrl))
        assertEquals(InstallProgress(completedFiles = 3, totalFiles = 3), fixture.service.progress.value)
        assertEquals(1, fixture.store.loadInstalled().installations.size)
        assertFalse(fixture.fileSystem.listRecursively(fixture.root).any { it.name.endsWith(".download") })
        fixture.close()
    }
}

internal class InstallFixture(
    corruptClientAttempts: Int = 0,
    private val blockContentDownloads: Boolean = false,
) {
    val root = "/launcher root".toPath()
    val fileSystem = FakeFileSystem().also { it.createDirectories(root) }
    val store = LauncherStore(fileSystem, root)
    val platform = LauncherPlatform("windows", "x86_64", ";", "windows-x86_64")
    val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    val metadataUrl = "https://fixture/version.json"
    val clientUrl = "https://fixture/client.jar"
    val libraryUrl = "https://fixture/library.jar"
    val assetIndexUrl = "https://fixture/assets.json"
    private val clientBytes = "client-bytes".encodeToByteArray()
    private val libraryBytes = "library-bytes".encodeToByteArray()
    private val assetBytes = "asset-bytes".encodeToByteArray()
    val assetHash = assetBytes.sha1()
    val assetUrl = "https://resources.download.minecraft.net/${assetHash.take(2)}/$assetHash"
    private val assetIndexBytes = launcherJson.encodeToString(
        AssetIndex(mapOf("minecraft/sounds/test.ogg" to AssetObject(assetHash, assetBytes.size.toLong()))),
    ).encodeToByteArray()
    private val metadataBytes = launcherJson.encodeToString(
        VersionMetadata(
            id = "demo",
            type = "release",
            mainClass = "example.Main",
            assets = "assets-id",
            assetIndex = AssetIndexDownload(
                id = "assets-id",
                url = assetIndexUrl,
                sha1 = assetIndexBytes.sha1(),
                size = assetIndexBytes.size.toLong(),
            ),
            downloads = VersionDownloads(
                Download(clientUrl, clientBytes.sha1(), clientBytes.size.toLong()),
            ),
            libraries = listOf(
                MojangLibrary(
                    name = "example:library:1",
                    downloads = MojangLibrary.Downloads(
                        artifact = Download(
                            libraryUrl,
                            libraryBytes.sha1(),
                            libraryBytes.size.toLong(),
                            "example/library.jar",
                        ),
                    ),
                ),
            ),
            arguments = MojangArguments(
                game = listOf(MojangArgument.Literal("--username"), MojangArgument.Literal("${'$'}{auth_player_name}")),
                jvm = listOf(MojangArgument.Literal("-cp"), MojangArgument.Literal("${'$'}{classpath}")),
            ),
        ),
    ).encodeToByteArray()
    val entry = VersionEntry(
        id = "demo",
        type = "release",
        url = metadataUrl,
        time = "now",
        releaseTime = "now",
        sha1 = metadataBytes.sha1(),
    )
    private val manifestBytes = launcherJson.encodeToString(
        VersionManifest(
            latest = VersionManifest.Latest(release = entry.id, snapshot = entry.id),
            versions = listOf(entry),
        ),
    ).encodeToByteArray()
    private val requestMutex = Mutex()
    private val requests = mutableListOf<String>()
    private var remainingCorruptClientAttempts = corruptClientAttempts
    val activeContentDownloads = MutableStateFlow(0)
    private val engine = MockEngine { request ->
        val url = request.url.toString()
        val corruptClient = requestMutex.withLock {
            requests += url
            if (url == clientUrl && remainingCorruptClientAttempts > 0) {
                remainingCorruptClientAttempts--
                true
            } else {
                false
            }
        }
        if (blockContentDownloads && (url == clientUrl || url == libraryUrl || url == assetUrl)) {
            activeContentDownloads.update { it + 1 }
            try {
                awaitCancellation()
            } finally {
                activeContentDownloads.update { it - 1 }
            }
        }
        val content = when (url) {
            manifestUrl -> manifestBytes
            metadataUrl -> metadataBytes
            clientUrl -> if (corruptClient) "corrupt".encodeToByteArray() else clientBytes
            libraryUrl -> libraryBytes
            assetIndexUrl -> assetIndexBytes
            assetUrl -> assetBytes
            else -> error("Unexpected request: $url")
        }
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = if (url == manifestUrl || url == metadataUrl) {
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            } else {
                headersOf(HttpHeaders.ContentLength, content.size.toString())
            },
        )
    }
    val client = HttpClient(engine) { configureLauncherHttpClient() }
    val service = InstallationService(createMojangApi(client), fileSystem, store, platform)

    suspend fun countRequests(url: String): Int = requestMutex.withLock { requests.count { it == url } }

    fun close() {
        client.close()
    }
}

private fun ByteArray.sha1(): String = toByteString().sha1().hex()
