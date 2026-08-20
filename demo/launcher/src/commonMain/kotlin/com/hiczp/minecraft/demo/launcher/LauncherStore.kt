package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.uuid.Uuid

internal val launcherJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

internal class LauncherStore(
    private val fileSystem: FileSystem,
    private val launcherRoot: Path,
) {
    private val installedMutex = Mutex()
    private val installedPath = launcherRoot / "installed.json"
    val auth = AuthMemory(fileSystem, launcherRoot / "auth.json")

    suspend fun loadInstalled(): InstalledState = installedMutex.withLock {
        if (!fileSystem.exists(installedPath)) return@withLock InstalledState()
        readInstalledUnlocked()
    }

    suspend fun updateInstalled(transform: (InstalledState) -> InstalledState): InstalledState =
        installedMutex.withLock {
            val updated = transform(if (fileSystem.exists(installedPath)) readInstalledUnlocked() else InstalledState())
            validateInstalled(updated)
            writeJsonAtomically(fileSystem, installedPath, launcherJson.encodeToString(updated))
            updated
        }

    suspend fun reconcileInstalled(platform: LauncherPlatform): InstalledState = updateInstalled { state ->
        state.copy(
            installations = state.installations.filter { installation ->
                installation.platformKey != platform.platformKey ||
                        fileSystem.exists(gameRoot(installation.versionId))
            },
        )
    }

    fun gameRoot(versionId: String): Path = launcherRoot / "minecraft" /
            validateSinglePathComponent(versionId, "version ID")

    fun root(): Path = launcherRoot

    private fun readInstalledUnlocked(): InstalledState {
        val state = launcherJson.decodeFromString<InstalledState>(fileSystem.read(installedPath) { readUtf8() })
        validateInstalled(state)
        return state
    }
}

internal class AuthMemory(
    private val fileSystem: FileSystem,
    private val path: Path,
) {
    private val mutex = Mutex()
    private val memory = MutableStateFlow(loadInitialState())

    fun <T> read(block: AuthState.() -> T): T = memory.value.block()

    suspend fun <T> update(block: AuthUpdateScope.() -> T): T = mutex.withLock {
        val current = memory.value
        val scope = AuthUpdateScope(current)
        val result = scope.block()
        val updated = scope.build().ownedCopy()
        validateAuth(updated)
        if (updated != current) {
            writeJsonAtomically(fileSystem, path, launcherJson.encodeToString(updated))
            memory.value = updated
        }
        result
    }

    private fun loadInitialState(): AuthState {
        val loaded = if (fileSystem.exists(path)) {
            launcherJson.decodeFromString<AuthState>(fileSystem.read(path) { readUtf8() })
        } else {
            AuthState(installationId = Uuid.random()).also { initial ->
                writeJsonAtomically(fileSystem, path, launcherJson.encodeToString(initial))
            }
        }
        validateAuth(loaded)
        return loaded.ownedCopy()
    }
}

internal class AuthUpdateScope(initial: AuthState) {
    var installationId: Uuid = initial.installationId
    var selectedIdentityId: Uuid? = initial.selectedIdentityId
    var accounts: List<StoredAccount> = initial.accounts

    internal fun build(): AuthState = AuthState(
        installationId = installationId,
        selectedIdentityId = selectedIdentityId,
        accounts = accounts,
    )
}

private fun AuthState.ownedCopy(): AuthState = copy(accounts = accounts.toList())

private fun writeJsonAtomically(fileSystem: FileSystem, path: Path, content: String) {
    fileSystem.createDirectories(checkNotNull(path.parent))
    val temporary = checkNotNull(path.parent) / "${path.name}.tmp"
    try {
        fileSystem.write(temporary, mustCreate = false) {
            writeUtf8(content)
            flush()
        }
        fileSystem.atomicMove(temporary, path)
    } finally {
        if (fileSystem.exists(temporary)) fileSystem.delete(temporary)
    }
}

private fun validateAuth(state: AuthState) {
    require(state.schemaVersion == 2) { "Unsupported auth.json schema: ${state.schemaVersion}" }
    require(state.accounts.map { it.identity.id }.distinct().size == state.accounts.size) {
        "auth.json contains duplicate identities"
    }
    require(state.selectedIdentityId == null || state.accounts.any { it.identity.id == state.selectedIdentityId }) {
        "auth.json selected identity does not exist"
    }
    state.accounts.forEach { account ->
        when (account.identity) {
            is MinecraftOfflineIdentity -> require(
                account.microsoftRefreshToken == null && account.minecraftAccessTokenExpiresAtEpochSeconds == null,
            ) { "Offline accounts cannot contain credentials" }

            is MinecraftOnlineIdentity -> require(
                account.microsoftRefreshToken != null && account.minecraftAccessTokenExpiresAtEpochSeconds != null,
            ) { "Microsoft account is missing its refresh credentials" }
        }
    }
}

private fun validateInstalled(state: InstalledState) {
    require(state.schemaVersion == 1) { "Unsupported installed.json schema: ${state.schemaVersion}" }
    state.installations.forEach { installation ->
        validateSinglePathComponent(installation.versionId, "version ID")
        require(installation.platformKey.isNotBlank()) { "installed.json has a blank platform key" }
    }
    require(state.installations.distinct().size == state.installations.size) {
        "installed.json contains duplicate records"
    }
}
