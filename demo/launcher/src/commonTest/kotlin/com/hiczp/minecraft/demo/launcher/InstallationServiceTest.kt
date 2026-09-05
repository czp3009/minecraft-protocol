package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.*
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
import kotlin.test.*

class InstallationServiceTest {
    @Test
    fun loadsManifestThroughConfiguredKtorClient() = runTest {
        val installFixture = InstallFixture()

        val minecraftVersionManifest = installFixture.installationService.loadManifest()

        assertEquals(listOf(installFixture.minecraftVersionReference), minecraftVersionManifest.versions)
        assertEquals(1, installFixture.countRequests(installFixture.manifestUrl))
        installFixture.close()
    }

    @Test
    fun miniatureInstallIsIsolatedIndexedLastAndOverwritten() = runTest {
        val installFixture = InstallFixture()

        assertTrue(installFixture.launcherStore.loadInstalled().installations.isEmpty())
        installFixture.installationService.install(installFixture.minecraftVersionReference)
        assertEquals(
            InstallProgress(completedFiles = 4, totalFiles = 4),
            installFixture.installationService.progress.value
        )

        var stateWhenDownloadsStarted: InstalledState? = null
        installFixture.installationService.install(installFixture.minecraftVersionReference) { installedState ->
            stateWhenDownloadsStarted = installedState
        }

        val gameRoot = installFixture.launcherStore.gameRoot("demo")
        assertTrue(installFixture.fakeFileSystem.exists(gameRoot / "client.jar"))
        assertTrue(installFixture.fakeFileSystem.exists(gameRoot / "libraries/example/library.jar"))
        assertTrue(installFixture.fakeFileSystem.exists(gameRoot / "logging/client.xml"))
        assertTrue(installFixture.fakeFileSystem.exists(gameRoot / "assets/indexes/assets-id.json"))
        val assetPath = gameRoot / "assets/objects/${installFixture.assetHash.take(2)}/${installFixture.assetHash}"
        assertTrue(installFixture.fakeFileSystem.exists(assetPath))
        val expectedInstallations = listOf(InstalledVersion("demo"))
        assertTrue(stateWhenDownloadsStarted?.installations.orEmpty().isEmpty())
        assertEquals(expectedInstallations, installFixture.launcherStore.loadInstalled().installations)
        assertEquals(2, installFixture.countRequests(installFixture.metadataUrl))
        assertEquals(2, installFixture.countRequests(installFixture.clientUrl))
        assertEquals(2, installFixture.countRequests(installFixture.libraryUrl))
        assertEquals(2, installFixture.countRequests(installFixture.loggingUrl))
        assertEquals(2, installFixture.countRequests(installFixture.assetIndexUrl))
        assertEquals(2, installFixture.countRequests(installFixture.assetUrl))
        assertFalse(
            installFixture.fakeFileSystem.listRecursively(installFixture.root).any { it.name.endsWith(".download") })
        installFixture.close()
    }

    @Test
    fun failedResourceDownloadRetriesUntilItSucceeds() = runTest {
        val installFixture = InstallFixture(corruptClientAttempts = 2)

        installFixture.installationService.install(installFixture.minecraftVersionReference)

        assertEquals(3, installFixture.countRequests(installFixture.clientUrl))
        assertEquals(
            InstallProgress(completedFiles = 4, totalFiles = 4),
            installFixture.installationService.progress.value
        )
        assertEquals(1, installFixture.launcherStore.loadInstalled().installations.size)
        assertFalse(
            installFixture.fakeFileSystem.listRecursively(installFixture.root).any { it.name.endsWith(".download") })
        installFixture.close()
    }

    @Test
    fun manifestEntryIdOwnsTheInstallationIdentity() = runTest {
        val installFixture = InstallFixture(metadataVersionId = "embedded")

        val completedInstallation = installFixture.installationService.install(installFixture.minecraftVersionReference)

        assertEquals("demo", completedInstallation.minecraftVersionMetadata.id)
        assertEquals(listOf(InstalledVersion("demo")), completedInstallation.installedState.installations)
        assertTrue(installFixture.fakeFileSystem.exists(installFixture.launcherStore.gameRoot("demo") / "client.jar"))
        assertFalse(installFixture.fakeFileSystem.exists(installFixture.launcherStore.gameRoot("embedded")))
        installFixture.close()
    }

    @Test
    fun installedAssetIndexIsReadableAndCorruptBinaryFilesAreRejected() = runTest {
        val installFixture = InstallFixture()
        try {
            val completedInstallation =
                installFixture.installationService.install(installFixture.minecraftVersionReference)
            val installPlan =
                installFixture.installationService.validateInstallation(completedInstallation.minecraftVersionMetadata)
            val gameRoot = installFixture.launcherStore.gameRoot("demo")
            val minecraftAssetIndex = launcherJson.decodeFromString<MinecraftAssetIndex>(
                installFixture.fakeFileSystem.read(gameRoot / installPlan.assetIndexPath) { readUtf8() },
            )
            assertEquals(installFixture.assetHash, minecraftAssetIndex.objects.values.single().hash)
            assertEquals(1, installFixture.countRequests(installFixture.assetIndexUrl))

            installFixture.fakeFileSystem.write(gameRoot / "client.jar") { writeUtf8("corrupt") }
            assertFailsWith<IllegalArgumentException> {
                installFixture.installationService.validateInstallation(completedInstallation.minecraftVersionMetadata)
            }
        } finally {
            installFixture.close()
        }
    }
}

internal class InstallFixture(
    corruptClientAttempts: Int = 0,
    private val blockContentDownloads: Boolean = false,
    private val blockAssetIndexDownload: Boolean = false,
    metadataVersionId: String = "demo",
) {
    val root = "/launcher root".toPath()
    val fakeFileSystem = FakeFileSystem().also { it.createDirectories(root) }
    val launcherStore = LauncherStore(fakeFileSystem, root)
    val launcherPlatform = LauncherPlatform("windows", "x86_64", ";", "windows-x86_64", "10.0.22631")
    val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    val metadataUrl = "https://fixture/version.json"
    val clientUrl = "https://fixture/client.jar"
    val libraryUrl = "https://fixture/library.jar"
    val loggingUrl = "https://fixture/client.xml"
    val assetIndexUrl = "https://fixture/assets.json"
    private val clientBytes = "client-bytes".encodeToByteArray()
    private val libraryBytes = "library-bytes".encodeToByteArray()
    private val loggingBytes = "logging-bytes".encodeToByteArray()
    private val assetBytes = "asset-bytes".encodeToByteArray()
    val assetHash = assetBytes.toByteString().sha1().hex()
    val assetUrl = "https://resources.download.minecraft.net/${assetHash.take(2)}/$assetHash"
    private val assetIndexBytes = launcherJson.encodeToString(
        MinecraftAssetIndex(
            mapOf(
                "minecraft/sounds/test.ogg" to MinecraftAssetObject(
                    assetHash,
                    assetBytes.size.toLong()
                )
            )
        ),
    ).encodeToByteArray()
    private val metadataBytes = launcherJson.encodeToString(
        MinecraftVersionMetadata(
            id = metadataVersionId,
            type = "release",
            mainClass = "example.Main",
            assets = "assets-id",
            assetIndex = MinecraftAssetIndexReference(
                id = "assets-id",
                url = assetIndexUrl,
                sha1 = sha('0'),
                size = 0,
                totalSize = assetBytes.size.toLong(),
            ),
            downloads = MinecraftVersionDownloads(
                client = MinecraftDownload(
                    clientBytes.toByteString().sha1().hex(),
                    clientBytes.size.toLong(),
                    clientUrl
                ),
                server = MinecraftDownload(sha('f'), 0, "https://fixture/server.jar"),
            ),
            libraries = listOf(
                MinecraftLibrary(
                    name = "example:library:1",
                    downloads = MinecraftLibraryDownloads(
                        artifact = MinecraftLibraryDownload(
                            "example/library.jar",
                            libraryBytes.toByteString().sha1().hex(),
                            libraryBytes.size.toLong(),
                            libraryUrl,
                        ),
                    ),
                ),
            ),
            arguments = MinecraftArguments(
                defaultUserJvm = listOf(MinecraftArgument.Literal("-Xmx2G")),
                game = listOf(
                    MinecraftArgument.Literal("--username"),
                    MinecraftArgument.Literal("${'$'}{auth_player_name}")
                ),
                jvm = listOf(MinecraftArgument.Literal("-cp"), MinecraftArgument.Literal("${'$'}{classpath}")),
            ),
            javaVersion = MinecraftJavaVersion("fixture-runtime", 21),
            logging = MinecraftLoggingConfiguration(
                MinecraftClientLoggingConfiguration(
                    argument = "-Dlog4j.configurationFile=${'$'}{path}",
                    file = MinecraftLoggingFile(
                        "client.xml",
                        loggingBytes.toByteString().sha1().hex(),
                        loggingBytes.size.toLong(),
                        loggingUrl
                    ),
                    type = "log4j2-xml",
                ),
            ),
            complianceLevel = 1,
            minimumLauncherVersion = 1,
            releaseTime = "now",
            time = "now",
        ),
    ).encodeToByteArray()
    val minecraftVersionReference = MinecraftVersionReference(
        id = "demo",
        type = "release",
        url = metadataUrl,
        time = "now",
        releaseTime = "now",
        sha1 = sha('0'),
        complianceLevel = 1,
    )
    private val manifestBytes = launcherJson.encodeToString(
        MinecraftVersionManifest(
            latest = MinecraftLatestVersions(
                release = minecraftVersionReference.id,
                snapshot = minecraftVersionReference.id
            ),
            versions = listOf(minecraftVersionReference),
        ),
    ).encodeToByteArray()
    private val requestMutex = Mutex()
    private val requests = mutableListOf<String>()
    private var remainingCorruptClientAttempts = corruptClientAttempts
    val activeContentDownloads = MutableStateFlow(0)
    val activeAssetIndexDownloads = MutableStateFlow(0)
    private val mockEngine = MockEngine { httpRequestData ->
        val url = httpRequestData.url.toString()
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
        if (blockAssetIndexDownload && url == assetIndexUrl) {
            activeAssetIndexDownloads.update { it + 1 }
            try {
                awaitCancellation()
            } finally {
                activeAssetIndexDownloads.update { it - 1 }
            }
        }
        val content = when (url) {
            manifestUrl -> manifestBytes
            metadataUrl -> metadataBytes
            clientUrl -> if (corruptClient) "corrupt".encodeToByteArray() else clientBytes
            libraryUrl -> libraryBytes
            loggingUrl -> loggingBytes
            assetIndexUrl -> assetIndexBytes
            assetUrl -> assetBytes
            else -> error("Unexpected request: $url")
        }
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = if (url == manifestUrl || url == metadataUrl || url == assetIndexUrl) {
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            } else {
                headersOf(HttpHeaders.ContentLength, content.size.toString())
            },
        )
    }
    val httpClient = HttpClient(mockEngine) { configureLauncherHttpClient() }
    val installationService = InstallationService(httpClient, fakeFileSystem, launcherStore, launcherPlatform)

    suspend fun countRequests(url: String): Int = requestMutex.withLock { requests.count { it == url } }

    suspend fun recordInstalled() {
        fakeFileSystem.createDirectories(launcherStore.gameRoot(minecraftVersionReference.id))
        launcherStore.updateInstalled {
            it.copy(installations = listOf(InstalledVersion(minecraftVersionReference.id)))
        }
    }

    fun close() {
        httpClient.close()
    }
}
