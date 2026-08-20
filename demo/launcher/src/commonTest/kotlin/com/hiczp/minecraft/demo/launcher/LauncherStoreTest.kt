package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class LauncherStoreTest {
    @Test
    fun authAndInstalledStateRoundTripAtomically() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher root".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val initial = store.auth.read { this }

        val account = StoredAccount(MinecraftOfflineIdentity("Player"))
        store.auth.update {
            selectedIdentityId = account.identity.id
            accounts = listOf(account)
        }
        store.updateInstalled { it.copy(installations = listOf(InstalledVersion("demo", "windows-x86_64"))) }

        val reloaded = LauncherStore(fileSystem, root)
        assertEquals(initial.installationId, reloaded.auth.read { installationId })
        assertEquals(account, reloaded.auth.read { accounts.single() })
        val authText = fileSystem.read(root / "auth.json") { readUtf8() }
        val authDocument = launcherJson.parseToJsonElement(authText).jsonObject
        val persistedIdentity = authDocument.getValue("accounts").jsonArray
            .single()
            .jsonObject
            .getValue("identity")
        assertEquals(buildJsonObject { put("name", "Player") }, persistedIdentity)
        assertEquals("demo", reloaded.loadInstalled().installations.single().versionId)
        assertFalse(fileSystem.exists(root / "auth.json.tmp"))
        assertFalse(fileSystem.exists(root / "installed.json.tmp"))
    }

    @Test
    fun malformedAuthIsNotOverwritten() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        fileSystem.write(root / "auth.json") { writeUtf8("{broken") }

        assertFails { LauncherStore(fileSystem, root) }
        assertEquals("{broken", fileSystem.read(root / "auth.json") { readUtf8() })
    }

    @Test
    fun authReadsUseMemoryUntilAnUpdatePersistsTheNewState() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        val installationId = store.auth.read { installationId }
        fileSystem.write(root / "auth.json") { writeUtf8("{changed-outside-the-store") }

        assertEquals(installationId, store.auth.read { installationId })

        val account = StoredAccount(MinecraftOfflineIdentity("Alex"))
        store.auth.update { accounts = listOf(account) }

        val accounts = store.auth.read { accounts }
        assertEquals(listOf(account), accounts)
        assertEquals(accounts, LauncherStore(fileSystem, root).auth.read { accounts })
    }

    @Test
    fun reconcileOnlyRemovesMissingRecordsForCurrentPlatform() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fileSystem.createDirectories(root)
        val store = LauncherStore(fileSystem, root)
        fileSystem.createDirectories(store.gameRoot("present"))
        store.updateInstalled {
            it.copy(
                installations = listOf(
                    InstalledVersion("present", "windows-x86_64"),
                    InstalledVersion("missing", "windows-x86_64"),
                    InstalledVersion("other", "linux-x86_64"),
                ),
            )
        }

        val reconciled = store.reconcileInstalled(LauncherPlatform("windows", "x86_64", ";", "windows-x86_64"))

        assertTrue(reconciled.installations.any { it.versionId == "present" })
        assertFalse(reconciled.installations.any { it.versionId == "missing" })
        assertTrue(reconciled.installations.any { it.versionId == "other" })
    }
}
