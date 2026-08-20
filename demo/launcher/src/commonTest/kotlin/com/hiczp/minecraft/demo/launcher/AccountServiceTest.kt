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
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val accountClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val service = AccountService(accountClient, store)

        val identity = assertIs<MinecraftOfflineIdentity>(service.selectedIdentity())

        assertEquals(DEFAULT_OFFLINE_PLAYER_NAME, identity.name)
        assertEquals(MinecraftOfflineIdentity(DEFAULT_OFFLINE_PLAYER_NAME).id, identity.id)
        assertTrue(store.auth.read { accounts.isEmpty() })
        accountClient.close()
    }

    @Test
    fun loopbackLoginIgnoresBadStateAndPersistsOnlyRequiredCredentials() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
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
        val browser = BrowserService { authorizationUrl ->
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
        val service = AccountService(accountClient, store, browser)

        val account = service.loginMicrosoft()

        val identity = assertIs<MinecraftOnlineIdentity>(account.identity)
        assertEquals("OnlinePlayer", identity.name)
        assertEquals(Uuid.parse(TEST_PROFILE_ID), identity.id)
        val storedText = fileSystem.read(root / "auth.json") { readUtf8() }
        val storedAccount = launcherJson.parseToJsonElement(storedText)
            .jsonObject
            .getValue("accounts")
            .jsonArray
            .single()
            .jsonObject
        val storedIdentity = storedAccount.getValue("identity").jsonObject
        assertEquals("refresh-token", storedAccount.getValue("microsoftRefreshToken").jsonPrimitive.content)
        assertTrue(
            storedAccount.getValue("minecraftAccessTokenExpiresAtEpochSeconds").jsonPrimitive.content.toLong() > 0,
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
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val account = StoredAccount(
            identity = MinecraftOnlineIdentity(
                id = Uuid.parse(CACHED_PROFILE_ID),
                name = "CachedPlayer",
                accessToken = "cached-minecraft-token",
            ),
            microsoftRefreshToken = "refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = Long.MAX_VALUE,
        )
        store.auth.update {
            selectedIdentityId = account.identity.id
            accounts = listOf(account)
        }
        val accountClient = HttpClient(MockEngine { error("A valid Minecraft token must not trigger HTTP") })
        val service = AccountService(accountClient, store)

        val refreshed = requireNotNull(service.refreshIfNeeded(account.identity.id))
        val identity = assertIs<MinecraftOnlineIdentity>(refreshed.identity)

        assertEquals("CachedPlayer", identity.name)
        assertEquals(Uuid.parse(CACHED_PROFILE_ID), identity.id)
        assertEquals("cached-minecraft-token", identity.accessToken)
        accountClient.close()
    }

    @Test
    fun expiredMinecraftTokenRefreshesFullChainAndPersistsRotation() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val account = StoredAccount(
            identity = MinecraftOnlineIdentity(
                id = Uuid.parse(TEST_PROFILE_ID),
                name = "OldPlayer",
                accessToken = "expired-minecraft-token",
            ),
            microsoftRefreshToken = "old-refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = 0,
        )
        store.auth.update {
            selectedIdentityId = account.identity.id
            accounts = listOf(account)
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
        val service = AccountService(accountClient, store)

        val refreshed = requireNotNull(service.refreshIfNeeded(account.identity.id))
        val identity = assertIs<MinecraftOnlineIdentity>(refreshed.identity)

        assertEquals("OnlinePlayer", identity.name)
        assertEquals(Uuid.parse(TEST_PROFILE_ID), identity.id)
        assertEquals("minecraft-access", identity.accessToken)
        val persisted = store.auth.read { accounts.single() }
        assertEquals("refresh-token", persisted.microsoftRefreshToken)
        assertEquals("minecraft-access", assertIs<MinecraftOnlineIdentity>(persisted.identity).accessToken)
        assertTrue(requireNotNull(persisted.minecraftAccessTokenExpiresAtEpochSeconds) > 0)
        assertEquals(6, responseIndex)
        accountClient.close()
    }

    @Test
    fun refreshFinishingAfterDeletionDoesNotRestoreTheAccount() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val account = StoredAccount(
            identity = MinecraftOnlineIdentity(
                id = Uuid.parse(TEST_PROFILE_ID),
                name = "OldPlayer",
                accessToken = "expired-minecraft-token",
            ),
            microsoftRefreshToken = "old-refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = 0,
        )
        store.auth.update {
            selectedIdentityId = account.identity.id
            accounts = listOf(account)
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
        val service = AccountService(accountClient, store)

        val refresh = async { service.refreshIfNeeded(account.identity.id) }
        refreshStarted.await()
        service.delete(account.identity.id)
        releaseRefresh.complete(Unit)

        assertEquals(null, refresh.await())
        assertTrue(store.auth.read { accounts.isEmpty() })
        assertEquals(1, responseIndex)
        accountClient.close()
    }

    @Test
    fun failedRefreshIsRememberedForTheLauncherSession() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val account = StoredAccount(
            identity = MinecraftOnlineIdentity(
                id = Uuid.parse(TEST_PROFILE_ID),
                name = "OnlinePlayer",
                accessToken = "expired-minecraft-token",
            ),
            microsoftRefreshToken = "expired-refresh-token",
            minecraftAccessTokenExpiresAtEpochSeconds = 0,
        )
        store.auth.update {
            selectedIdentityId = account.identity.id
            accounts = listOf(account)
        }
        var requestCount = 0
        val accountClient = HttpClient(
            MockEngine {
                requestCount++
                throw IllegalStateException("refresh rejected")
            },
        )
        val service = AccountService(accountClient, store)

        assertFailsWith<AccountLoginExpiredException> { service.refreshIfNeeded(account.identity.id) }
        assertFailsWith<AccountLoginExpiredException> { service.refreshIfNeeded(account.identity.id) }

        assertEquals(1, requestCount)
        accountClient.close()
    }

    @Test
    fun editingAnOfflineIdentityReplacesItsDerivedIdentityAndSelection() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val accountClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val service = AccountService(accountClient, store)
        val original = service.addOffline("Before")

        val replacement = service.updateOffline(original.identity.id, "After")

        assertEquals(MinecraftOfflineIdentity("After"), replacement.identity)
        val auth = store.auth.read { this }
        assertEquals(replacement.identity.id, auth.selectedIdentityId)
        assertEquals(listOf(replacement), auth.accounts)
        accountClient.close()
    }

    @Test
    fun deletingTheSelectedAccountLeavesTheDefaultIdentityUnpersisted() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val accountClient = HttpClient(MockEngine { error("Offline identity must not trigger HTTP") })
        val service = AccountService(accountClient, store)
        service.addOffline("First")
        val selected = service.addOffline("Second")

        service.delete(selected.identity.id)

        val auth = store.auth.read { this }
        assertEquals(null, auth.selectedIdentityId)
        assertEquals(listOf(MinecraftOfflineIdentity("First")), auth.accounts.map(StoredAccount::identity))
        assertEquals(MinecraftOfflineIdentity(DEFAULT_OFFLINE_PLAYER_NAME), service.selectedIdentity())
        accountClient.close()
    }
}

private const val CACHED_PROFILE_ID = "11111111111111111111111111111111"
