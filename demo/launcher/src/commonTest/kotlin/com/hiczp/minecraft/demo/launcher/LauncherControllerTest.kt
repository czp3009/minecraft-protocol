package com.hiczp.minecraft.demo.launcher

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
        val client = HttpClient(
            MockEngine {
                requestStarted.complete(Unit)
                releaseResponse.await()
                respond(manifestBytes, headers = jsonHeaders())
            },
        ) { configureLauncherHttpClient() }
        val fixture = ControllerFixture(backgroundScope, client)
        try {
            fixture.controller.start()
            requestStarted.await()

            assertEquals(LauncherDestination.Home, fixture.controller.state.value.destination)
            fixture.controller.showVersions()
            val loading = assertIs<LauncherDestination.Loading>(fixture.controller.state.value.destination)
            assertEquals(LauncherOperation.VERSION_MANIFEST, loading.operation)

            releaseResponse.complete(Unit)
            val ready = fixture.controller.state.first { it.manifest is VersionManifestState.Ready }

            assertEquals(LauncherDestination.Versions, ready.destination)
        } finally {
            client.close()
        }
    }

    @Test
    fun manifestFailureStaysInBackgroundUntilVersionsAreOpened() = runTest {
        var requestCount = 0
        val client = HttpClient(
            MockEngine {
                requestCount++
                throw IllegalStateException("manifest failed")
            },
        ) { configureLauncherHttpClient() }
        val fixture = ControllerFixture(backgroundScope, client)
        try {
            fixture.controller.start()
            val failed = fixture.controller.state.first { it.manifest is VersionManifestState.Failed }

            assertEquals(LauncherDestination.Home, failed.destination)
            fixture.controller.showVersions()
            val errorState = fixture.controller.state.first { it.destination is LauncherDestination.Error }
            val error = assertIs<LauncherDestination.Error>(errorState.destination)
            assertTrue("manifest failed" in error.message)
            assertEquals(LauncherDestination.Home, error.returnTo)
            assertEquals(2, requestCount)

            fixture.controller.dismissError()
            assertEquals(LauncherDestination.Home, fixture.controller.state.value.destination)
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellingInstallStopsAllDownloadsAndReturnsToVersions() = runTest {
        val fixture = InstallFixture(blockContentDownloads = true)
        val controller = createController(
            scope = backgroundScope,
            client = fixture.client,
            fileSystem = fixture.fileSystem,
            root = fixture.root,
            store = fixture.store,
            installationService = fixture.service,
            platform = fixture.platform,
        )
        try {
            controller.start()
            controller.state.first { it.manifest is VersionManifestState.Ready }
            controller.confirmInstall(fixture.entry)
            controller.install(fixture.entry)
            fixture.activeContentDownloads.first { it == 3 }

            assertIs<LauncherDestination.Installing>(controller.state.value.destination)
            controller.cancelInstallation()

            controller.state.first { it.destination == LauncherDestination.Versions }
            fixture.activeContentDownloads.first { it == 0 }
            assertTrue(fixture.store.loadInstalled().installations.isEmpty())
            assertEquals(LauncherDestination.Versions, controller.state.value.destination)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun loginFailureShowsErrorAndReturnsToAccounts() = runTest {
        val client = HttpClient(MockEngine { error("Unexpected HTTP request") })
        val fileSystem = FakeFileSystem()
        val root = "/launcher root".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val platform = testPlatform()
        val installationService = InstallationService(createMojangApi(client), fileSystem, store, platform)
        val accountService = AccountService(client, store) {
            throw IllegalStateException("browser failed")
        }
        val controller = LauncherController(
            backgroundScope,
            store,
            installationService,
            accountService,
            GameProcessService(fileSystem, root),
            platform,
        )
        try {
            controller.showAccounts()
            controller.loginMicrosoft()

            val errorState = controller.state.first { it.destination is LauncherDestination.Error }
            val error = assertIs<LauncherDestination.Error>(errorState.destination)
            assertTrue("browser failed" in error.message)
            assertEquals(LauncherDestination.Accounts, error.returnTo)

            controller.dismissError()
            assertEquals(LauncherDestination.Accounts, controller.state.value.destination)
        } finally {
            client.close()
        }
    }

    @Test
    fun addingOfflineIdentityReturnsToAccounts() = runTest {
        val client = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val fixture = ControllerFixture(backgroundScope, client)
        try {
            fixture.controller.showAddAccount()
            fixture.controller.showOfflineInput()
            fixture.controller.saveOfflineIdentity("OfflinePlayer")

            val state = fixture.controller.state.first { it.destination == LauncherDestination.Accounts }

            assertEquals(listOf("OfflinePlayer"), state.auth?.accounts?.map { it.identity.name })
        } finally {
            client.close()
        }
    }

    @Test
    fun backgroundRefreshFailureMarksLoginExpiredWithoutLeavingHomeAndBlocksLaunch() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher root".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val identity = MinecraftOnlineIdentity(
            id = Uuid.parse(TEST_PROFILE_ID),
            name = "OnlinePlayer",
            accessToken = "expired-minecraft-token",
        )
        store.auth.update {
            selectedIdentityId = identity.id
            accounts = listOf(StoredAccount(identity, "expired-refresh-token", 0))
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine { request ->
                if ("version_manifest" in request.url.toString()) {
                    respond(emptyManifestBytes(), headers = jsonHeaders())
                } else {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                    throw IllegalStateException("refresh rejected")
                }
            },
        ) { configureLauncherHttpClient() }
        val platform = testPlatform()
        val controller = LauncherController(
            backgroundScope,
            store,
            InstallationService(createMojangApi(client), fileSystem, store, platform),
            AccountService(client, store, BrowserService {}),
            GameProcessService(fileSystem, root),
            platform,
        )
        try {
            controller.start()
            refreshStarted.await()
            val refreshing = controller.state.first {
                it.accountCredentials[identity.id] == AccountCredentialState.REFRESHING
            }
            assertEquals(LauncherDestination.Home, refreshing.destination)

            controller.showVersionActions("demo")
            controller.launchGame("demo")
            val loadingState = controller.state.first {
                (it.destination as? LauncherDestination.Loading)?.operation == LauncherOperation.REFRESH_ACCOUNT
            }
            val loading = assertIs<LauncherDestination.Loading>(loadingState.destination)
            assertEquals(LauncherOperation.REFRESH_ACCOUNT, loading.operation)
            releaseRefresh.complete(Unit)

            val failed = controller.state.first { it.destination is LauncherDestination.Error }
            assertEquals(AccountCredentialState.LOGIN_EXPIRED, failed.accountCredentials[identity.id])
            val error = assertIs<LauncherDestination.Error>(failed.destination)
            assertTrue("Login expired" in error.message)
            assertEquals(LauncherDestination.VersionActions("demo"), error.returnTo)
        } finally {
            client.close()
        }
    }

    @Test
    fun launchWaitsForBackgroundRefreshAndUsesTheNewMinecraftAccessToken() = runTest {
        val fixture = InstallFixture()
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
        val identity = MinecraftOnlineIdentity(
            id = Uuid.parse(TEST_PROFILE_ID),
            name = "OnlinePlayer",
            accessToken = "expired-minecraft-token",
        )
        val processRuntime = RecordingGameProcessRuntime()
        try {
            fixture.service.install(fixture.service.prepareInstallation(fixture.entry))
            fixture.store.auth.update {
                selectedIdentityId = identity.id
                accounts = listOf(StoredAccount(identity, "old-refresh-token", 0))
            }
            val controller = LauncherController(
                backgroundScope,
                fixture.store,
                fixture.service,
                AccountService(accountClient, fixture.store, BrowserService {}),
                processRuntime,
                fixture.platform,
            )
            controller.start()
            refreshStarted.await()
            controller.state.first { it.accountCredentials[identity.id] == AccountCredentialState.REFRESHING }

            controller.showVersionActions(fixture.entry.id)
            controller.launchGame(fixture.entry.id)
            val loadingState = controller.state.first {
                (it.destination as? LauncherDestination.Loading)?.operation == LauncherOperation.REFRESH_ACCOUNT
            }
            val loading = assertIs<LauncherDestination.Loading>(loadingState.destination)
            assertEquals(LauncherOperation.REFRESH_ACCOUNT, loading.operation)
            releaseRefresh.complete(Unit)

            assertEquals("minecraft-access", processRuntime.launched.await().sensitiveAccessToken)
            val launched = controller.state.first { it.destination is LauncherDestination.GameOutput }
            assertTrue(identity.id !in launched.accountCredentials)
            val persistedIdentity = assertIs<MinecraftOnlineIdentity>(
                fixture.store.auth.read { accounts.single().identity },
            )
            assertEquals("minecraft-access", persistedIdentity.accessToken)
        } finally {
            accountClient.close()
            fixture.close()
        }
    }

    @Test
    fun gameProcessFailureWithDefaultIdentityShowsErrorAndReturnsToGameOutput() = runTest {
        val fixture = InstallFixture()
        val processRuntime = FailingGameProcessRuntime()
        try {
            fixture.service.install(fixture.service.prepareInstallation(fixture.entry))
            val accountService = AccountService(fixture.client, fixture.store, BrowserService {})
            val controller = LauncherController(
                backgroundScope,
                fixture.store,
                fixture.service,
                accountService,
                processRuntime,
                fixture.platform,
            )
            controller.start()
            controller.state.first {
                it.manifest is VersionManifestState.Ready && it.installed.installations.isNotEmpty()
            }
            controller.showVersionActions(fixture.entry.id)
            controller.launchGame(fixture.entry.id)

            val errorState = controller.state.first { it.destination is LauncherDestination.Error }
            val error = assertIs<LauncherDestination.Error>(errorState.destination)
            assertTrue("process failed" in error.message)
            val outputDestination = assertIs<LauncherDestination.GameOutput>(error.returnTo)
            val output = outputDestination.output.state.value
            assertTrue(output.lines.any { "Launch failed" in it.text && "process failed" in it.text })
            assertEquals(false, output.running)

            controller.dismissError()
            assertEquals(outputDestination, controller.state.value.destination)
        } finally {
            fixture.close()
        }
    }
}

private class FailingGameProcessRuntime : GameProcessRuntime {
    override fun cleanupStaleArgumentFiles() = Unit

    override fun outputBuffer(plan: LaunchPlan) = GameOutputBuffer(listOfNotNull(plan.sensitiveAccessToken))

    override suspend fun launch(plan: LaunchPlan, output: GameOutputBuffer): Nothing {
        throw IllegalStateException("process failed")
    }
}

private class RecordingGameProcessRuntime : GameProcessRuntime {
    val launched = CompletableDeferred<LaunchPlan>()

    override fun cleanupStaleArgumentFiles() = Unit

    override fun outputBuffer(plan: LaunchPlan) = GameOutputBuffer(listOfNotNull(plan.sensitiveAccessToken))

    override suspend fun launch(plan: LaunchPlan, output: GameOutputBuffer) {
        launched.complete(plan)
        output.finish(0)
    }
}

private class ControllerFixture(scope: CoroutineScope, client: HttpClient) {
    private val fileSystem = FakeFileSystem()
    private val root = "/launcher root".toPath()
    private val store = LauncherStore(fileSystem, root)
    private val platform = testPlatform()
    val controller: LauncherController

    init {
        fileSystem.createDirectories(root)
        controller = createController(
            scope,
            client,
            fileSystem,
            root,
            store,
            InstallationService(createMojangApi(client), fileSystem, store, platform),
            platform,
        )
    }
}

private fun createController(
    scope: CoroutineScope,
    client: HttpClient,
    fileSystem: FakeFileSystem,
    root: Path,
    store: LauncherStore,
    installationService: InstallationService,
    platform: LauncherPlatform,
): LauncherController = LauncherController(
    scope,
    store,
    installationService,
    AccountService(client, store, BrowserService {}),
    GameProcessService(fileSystem, root),
    platform,
)

private fun emptyManifestBytes(): ByteArray = launcherJson.encodeToString(
    VersionManifest(
        latest = VersionManifest.Latest(release = "none", snapshot = "none"),
        versions = emptyList(),
    ),
).encodeToByteArray()

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private fun testPlatform() = LauncherPlatform("windows", "x86_64", ";", "windows-x86_64")
