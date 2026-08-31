package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

internal data class LauncherState(
    val launcherDestination: LauncherDestination = LauncherDestination.Home,
    val versionManifestState: VersionManifestState = VersionManifestState.Loading,
    val installedState: InstalledState = InstalledState(),
    val installedMetadata: Map<String, VersionMetadata> = emptyMap(),
    val authState: AuthState? = null,
    val accountCredentials: Map<Uuid, AccountCredentialState> = emptyMap(),
)

internal enum class AccountCredentialState {
    REFRESHING,
    LOGIN_EXPIRED,
}

internal sealed interface VersionManifestState {
    data object Loading : VersionManifestState
    data class Ready(val versionManifest: VersionManifest) : VersionManifestState
    data class Failed(val message: String) : VersionManifestState
}

internal sealed interface LauncherDestination {
    data class Loading(
        val launcherOperation: LauncherOperation,
        val subject: String? = null,
        val cancellable: Boolean = false,
    ) : LauncherDestination

    data class Error(val message: String, val returnTo: LauncherDestination) : LauncherDestination
    data object Home : LauncherDestination
    data object Versions : LauncherDestination
    data class ConfirmInstall(val versionEntry: VersionEntry) : LauncherDestination
    data class PreparingInstall(val versionEntry: VersionEntry) : LauncherDestination
    data class Installing(val versionEntry: VersionEntry) : LauncherDestination
    data object Installed : LauncherDestination
    data class VersionActions(val versionId: String) : LauncherDestination
    data class ConfirmDelete(val versionId: String) : LauncherDestination
    data object Accounts : LauncherDestination
    data object AddAccount : LauncherDestination
    data class AccountActions(val identityId: Uuid) : LauncherDestination
    data class OfflineInput(val replacingIdentityId: Uuid? = null) : LauncherDestination
    data class MicrosoftLogin(
        val microsoftLoginStage: MicrosoftLoginStage,
        val replacingIdentityId: Uuid? = null,
    ) : LauncherDestination

    data class GameOutput(val versionId: String, val gameOutputBuffer: GameOutputBuffer) : LauncherDestination
}

internal enum class LauncherOperation {
    VERSION_MANIFEST,
    DELETE_VERSION,
    SELECT_ACCOUNT,
    DELETE_ACCOUNT,
    SAVE_OFFLINE_IDENTITY,
    REFRESH_ACCOUNT,
    PREPARE_GAME,
}

internal class LauncherController(
    private val coroutineScope: CoroutineScope,
    private val launcherStore: LauncherStore,
    private val installationService: InstallationService,
    private val accountService: AccountService,
    private val processService: GameProcessRuntime,
    private val launcherPlatform: LauncherPlatform,
) {
    private val _state = MutableStateFlow(LauncherState(authState = launcherStore.authMemory.read { this }))
    val state: StateFlow<LauncherState> = _state.asStateFlow()
    val installProgress: StateFlow<InstallProgress> = installationService.progress

    private var activeJob: Job? = null
    private var localStateJob: Job? = null
    private var manifestJob: Job? = null
    private var cancellationDestination: LauncherDestination? = null
    private var manifestReturnDestination: LauncherDestination = LauncherDestination.Home

    fun start() {
        try {
            processService.cleanupStaleArgumentFiles()
        } catch (failure: Throwable) {
            show(LauncherDestination.Error(safeMessage(failure), LauncherDestination.Home))
        }
        loadLocalState()
        loadManifest()
    }

    fun availableVersions(type: String?): List<VersionEntry> {
        val versions =
            (_state.value.versionManifestState as? VersionManifestState.Ready)?.versionManifest?.versions.orEmpty()
        return if (type == null) versions else versions.filter { it.type == type }
    }

    fun installedVersions(): List<InstalledVersion> = _state.value.installedState.installations

    fun showHome() = show(LauncherDestination.Home)

    fun showVersions() {
        when (val versionManifestState = _state.value.versionManifestState) {
            VersionManifestState.Loading -> {
                manifestReturnDestination = _state.value.launcherDestination
                show(manifestLoadingDestination())
            }

            is VersionManifestState.Ready -> show(LauncherDestination.Versions)
            is VersionManifestState.Failed -> loadManifest(waiting = true)
        }
    }

    fun dismissError() {
        val error = _state.value.launcherDestination as? LauncherDestination.Error ?: return
        show(error.returnTo)
    }

    fun confirmInstall(versionEntry: VersionEntry) = show(LauncherDestination.ConfirmInstall(versionEntry))

    fun showInstalled() = show(LauncherDestination.Installed)

    fun showVersionActions(versionId: String) = show(LauncherDestination.VersionActions(versionId))

    fun confirmDelete(versionId: String) = show(LauncherDestination.ConfirmDelete(versionId))

    fun showAccounts() = show(LauncherDestination.Accounts)

    fun showAddAccount() = show(LauncherDestination.AddAccount)

    fun showAccountActions(storedAccount: StoredAccount) =
        show(LauncherDestination.AccountActions(storedAccount.minecraftIdentity.id))

    fun showOfflineInput(storedAccount: StoredAccount? = null) =
        show(LauncherDestination.OfflineInput(storedAccount?.minecraftIdentity?.id))

    fun cancelInstallation() = cancelActive(LauncherDestination.Versions)

    fun cancelMicrosoftLogin() = cancelActive(LauncherDestination.Accounts)

    fun cancelGamePreparation() = cancelActive(LauncherDestination.Installed)

    fun install(versionEntry: VersionEntry) {
        runOperation(LauncherDestination.PreparingInstall(versionEntry)) {
            val completedInstallation = installationService.install(
                versionEntry = versionEntry,
                onDownloadsStarted = { installedState ->
                    _state.update { current ->
                        current.copy(
                            launcherDestination = LauncherDestination.Installing(versionEntry),
                            installedState = installedState,
                            installedMetadata = current.installedMetadata - versionEntry.id,
                        )
                    }
                },
            )
            _state.update { current ->
                current.copy(
                    launcherDestination = LauncherDestination.Installed,
                    installedState = completedInstallation.installedState,
                    installedMetadata = current.installedMetadata + (versionEntry.id to completedInstallation.versionMetadata),
                )
            }
        }
    }

    fun deleteVersion(versionId: String) {
        runOperation(LauncherDestination.Loading(LauncherOperation.DELETE_VERSION, versionId)) {
            installationService.delete(versionId)
            _state.update { current ->
                current.copy(
                    launcherDestination = LauncherDestination.Installed,
                    installedState = current.installedState.copy(
                        installations = current.installedState.installations.filterNot {
                            it.versionId == versionId
                        },
                    ),
                    installedMetadata = current.installedMetadata - versionId,
                )
            }
        }
    }

    fun selectAccount(storedAccount: StoredAccount) {
        runOperation(LauncherDestination.Loading(LauncherOperation.SELECT_ACCOUNT)) {
            accountService.select(storedAccount.minecraftIdentity.id)
            refreshAuth()
        }
    }

    fun deleteAccount(storedAccount: StoredAccount) {
        runOperation(LauncherDestination.Loading(LauncherOperation.DELETE_ACCOUNT)) {
            accountService.delete(storedAccount.minecraftIdentity.id)
            refreshAuth(removeCredentialStates = setOf(storedAccount.minecraftIdentity.id))
        }
    }

    fun saveOfflineIdentity(name: String, replacingIdentityId: Uuid? = null) {
        if (name.isBlank()) return
        runOperation(LauncherDestination.Loading(LauncherOperation.SAVE_OFFLINE_IDENTITY)) {
            val storedAccount = if (replacingIdentityId == null) {
                accountService.addOffline(name)
            } else {
                accountService.updateOffline(replacingIdentityId, name)
            }
            refreshAuth(removeCredentialStates = setOfNotNull(replacingIdentityId, storedAccount.minecraftIdentity.id))
        }
    }

    fun loginMicrosoft(replacingIdentityId: Uuid? = null) {
        runOperation(LauncherDestination.MicrosoftLogin(MicrosoftLoginStage.STARTING_CALLBACK, replacingIdentityId)) {
            val storedAccount = accountService.loginMicrosoft(replacingIdentityId) { microsoftLoginStage ->
                show(LauncherDestination.MicrosoftLogin(microsoftLoginStage, replacingIdentityId))
            }
            refreshAuth(removeCredentialStates = setOfNotNull(replacingIdentityId, storedAccount.minecraftIdentity.id))
        }
    }

    fun launchGame(versionId: String) {
        runOperation(
            LauncherDestination.Loading(LauncherOperation.PREPARE_GAME, versionId, cancellable = true),
        ) {
            val minecraftIdentity = launchIdentity()
            show(LauncherDestination.Loading(LauncherOperation.PREPARE_GAME, versionId, cancellable = true))
            val versionMetadata = loadInstalledMetadata(versionId)
            val installPlan = installationService.validateInstallation(versionMetadata)
            val authState = launcherStore.authMemory.read { this }
            _state.update { it.copy(authState = authState) }
            val launchPlan = MetadataPlanner.createLaunchPlan(
                installPlan,
                launcherStore.gameRoot(versionId),
                launcherPlatform,
                minecraftIdentity,
                authState.installationId,
            )
            val gameOutputBuffer = processService.outputBuffer(launchPlan)
            val outputDestination = LauncherDestination.GameOutput(versionId, gameOutputBuffer)
            show(outputDestination)
            try {
                processService.launch(launchPlan, gameOutputBuffer)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                gameOutputBuffer.append(OutputSource.SYSTEM, "Launch failed: ${safeMessage(failure)}")
                gameOutputBuffer.finish(-1)
                show(LauncherDestination.Error(safeMessage(failure), outputDestination))
            }
        }
    }

    private suspend fun launchIdentity(): MinecraftIdentity {
        val storedAccount = accountService.selectedAccount() ?: return accountService.selectedIdentity()
        if (storedAccount.minecraftIdentity !is MinecraftOnlineIdentity) return storedAccount.minecraftIdentity

        val accountCredentialState = _state.value.accountCredentials[storedAccount.minecraftIdentity.id]
        when (accountCredentialState) {
            AccountCredentialState.LOGIN_EXPIRED -> throw AccountLoginExpiredException(storedAccount.minecraftIdentity.name)
            AccountCredentialState.REFRESHING, null -> Unit
        }
        val wasRefreshing = accountCredentialState == AccountCredentialState.REFRESHING
        if (wasRefreshing || accountService.needsRefresh(storedAccount)) {
            show(
                LauncherDestination.Loading(
                    LauncherOperation.REFRESH_ACCOUNT,
                    storedAccount.minecraftIdentity.name,
                    cancellable = true,
                ),
            )
            try {
                return requireNotNull(refreshAccount(storedAccount.minecraftIdentity.id)).minecraftIdentity
            } catch (failure: CancellationException) {
                if (!wasRefreshing) updateCredentialState(storedAccount.minecraftIdentity.id, null)
                throw failure
            }
        }
        return storedAccount.minecraftIdentity
    }

    private suspend fun refreshAccount(identityId: Uuid): StoredAccount? {
        updateCredentialState(identityId, AccountCredentialState.REFRESHING)
        return try {
            val storedAccount = accountService.refreshIfNeeded(identityId)
            val authState = launcherStore.authMemory.read { this }
            val identityIds = authState.accounts.mapTo(mutableSetOf()) { it.minecraftIdentity.id }
            _state.update { current ->
                current.copy(
                    authState = authState,
                    accountCredentials = current.accountCredentials.filterKeys { key ->
                        key != identityId && key in identityIds
                    },
                )
            }
            storedAccount
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (launcherStore.authMemory.read { accounts.none { it.minecraftIdentity.id == identityId } }) {
                updateCredentialState(identityId, null)
                null
            } else {
                updateCredentialState(identityId, AccountCredentialState.LOGIN_EXPIRED)
                throw failure
            }
        }
    }

    private fun updateCredentialState(identityId: Uuid, accountCredentialState: AccountCredentialState?) {
        _state.update { current ->
            current.copy(
                accountCredentials = if (accountCredentialState == null) {
                    current.accountCredentials - identityId
                } else {
                    current.accountCredentials + (identityId to accountCredentialState)
                },
            )
        }
    }

    private fun loadLocalState() {
        localStateJob?.cancel()
        localStateJob = coroutineScope.launch {
            try {
                val authState = launcherStore.authMemory.read { this }
                val installedState = installationService.loadInstalled()
                val expiredAccounts = authState.accounts.filter(accountService::needsRefresh)
                _state.update { current ->
                    current.copy(
                        authState = authState,
                        installedState = installedState,
                        accountCredentials = expiredAccounts.associate { storedAccount ->
                            storedAccount.minecraftIdentity.id to AccountCredentialState.REFRESHING
                        },
                    )
                }
                expiredAccounts.forEach { storedAccount ->
                    coroutineScope.launch {
                        try {
                            refreshAccount(storedAccount.minecraftIdentity.id)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            // Background refresh failures are exposed through the account state and retried by sign-in.
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                _state.update { current ->
                    current.copy(
                        launcherDestination = LauncherDestination.Error(
                            safeMessage(failure),
                            current.launcherDestination
                        )
                    )
                }
            }
        }
    }

    private fun loadManifest(waiting: Boolean = false) {
        manifestJob?.cancel()
        if (waiting) manifestReturnDestination = _state.value.launcherDestination
        _state.update { current ->
            current.copy(
                versionManifestState = VersionManifestState.Loading,
                launcherDestination = if (waiting) manifestLoadingDestination() else current.launcherDestination,
            )
        }
        manifestJob = coroutineScope.launch {
            try {
                val versionManifest = installationService.loadManifest()
                _state.update { current ->
                    current.copy(
                        versionManifestState = VersionManifestState.Ready(versionManifest),
                        launcherDestination = if (current.launcherDestination.isWaitingForManifest()) {
                            LauncherDestination.Versions
                        } else {
                            current.launcherDestination
                        },
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val message = safeMessage(failure)
                _state.update { current ->
                    current.copy(
                        versionManifestState = VersionManifestState.Failed(message),
                        launcherDestination = if (current.launcherDestination.isWaitingForManifest()) {
                            LauncherDestination.Error(message, manifestReturnDestination)
                        } else {
                            current.launcherDestination
                        },
                    )
                }
            }
        }
    }

    private suspend fun loadInstalledMetadata(versionId: String): VersionMetadata {
        _state.value.installedMetadata[versionId]?.let { return it }
        manifestJob?.join()
        val versionManifest = (_state.value.versionManifestState as? VersionManifestState.Ready)?.versionManifest
            ?: throw IllegalStateException("The official version manifest is unavailable")
        val versionEntry = versionManifest.versions.singleOrNull { it.id == versionId }
            ?: throw IllegalStateException("Version $versionId is absent from the official manifest")
        val versionMetadata = installationService.loadVersionMetadata(versionEntry)
        _state.update { it.copy(installedMetadata = it.installedMetadata + (versionId to versionMetadata)) }
        return versionMetadata
    }

    private suspend fun refreshAuth(removeCredentialStates: Set<Uuid> = emptySet()) {
        val authState = launcherStore.authMemory.read { this }
        val identityIds = authState.accounts.mapTo(mutableSetOf()) { it.minecraftIdentity.id }
        _state.update { current ->
            current.copy(
                launcherDestination = LauncherDestination.Accounts,
                authState = authState,
                accountCredentials = current.accountCredentials.filterKeys { identityId ->
                    identityId in identityIds && identityId !in removeCredentialStates
                },
            )
        }
    }

    private fun runOperation(
        initialDestination: LauncherDestination,
        operation: suspend () -> Unit,
    ) {
        activeJob?.cancel()
        cancellationDestination = null
        val returnDestination = _state.value.launcherDestination
        show(initialDestination)
        activeJob = coroutineScope.launch {
            try {
                operation()
            } catch (failure: CancellationException) {
                cancellationDestination?.let(::show)
                throw failure
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(launcherDestination = LauncherDestination.Error(safeMessage(failure), returnDestination))
                }
            }
        }
    }

    private fun cancelActive(launcherDestination: LauncherDestination) {
        cancellationDestination = launcherDestination
        activeJob?.cancel()
    }

    private fun show(launcherDestination: LauncherDestination) {
        _state.update { it.copy(launcherDestination = launcherDestination) }
    }

    private fun safeMessage(failure: Throwable): String {
        val message = failure.toString().takeIf(String::isNotBlank) ?: "Operation failed"
        val secrets = _state.value.authState?.accounts.orEmpty().flatMap { storedAccount ->
            listOfNotNull(
                storedAccount.microsoftRefreshToken,
                (storedAccount.minecraftIdentity as? MinecraftOnlineIdentity)?.accessToken,
            )
        }
        return redactSecrets(sanitizeTerminalText(message), secrets)
    }
}

private fun manifestLoadingDestination() = LauncherDestination.Loading(
    launcherOperation = LauncherOperation.VERSION_MANIFEST,
    cancellable = true,
)

private fun LauncherDestination.isWaitingForManifest(): Boolean =
    this is LauncherDestination.Loading && launcherOperation == LauncherOperation.VERSION_MANIFEST
