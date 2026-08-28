package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class LauncherStoreTest {
    @Test
    fun authAndInstalledStateRoundTripAtomically() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher root".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val initial = launcherStore.authMemory.read { this }

        val storedAccount = StoredAccount(MinecraftOfflineIdentity("Player"))
        launcherStore.authMemory.update {
            selectedIdentityId = storedAccount.minecraftIdentity.id
            accounts = listOf(storedAccount)
        }
        launcherStore.updateInstalled { it.copy(installations = listOf(InstalledVersion("demo"))) }

        val reloaded = LauncherStore(fakeFileSystem, root)
        assertEquals(initial.installationId, reloaded.authMemory.read { installationId })
        assertEquals(storedAccount, reloaded.authMemory.read { accounts.single() })
        val authText = fakeFileSystem.read(root / "auth.json") { readUtf8() }
        val authDocument = launcherJson.parseToJsonElement(authText).jsonObject
        assertFalse("schemaVersion" in authDocument)
        val persistedIdentity = authDocument.getValue("accounts").jsonArray
            .single()
            .jsonObject
            .getValue("minecraftIdentity")
        assertEquals(buildJsonObject { put("name", "Player") }, persistedIdentity)
        val installedText = fakeFileSystem.read(root / "installed.json") { readUtf8() }
        val installedDocument = launcherJson.parseToJsonElement(installedText)
        assertEquals(
            buildJsonObject {
                put(
                    "installations",
                    buildJsonArray {
                        add(buildJsonObject { put("versionId", "demo") })
                    },
                )
            },
            installedDocument,
        )
        assertEquals("demo", reloaded.loadInstalled().installations.single().versionId)
        assertFalse(fakeFileSystem.exists(root / "auth.json.tmp"))
        assertFalse(fakeFileSystem.exists(root / "installed.json.tmp"))
    }

    @Test
    fun malformedAuthIsNotOverwritten() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        fakeFileSystem.write(root / "auth.json") { writeUtf8("{broken") }

        assertFails { LauncherStore(fakeFileSystem, root) }
        assertEquals("{broken", fakeFileSystem.read(root / "auth.json") { readUtf8() })
    }

    @Test
    fun authReadsUseMemoryUntilAnUpdatePersistsTheNewState() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        val installationId = launcherStore.authMemory.read { installationId }
        fakeFileSystem.write(root / "auth.json") { writeUtf8("{changed-outside-the-store") }

        assertEquals(installationId, launcherStore.authMemory.read { installationId })

        val storedAccount = StoredAccount(MinecraftOfflineIdentity("Alex"))
        launcherStore.authMemory.update { accounts = listOf(storedAccount) }

        val accounts = launcherStore.authMemory.read { accounts }
        assertEquals(listOf(storedAccount), accounts)
        assertEquals(accounts, LauncherStore(fakeFileSystem, root).authMemory.read { accounts })
    }

    @Test
    fun reconcileRemovesEveryRecordWhoseVersionDirectoryIsMissing() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/launcher".toPath()
        fakeFileSystem.createDirectories(root)
        val launcherStore = LauncherStore(fakeFileSystem, root)
        fakeFileSystem.createDirectories(launcherStore.gameRoot("present"))
        launcherStore.updateInstalled {
            it.copy(
                installations = listOf(
                    InstalledVersion("present"),
                    InstalledVersion("missing"),
                ),
            )
        }

        val reconciled = launcherStore.reconcileInstalled()

        assertEquals(listOf(InstalledVersion("present")), reconciled.installations)
    }
}
