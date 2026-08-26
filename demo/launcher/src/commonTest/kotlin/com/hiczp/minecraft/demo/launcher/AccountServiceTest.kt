@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.uuid.Uuid

class AccountServiceTest {
    @Test
    fun missingSelectionUsesDefaultOfflinePlayerWithoutPersistingAnAccount() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val accountClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val accountService = AccountService(accountClient, launcherStore)

        val minecraftOfflineIdentity = assertIs<MinecraftOfflineIdentity>(accountService.selectedIdentity())

        assertEquals(DEFAULT_OFFLINE_PLAYER_NAME, minecraftOfflineIdentity.name)
        assertEquals(MinecraftOfflineIdentity(DEFAULT_OFFLINE_PLAYER_NAME).id, minecraftOfflineIdentity.id)
        assertTrue(launcherStore.authMemory.read { accounts.isEmpty() })
        accountClient.close()
    }

    @Test
    fun loopbackLoginIgnoresBadStateAndPersistsOnlyRequiredCredentials() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val responses = authenticationResponses()
        val responseMutex = Mutex()
        var responseIndex = 0
        val accountClient = HttpClient(
            MockEngine {
                val content = responseMutex.withLock { responses[responseIndex++] }
                respond(
                    content = content,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val browserService = BrowserService { authorizationUrl ->
            withContext(Dispatchers.Default) {
                val authorization = Url(authorizationUrl)
                val redirect = requireNotNull(authorization.parameters["redirect_uri"])
                val expectedState = requireNotNull(authorization.parameters["state"])
                val browserClient = HttpClient()
                try {
                    val invalid = browserClient.get(
                        URLBuilder(redirect).apply {
                            parameters.append("state", "wrong-state")
                            parameters.append("code", "ignored-code")
                        }.build(),
                    )
                    assertEquals(HttpStatusCode.BadRequest, invalid.status)
                    val valid = browserClient.get(
                        URLBuilder(redirect).apply {
                            parameters.append("state", expectedState)
                            parameters.append("code", "authorization-code")
                        }.build(),
                    )
                    assertEquals(HttpStatusCode.OK, valid.status)
                    val message = valid.bodyAsText()
                    assertTrue("authorization is complete" in message)
                    assertFalse("authorization-code" in message)
                } finally {
                    browserClient.close()
                }
            }
        }
        val accountService = AccountService(accountClient, launcherStore, browserService)

        val storedAccount = accountService.loginMicrosoft()

        val minecraftOnlineIdentity = assertIs<MinecraftOnlineIdentity>(storedAccount.minecraftIdentity)
        assertEquals("OnlinePlayer", minecraftOnlineIdentity.name)
        assertEquals(Uuid.parse(TEST_PROFILE_ID), minecraftOnlineIdentity.id)
        val storedText = fakeFileSystem.read(root / "auth.json") { readUtf8() }
        val storedAccountJsonObject = launcherJson.parseToJsonElement(storedText)
            .jsonObject
            .getValue("accounts")
            .jsonArray
            .single()
            .jsonObject
        val storedIdentity = storedAccountJsonObject.getValue("minecraftIdentity").jsonObject
        assertEquals("refresh-token", storedAccountJsonObject.getValue("microsoftRefreshToken").jsonPrimitive.content)
        assertTrue(
            storedAccountJsonObject.getValue("minecraftAccessTokenExpiresAtEpochSeconds").jsonPrimitive.content.toLong() > 0,
        )
        assertEquals("minecraft-access", storedIdentity.getValue("accessToken").jsonPrimitive.content)
        assertFalse("microsoft-access" in storedText)
        assertFalse("user-token" in storedText)
        assertFalse("xsts-token" in storedText)
        assertEquals(6, responseIndex)
        accountClient.close()
    }

    @Test
    fun validMinecraftTokenIsUsedWithoutRefresh() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val storedAccount = StoredAccount(
            minecraftIdentity = MinecraftOnlineIdentity(
                id = Uuid.parse(CACHED_PROFILE_ID),
                name = "CachedPlayer",
                accessToken = "cached-minecraft-token",
            ),
            microsoftRefreshToken = "refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = Long.MAX_VALUE,
        )
        launcherStore.authMemory.update {
            selectedIdentityId = storedAccount.minecraftIdentity.id
            accounts = listOf(storedAccount)
        }
        val accountClient = HttpClient(MockEngine { error("A valid Minecraft token must not trigger HTTP") })
        val accountService = AccountService(accountClient, launcherStore)

        val refreshed = requireNotNull(accountService.refreshIfNeeded(storedAccount.minecraftIdentity.id))
        val minecraftOnlineIdentity = assertIs<MinecraftOnlineIdentity>(refreshed.minecraftIdentity)

        assertEquals("CachedPlayer", minecraftOnlineIdentity.name)
        assertEquals(Uuid.parse(CACHED_PROFILE_ID), minecraftOnlineIdentity.id)
        assertEquals("cached-minecraft-token", minecraftOnlineIdentity.accessToken)
        accountClient.close()
    }

    @Test
    fun expiredMinecraftTokenRefreshesFullChainAndPersistsRotation() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val storedAccount = StoredAccount(
            minecraftIdentity = MinecraftOnlineIdentity(
                id = Uuid.parse(TEST_PROFILE_ID),
                name = "OldPlayer",
                accessToken = "expired-minecraft-token",
            ),
            microsoftRefreshToken = "old-refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = 0,
        )
        launcherStore.authMemory.update {
            selectedIdentityId = storedAccount.minecraftIdentity.id
            accounts = listOf(storedAccount)
        }
        val responses = authenticationResponses()
        var responseIndex = 0
        val accountClient = HttpClient(
            MockEngine {
                val content = responses[responseIndex++]
                respond(
                    content = content,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val accountService = AccountService(accountClient, launcherStore)

        val refreshed = requireNotNull(accountService.refreshIfNeeded(storedAccount.minecraftIdentity.id))
        val minecraftOnlineIdentity = assertIs<MinecraftOnlineIdentity>(refreshed.minecraftIdentity)

        assertEquals("OnlinePlayer", minecraftOnlineIdentity.name)
        assertEquals(Uuid.parse(TEST_PROFILE_ID), minecraftOnlineIdentity.id)
        assertEquals("minecraft-access", minecraftOnlineIdentity.accessToken)
        val persisted = launcherStore.authMemory.read { accounts.single() }
        assertEquals("refresh-token", persisted.microsoftRefreshToken)
        assertEquals("minecraft-access", assertIs<MinecraftOnlineIdentity>(persisted.minecraftIdentity).accessToken)
        assertTrue(requireNotNull(persisted.minecraftAccessTokenExpiresAtEpochSeconds) > 0)
        assertEquals(6, responseIndex)
        accountClient.close()
    }

    @Test
    fun refreshFinishingAfterDeletionDoesNotRestoreTheAccount() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val storedAccount = StoredAccount(
            minecraftIdentity = MinecraftOnlineIdentity(
                id = Uuid.parse(TEST_PROFILE_ID),
                name = "OldPlayer",
                accessToken = "expired-minecraft-token",
            ),
            microsoftRefreshToken = "old-refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = 0,
        )
        launcherStore.authMemory.update {
            selectedIdentityId = storedAccount.minecraftIdentity.id
            accounts = listOf(storedAccount)
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val responses = authenticationResponses()
        var responseIndex = 0
        val accountClient = HttpClient(
            MockEngine {
                val index = responseIndex++
                if (index == 0) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                }
                respond(
                    content = responses[index],
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val accountService = AccountService(accountClient, launcherStore)

        val refresh = async { accountService.refreshIfNeeded(storedAccount.minecraftIdentity.id) }
        refreshStarted.await()
        accountService.delete(storedAccount.minecraftIdentity.id)
        releaseRefresh.complete(Unit)

        assertEquals(null, refresh.await())
        assertTrue(launcherStore.authMemory.read { accounts.isEmpty() })
        assertEquals(1, responseIndex)
        accountClient.close()
    }

    @Test
    fun failedRefreshIsRememberedForTheLauncherSession() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val storedAccount = StoredAccount(
            minecraftIdentity = MinecraftOnlineIdentity(
                id = Uuid.parse(TEST_PROFILE_ID),
                name = "OnlinePlayer",
                accessToken = "expired-minecraft-token",
            ),
            microsoftRefreshToken = "expired-refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = 0,
        )
        launcherStore.authMemory.update {
            selectedIdentityId = storedAccount.minecraftIdentity.id
            accounts = listOf(storedAccount)
        }
        var requestCount = 0
        val accountClient = HttpClient(
            MockEngine {
                requestCount++
                throw IllegalStateException("refresh rejected")
            },
        )
        val accountService = AccountService(accountClient, launcherStore)

        assertFailsWith<AccountLoginExpiredException> { accountService.refreshIfNeeded(storedAccount.minecraftIdentity.id) }
        assertFailsWith<AccountLoginExpiredException> { accountService.refreshIfNeeded(storedAccount.minecraftIdentity.id) }

        assertEquals(1, requestCount)
        accountClient.close()
    }

    @Test
    fun editingAnOfflineIdentityReplacesItsDerivedIdentityAndSelection() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val accountClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val accountService = AccountService(accountClient, launcherStore)
        val original = accountService.addOffline("Before")

        val replacement = accountService.updateOffline(original.minecraftIdentity.id, "After")

        assertEquals(MinecraftOfflineIdentity("After"), replacement.minecraftIdentity)
        val authState = launcherStore.authMemory.read { this }
        assertEquals(replacement.minecraftIdentity.id, authState.selectedIdentityId)
        assertEquals(listOf(replacement), authState.accounts)
        accountClient.close()
    }

    @Test
    fun deletingTheSelectedAccountLeavesTheDefaultIdentityUnpersisted() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val accountClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val accountService = AccountService(accountClient, launcherStore)
        accountService.addOffline("First")
        val selected = accountService.addOffline("Second")

        accountService.delete(selected.minecraftIdentity.id)

        val authState = launcherStore.authMemory.read { this }
        assertEquals(null, authState.selectedIdentityId)
        assertEquals(listOf(MinecraftOfflineIdentity("First")), authState.accounts.map(StoredAccount::minecraftIdentity))
        assertEquals(MinecraftOfflineIdentity(DEFAULT_OFFLINE_PLAYER_NAME), accountService.selectedIdentity())
        accountClient.close()
    }
}

private const val CACHED_PROFILE_ID = "11111111111111111111111111111111"
