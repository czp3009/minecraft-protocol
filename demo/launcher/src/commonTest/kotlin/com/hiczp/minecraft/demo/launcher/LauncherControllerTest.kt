package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.MinecraftLatestVersions
import com.hiczp.minecraft.distribution.metadata.MinecraftVersionManifest
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LauncherControllerTest {
    @Test
    fun startupShowsHomeWhileManifestLoadsAndOpensVersionsWhenReady() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val manifestBytes = emptyManifestBytes()
        val httpClient = HttpClient(
            MockEngine {
                requestStarted.complete(Unit)
                releaseResponse.await()
                respond(manifestBytes, headers = jsonHeaders())
            },
        ) { configureLauncherHttpClient() }
        val controllerFixture = ControllerFixture(backgroundScope, httpClient)
        try {
            controllerFixture.launcherController.start()
            requestStarted.await()

            assertEquals(LauncherDestination.Home, controllerFixture.launcherController.state.value.launcherDestination)
            controllerFixture.launcherController.showVersions()
            val loading =
                assertIs<LauncherDestination.Loading>(controllerFixture.launcherController.state.value.launcherDestination)
            assertEquals(LauncherOperation.VERSION_MANIFEST, loading.launcherOperation)

            releaseResponse.complete(Unit)
            val ready =
                controllerFixture.launcherController.state.first { it.versionManifestState is VersionManifestState.Ready }

            assertEquals(LauncherDestination.Versions, ready.launcherDestination)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun manifestFailureStaysInBackgroundUntilVersionsAreOpened() = runTest {
        var requestCount = 0
        val httpClient = HttpClient(
            MockEngine {
                requestCount++
                throw IllegalStateException("manifest failed")
            },
        ) { configureLauncherHttpClient() }
        val controllerFixture = ControllerFixture(backgroundScope, httpClient)
        try {
            controllerFixture.launcherController.start()
            val failed =
                controllerFixture.launcherController.state.first { it.versionManifestState is VersionManifestState.Failed }

            assertEquals(LauncherDestination.Home, failed.launcherDestination)
            controllerFixture.launcherController.showVersions()
            val errorState =
                controllerFixture.launcherController.state.first { it.launcherDestination is LauncherDestination.Error }
            val error = assertIs<LauncherDestination.Error>(errorState.launcherDestination)
            assertTrue("manifest failed" in error.message)
            assertEquals(LauncherDestination.Home, error.returnTo)
            assertEquals(2, requestCount)

            controllerFixture.launcherController.dismissError()
            assertEquals(LauncherDestination.Home, controllerFixture.launcherController.state.value.launcherDestination)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun cancellingReinstallAfterContentDownloadsStartLeavesVersionUninstalled() = runTest {
        val installFixture = InstallFixture(blockContentDownloads = true)
        installFixture.recordInstalled()
        val launcherController = createController(
            coroutineScope = backgroundScope,
            httpClient = installFixture.httpClient,
            fakeFileSystem = installFixture.fakeFileSystem,
            root = installFixture.root,
            launcherStore = installFixture.launcherStore,
            installationService = installFixture.installationService,
            launcherPlatform = installFixture.launcherPlatform,
        )
        try {
            launcherController.start()
            launcherController.state.first {
                it.versionManifestState is VersionManifestState.Ready && it.installedState.installations.isNotEmpty()
            }
            launcherController.confirmInstall(installFixture.minecraftVersionReference)
            launcherController.install(installFixture.minecraftVersionReference)
            installFixture.activeContentDownloads.first { it == 3 }

            assertIs<LauncherDestination.Installing>(launcherController.state.value.launcherDestination)
            assertTrue(launcherController.installedVersions().isEmpty())
            assertTrue(installFixture.launcherStore.loadInstalled().installations.isEmpty())
            launcherController.cancelInstallation()

            launcherController.state.first { it.launcherDestination == LauncherDestination.Versions }
            installFixture.activeContentDownloads.first { it == 0 }
            assertTrue(installFixture.launcherStore.loadInstalled().installations.isEmpty())
            assertEquals(LauncherDestination.Versions, launcherController.state.value.launcherDestination)
        } finally {
            installFixture.close()
        }
    }

    @Test
    fun cancellingReinstallDuringAssetIndexDownloadKeepsInstalledRecord() = runTest {
        val installFixture = InstallFixture(blockAssetIndexDownload = true)
        installFixture.recordInstalled()
        val launcherController = createController(
            coroutineScope = backgroundScope,
            httpClient = installFixture.httpClient,
            fakeFileSystem = installFixture.fakeFileSystem,
            root = installFixture.root,
            launcherStore = installFixture.launcherStore,
            installationService = installFixture.installationService,
            launcherPlatform = installFixture.launcherPlatform,
        )
        try {
            launcherController.start()
            launcherController.state.first {
                it.versionManifestState is VersionManifestState.Ready && it.installedState.installations.isNotEmpty()
            }
            launcherController.confirmInstall(installFixture.minecraftVersionReference)
            launcherController.install(installFixture.minecraftVersionReference)
            installFixture.activeAssetIndexDownloads.first { it == 1 }

            assertIs<LauncherDestination.PreparingInstall>(launcherController.state.value.launcherDestination)
            val installedVersion = InstalledVersion(installFixture.minecraftVersionReference.id)
            assertEquals(listOf(installedVersion), launcherController.installedVersions())
            assertEquals(1, installFixture.launcherStore.loadInstalled().installations.size)
            launcherController.cancelInstallation()

            launcherController.state.first { it.launcherDestination == LauncherDestination.Versions }
            installFixture.activeAssetIndexDownloads.first { it == 0 }
            assertEquals(1, installFixture.launcherStore.loadInstalled().installations.size)
        } finally {
            installFixture.close()
        }
    }

    @Test
    fun loginFailureShowsErrorAndReturnsToAccounts() = runTest {
        val httpClient = HttpClient(MockEngine { error("Unexpected HTTP request") })
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher root".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val launcherPlatform = testPlatform()
        val installationService =
            InstallationService(httpClient, fakeFileSystem, launcherStore, launcherPlatform)
        val accountService = AccountService(httpClient, launcherStore) {
            throw IllegalStateException("browser failed")
        }
        val launcherController = LauncherController(
            backgroundScope,
            launcherStore,
            installationService,
            accountService,
            GameProcessService(fakeFileSystem, root),
            launcherPlatform,
        )
        try {
            launcherController.showAccounts()
            launcherController.loginMicrosoft()

            val errorState = launcherController.state.first { it.launcherDestination is LauncherDestination.Error }
            val error = assertIs<LauncherDestination.Error>(errorState.launcherDestination)
            assertTrue("browser failed" in error.message)
            assertEquals(LauncherDestination.Accounts, error.returnTo)

            launcherController.dismissError()
            assertEquals(LauncherDestination.Accounts, launcherController.state.value.launcherDestination)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun addingOfflineIdentityReturnsToAccounts() = runTest {
        val httpClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val controllerFixture = ControllerFixture(backgroundScope, httpClient)
        try {
            controllerFixture.launcherController.showAddAccount()
            controllerFixture.launcherController.showOfflineInput()
            controllerFixture.launcherController.saveOfflineIdentity("OfflinePlayer")

            val launcherState = controllerFixture.launcherController.state.first {
                it.launcherDestination == LauncherDestination.Accounts
            }

            assertEquals(listOf("OfflinePlayer"), launcherState.authState?.accounts?.map { it.minecraftIdentity.name })
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun backgroundRefreshFailureMarksLoginExpiredWithoutLeavingHomeAndBlocksLaunch() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher root".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val minecraftOnlineIdentity = MinecraftOnlineIdentity(
            id = Uuid.parse(TEST_PROFILE_ID),
            name = "OnlinePlayer",
            accessToken = "expired-minecraft-token",
        )
        launcherStore.authMemory.update {
            selectedIdentityId = minecraftOnlineIdentity.id
            accounts = listOf(StoredAccount(minecraftOnlineIdentity, "expired-refresh-token", 0))
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val httpClient = HttpClient(
            MockEngine { httpRequestData ->
                if ("version_manifest" in httpRequestData.url.toString()) {
                    respond(emptyManifestBytes(), headers = jsonHeaders())
                } else {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                    throw IllegalStateException("refresh rejected")
                }
            },
        ) { configureLauncherHttpClient() }
        val launcherPlatform = testPlatform()
        val launcherController = LauncherController(
            backgroundScope,
            launcherStore,
            InstallationService(httpClient, fakeFileSystem, launcherStore, launcherPlatform),
            AccountService(httpClient, launcherStore, BrowserService {}),
            GameProcessService(fakeFileSystem, root),
            launcherPlatform,
        )
        try {
            launcherController.start()
            refreshStarted.await()
            val refreshing = launcherController.state.first {
                it.accountCredentials[minecraftOnlineIdentity.id] == AccountCredentialState.REFRESHING
            }
            assertEquals(LauncherDestination.Home, refreshing.launcherDestination)

            launcherController.showVersionActions("demo")
            launcherController.launchGame("demo")
            val loadingState = launcherController.state.first {
                (it.launcherDestination as? LauncherDestination.Loading)?.launcherOperation == LauncherOperation.REFRESH_ACCOUNT
            }
            val loading = assertIs<LauncherDestination.Loading>(loadingState.launcherDestination)
            assertEquals(LauncherOperation.REFRESH_ACCOUNT, loading.launcherOperation)
            releaseRefresh.complete(Unit)

            val failed = launcherController.state.first { it.launcherDestination is LauncherDestination.Error }
            assertEquals(AccountCredentialState.LOGIN_EXPIRED, failed.accountCredentials[minecraftOnlineIdentity.id])
            val error = assertIs<LauncherDestination.Error>(failed.launcherDestination)
            assertTrue("Login expired" in error.message)
            assertEquals(LauncherDestination.VersionActions("demo"), error.returnTo)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun launchWaitsForBackgroundRefreshAndUsesTheNewMinecraftAccessToken() = runTest {
        val installFixture = InstallFixture()
        val responseMutex = Mutex()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val responses = authenticationResponses()
        var responseIndex = 0
        val accountClient = HttpClient(
            MockEngine {
                val index = responseMutex.withLock { responseIndex++ }
                if (index == 0) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                }
                respond(
                    content = responses[index],
                    headers = jsonHeaders(),
                )
            },
        )
        val minecraftOnlineIdentity = MinecraftOnlineIdentity(
            id = Uuid.parse(TEST_PROFILE_ID),
            name = "OnlinePlayer",
            accessToken = "expired-minecraft-token",
        )
        val recordingGameProcessRuntime = RecordingGameProcessRuntime()
        try {
            installFixture.installationService.install(installFixture.minecraftVersionReference)
            installFixture.launcherStore.authMemory.update {
                selectedIdentityId = minecraftOnlineIdentity.id
                accounts = listOf(StoredAccount(minecraftOnlineIdentity, "old-refresh-token", 0))
            }
            val launcherController = LauncherController(
                backgroundScope,
                installFixture.launcherStore,
                installFixture.installationService,
                AccountService(accountClient, installFixture.launcherStore, BrowserService {}),
                recordingGameProcessRuntime,
                installFixture.launcherPlatform,
            )
            launcherController.start()
            refreshStarted.await()
            launcherController.state.first { it.accountCredentials[minecraftOnlineIdentity.id] == AccountCredentialState.REFRESHING }

            launcherController.showVersionActions(installFixture.minecraftVersionReference.id)
            launcherController.launchGame(installFixture.minecraftVersionReference.id)
            val loadingState = launcherController.state.first {
                (it.launcherDestination as? LauncherDestination.Loading)?.launcherOperation == LauncherOperation.REFRESH_ACCOUNT
            }
            val loading = assertIs<LauncherDestination.Loading>(loadingState.launcherDestination)
            assertEquals(LauncherOperation.REFRESH_ACCOUNT, loading.launcherOperation)
            releaseRefresh.complete(Unit)

            assertEquals("minecraft-access", recordingGameProcessRuntime.launched.await().sensitiveAccessToken)
            val launched = launcherController.state.first { it.launcherDestination is LauncherDestination.GameOutput }
            assertTrue(minecraftOnlineIdentity.id !in launched.accountCredentials)
            val persistedIdentity = assertIs<MinecraftOnlineIdentity>(
                installFixture.launcherStore.authMemory.read { accounts.single().minecraftIdentity },
            )
            assertEquals("minecraft-access", persistedIdentity.accessToken)
        } finally {
            accountClient.close()
            installFixture.close()
        }
    }

    @Test
    fun gameProcessFailureWithDefaultIdentityShowsErrorAndReturnsToGameOutput() = runTest {
        val installFixture = InstallFixture()
        val failingGameProcessRuntime = FailingGameProcessRuntime()
        try {
            installFixture.installationService.install(installFixture.minecraftVersionReference)
            val accountService =
                AccountService(installFixture.httpClient, installFixture.launcherStore, BrowserService {})
            val launcherController = LauncherController(
                backgroundScope,
                installFixture.launcherStore,
                installFixture.installationService,
                accountService,
                failingGameProcessRuntime,
                installFixture.launcherPlatform,
            )
            launcherController.start()
            launcherController.state.first {
                it.versionManifestState is VersionManifestState.Ready && it.installedState.installations.isNotEmpty()
            }
            launcherController.showVersionActions(installFixture.minecraftVersionReference.id)
            launcherController.launchGame(installFixture.minecraftVersionReference.id)

            val errorState = launcherController.state.first { it.launcherDestination is LauncherDestination.Error }
            val error = assertIs<LauncherDestination.Error>(errorState.launcherDestination)
            assertTrue("process failed" in error.message)
            val outputDestination = assertIs<LauncherDestination.GameOutput>(error.returnTo)
            val gameOutputSnapshot = outputDestination.gameOutputBuffer.state.value
            assertTrue(gameOutputSnapshot.lines.any { "Launch failed" in it.text && "process failed" in it.text })
            assertEquals(false, gameOutputSnapshot.running)

            launcherController.dismissError()
            assertEquals(outputDestination, launcherController.state.value.launcherDestination)
        } finally {
            installFixture.close()
        }
    }
}

private class FailingGameProcessRuntime : GameProcessRuntime {
    override fun cleanupStaleArgumentFiles() = Unit

    override fun outputBuffer(launchPlan: LaunchPlan) =
        GameOutputBuffer(listOfNotNull(launchPlan.sensitiveAccessToken))

    override suspend fun launch(launchPlan: LaunchPlan, gameOutputBuffer: GameOutputBuffer): Nothing {
        throw IllegalStateException("process failed")
    }
}

private class RecordingGameProcessRuntime : GameProcessRuntime {
    val launched = CompletableDeferred<LaunchPlan>()

    override fun cleanupStaleArgumentFiles() = Unit

    override fun outputBuffer(launchPlan: LaunchPlan) =
        GameOutputBuffer(listOfNotNull(launchPlan.sensitiveAccessToken))

    override suspend fun launch(launchPlan: LaunchPlan, gameOutputBuffer: GameOutputBuffer) {
        launched.complete(launchPlan)
        gameOutputBuffer.finish(0)
    }
}

private class ControllerFixture(coroutineScope: CoroutineScope, httpClient: HttpClient) {
    private val fakeFileSystem = FakeFileSystem()
    private val root = "/launcher root".toPath()
    private val launcherStore = LauncherStore(fakeFileSystem, root)
    private val launcherPlatform = testPlatform()
    val launcherController: LauncherController

    init {
        fakeFileSystem.createDirectories(root)
        launcherController = createController(
            coroutineScope,
            httpClient,
            fakeFileSystem,
            root,
            launcherStore,
            InstallationService(httpClient, fakeFileSystem, launcherStore, launcherPlatform),
            launcherPlatform,
        )
    }
}

private fun createController(
    coroutineScope: CoroutineScope,
    httpClient: HttpClient,
    fakeFileSystem: FakeFileSystem,
    root: Path,
    launcherStore: LauncherStore,
    installationService: InstallationService,
    launcherPlatform: LauncherPlatform,
): LauncherController = LauncherController(
    coroutineScope,
    launcherStore,
    installationService,
    AccountService(httpClient, launcherStore, BrowserService {}),
    GameProcessService(fakeFileSystem, root),
    launcherPlatform,
)

private fun emptyManifestBytes(): ByteArray = launcherJson.encodeToString(
    MinecraftVersionManifest(
        latest = MinecraftLatestVersions(release = "none", snapshot = "none"),
        versions = emptyList(),
    ),
).encodeToByteArray()

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private fun testPlatform() = LauncherPlatform("windows", "x86_64", ";", "windows-x86_64", "10.0.22631")
