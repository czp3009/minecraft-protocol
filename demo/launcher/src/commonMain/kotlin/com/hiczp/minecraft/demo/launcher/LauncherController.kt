@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
    val destination: LauncherDestination = LauncherDestination.Home,
    val manifest: VersionManifestState = VersionManifestState.Loading,
    val installed: InstalledState = InstalledState(),
    val installedMetadata: Map<String, VersionMetadata> = emptyMap(),
    val auth: AuthState? = null,
    val accountCredentials: Map<Uuid, AccountCredentialState> = emptyMap(),
)

internal enum class AccountCredentialState {
    REFRESHING,
    LOGIN_EXPIRED,
}

internal sealed interface VersionManifestState {
    data object Loading : VersionManifestState
    data class Ready(val manifest: VersionManifest) : VersionManifestState
    data class Failed(val message: String) : VersionManifestState
}

internal sealed interface LauncherDestination {
    data class Loading(
        val operation: LauncherOperation,
        val subject: String? = null,
        val cancellable: Boolean = false,
    ) : LauncherDestination

    data class Error(val message: String, val returnTo: LauncherDestination) : LauncherDestination
    data object Home : LauncherDestination
    data object Versions : LauncherDestination
    data class ConfirmInstall(val entry: VersionEntry) : LauncherDestination
    data class PreparingInstall(val entry: VersionEntry) : LauncherDestination
    data class Installing(val entry: VersionEntry) : LauncherDestination
    data object Installed : LauncherDestination
    data class VersionActions(val versionId: String) : LauncherDestination
    data class ConfirmDelete(val versionId: String) : LauncherDestination
    data object Accounts : LauncherDestination
    data object AddAccount : LauncherDestination
    data class AccountActions(val identityId: Uuid) : LauncherDestination
    data class OfflineInput(val replacingIdentityId: Uuid? = null) : LauncherDestination
    data class MicrosoftLogin(
        val stage: MicrosoftLoginStage,
        val replacingIdentityId: Uuid? = null,
    ) : LauncherDestination

    data class GameOutput(val versionId: String, val output: GameOutputBuffer) : LauncherDestination
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
    private val scope: CoroutineScope,
    private val store: LauncherStore,
    private val installationService: InstallationService,
    private val accountService: AccountService,
    private val processService: GameProcessRuntime,
    private val platform: LauncherPlatform,
) {
    private val _state = MutableStateFlow(LauncherState(auth = store.auth.read { this }))
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
        val versions = (_state.value.manifest as? VersionManifestState.Ready)?.manifest?.versions.orEmpty()
        return if (type == null) versions else versions.filter { it.type == type }
    }

    fun installedVersions(): List<InstalledVersion> = _state.value.installed.installations
        .filter { it.platformKey == platform.platformKey }

    fun showHome() = show(LauncherDestination.Home)

    fun showVersions() {
        when (val manifest = _state.value.manifest) {
            VersionManifestState.Loading -> {
                manifestReturnDestination = _state.value.destination
                show(manifestLoadingDestination())
            }

            is VersionManifestState.Ready -> show(LauncherDestination.Versions)
            is VersionManifestState.Failed -> loadManifest(waiting = true)
        }
    }

    fun dismissError() {
        val error = _state.value.destination as? LauncherDestination.Error ?: return
        show(error.returnTo)
    }

    fun confirmInstall(entry: VersionEntry) = show(LauncherDestination.ConfirmInstall(entry))

    fun showInstalled() = show(LauncherDestination.Installed)

    fun showVersionActions(versionId: String) = show(LauncherDestination.VersionActions(versionId))

    fun confirmDelete(versionId: String) = show(LauncherDestination.ConfirmDelete(versionId))

    fun showAccounts() = show(LauncherDestination.Accounts)

    fun showAddAccount() = show(LauncherDestination.AddAccount)

    fun showAccountActions(account: StoredAccount) = show(LauncherDestination.AccountActions(account.identity.id))

    fun showOfflineInput(account: StoredAccount? = null) =
        show(LauncherDestination.OfflineInput(account?.identity?.id))

    fun cancelInstallation() = cancelActive(LauncherDestination.Versions)

    fun cancelMicrosoftLogin() = cancelActive(LauncherDestination.Accounts)

    fun cancelGamePreparation() = cancelActive(LauncherDestination.Installed)

    fun install(entry: VersionEntry) {
        runOperation(LauncherDestination.PreparingInstall(entry)) {
            val prepared = installationService.prepareInstallation(entry)
            show(LauncherDestination.Installing(entry))
            val metadata = installationService.install(prepared)
            val record = InstalledVersion(entry.id, platform.platformKey)
            _state.update { current ->
                current.copy(
                    destination = LauncherDestination.Installed,
                    installed = current.installed.copy(
                        installations = current.installed.installations.filterNot { it == record } + record,
                    ),
                    installedMetadata = current.installedMetadata + (entry.id to metadata),
                )
            }
        }
    }

    fun deleteVersion(versionId: String) {
        runOperation(LauncherDestination.Loading(LauncherOperation.DELETE_VERSION, versionId)) {
            installationService.delete(versionId)
            _state.update { current ->
                current.copy(
                    destination = LauncherDestination.Installed,
                    installed = current.installed.copy(
                        installations = current.installed.installations.filterNot {
                            it.versionId == versionId && it.platformKey == platform.platformKey
                        },
                    ),
                    installedMetadata = current.installedMetadata - versionId,
                )
            }
        }
    }

    fun selectAccount(account: StoredAccount) {
        runOperation(LauncherDestination.Loading(LauncherOperation.SELECT_ACCOUNT)) {
            accountService.select(account.identity.id)
            refreshAuth()
        }
    }

    fun deleteAccount(account: StoredAccount) {
        runOperation(LauncherDestination.Loading(LauncherOperation.DELETE_ACCOUNT)) {
            accountService.delete(account.identity.id)
            refreshAuth(removeCredentialStates = setOf(account.identity.id))
        }
    }

    fun saveOfflineIdentity(name: String, replacingIdentityId: Uuid? = null) {
        if (name.isBlank()) return
        runOperation(LauncherDestination.Loading(LauncherOperation.SAVE_OFFLINE_IDENTITY)) {
            val account = if (replacingIdentityId == null) {
                accountService.addOffline(name)
            } else {
                accountService.updateOffline(replacingIdentityId, name)
            }
            refreshAuth(removeCredentialStates = setOfNotNull(replacingIdentityId, account.identity.id))
        }
    }

    fun loginMicrosoft(replacingIdentityId: Uuid? = null) {
        runOperation(LauncherDestination.MicrosoftLogin(MicrosoftLoginStage.STARTING_CALLBACK, replacingIdentityId)) {
            val account = accountService.loginMicrosoft(replacingIdentityId) { stage ->
                show(LauncherDestination.MicrosoftLogin(stage, replacingIdentityId))
            }
            refreshAuth(removeCredentialStates = setOfNotNull(replacingIdentityId, account.identity.id))
        }
    }

    fun launchGame(versionId: String) {
        runOperation(
            LauncherDestination.Loading(LauncherOperation.PREPARE_GAME, versionId, cancellable = true),
        ) {
            val identity = launchIdentity()
            show(LauncherDestination.Loading(LauncherOperation.PREPARE_GAME, versionId, cancellable = true))
            val metadata = loadInstalledMetadata(versionId)
            val installPlan = installationService.validateInstallation(metadata)
            val auth = store.auth.read { this }
            _state.update { it.copy(auth = auth) }
            val launchPlan = MetadataPlanner.createLaunchPlan(
                installPlan,
                store.gameRoot(versionId),
                platform,
                identity,
                auth.installationId,
            )
            val output = processService.outputBuffer(launchPlan)
            val outputDestination = LauncherDestination.GameOutput(versionId, output)
            show(outputDestination)
            try {
                processService.launch(launchPlan, output)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                output.append(OutputSource.SYSTEM, "Launch failed: ${safeMessage(failure)}")
                output.finish(-1)
                show(LauncherDestination.Error(safeMessage(failure), outputDestination))
            }
        }
    }

    private suspend fun launchIdentity(): MinecraftIdentity {
        val account = accountService.selectedAccount() ?: return accountService.selectedIdentity()
        if (account.identity !is MinecraftOnlineIdentity) return account.identity

        val credentialState = _state.value.accountCredentials[account.identity.id]
        when (credentialState) {
            AccountCredentialState.LOGIN_EXPIRED -> throw AccountLoginExpiredException(account.identity.name)
            AccountCredentialState.REFRESHING, null -> Unit
        }
        val wasRefreshing = credentialState == AccountCredentialState.REFRESHING
        if (wasRefreshing || accountService.needsRefresh(account)) {
            show(
                LauncherDestination.Loading(
                    LauncherOperation.REFRESH_ACCOUNT,
                    account.identity.name,
                    cancellable = true,
                ),
            )
            try {
                return requireNotNull(refreshAccount(account.identity.id)).identity
            } catch (failure: CancellationException) {
                if (!wasRefreshing) updateCredentialState(account.identity.id, null)
                throw failure
            }
        }
        return account.identity
    }

    private suspend fun refreshAccount(identityId: Uuid): StoredAccount? {
        updateCredentialState(identityId, AccountCredentialState.REFRESHING)
        return try {
            val account = accountService.refreshIfNeeded(identityId)
            val auth = store.auth.read { this }
            val identityIds = auth.accounts.mapTo(mutableSetOf()) { it.identity.id }
            _state.update { current ->
                current.copy(
                    auth = auth,
                    accountCredentials = current.accountCredentials.filterKeys { key ->
                        key != identityId && key in identityIds
                    },
                )
            }
            account
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (store.auth.read { accounts.none { it.identity.id == identityId } }) {
                updateCredentialState(identityId, null)
                null
            } else {
                updateCredentialState(identityId, AccountCredentialState.LOGIN_EXPIRED)
                throw failure
            }
        }
    }

    private fun updateCredentialState(identityId: Uuid, credentialState: AccountCredentialState?) {
        _state.update { current ->
            current.copy(
                accountCredentials = if (credentialState == null) {
                    current.accountCredentials - identityId
                } else {
                    current.accountCredentials + (identityId to credentialState)
                },
            )
        }
    }

    private fun loadLocalState() {
        localStateJob?.cancel()
        localStateJob = scope.launch {
            try {
                val auth = store.auth.read { this }
                val installed = installationService.loadInstalled()
                val expiredAccounts = auth.accounts.filter(accountService::needsRefresh)
                _state.update { current ->
                    current.copy(
                        auth = auth,
                        installed = installed,
                        accountCredentials = expiredAccounts.associate { account ->
                            account.identity.id to AccountCredentialState.REFRESHING
                        },
                    )
                }
                expiredAccounts.forEach { account ->
                    scope.launch {
                        try {
                            refreshAccount(account.identity.id)
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
                    current.copy(destination = LauncherDestination.Error(safeMessage(failure), current.destination))
                }
            }
        }
    }

    private fun loadManifest(waiting: Boolean = false) {
        manifestJob?.cancel()
        if (waiting) manifestReturnDestination = _state.value.destination
        _state.update { current ->
            current.copy(
                manifest = VersionManifestState.Loading,
                destination = if (waiting) manifestLoadingDestination() else current.destination,
            )
        }
        manifestJob = scope.launch {
            try {
                val manifest = installationService.loadManifest()
                _state.update { current ->
                    current.copy(
                        manifest = VersionManifestState.Ready(manifest),
                        destination = if (current.destination.isWaitingForManifest()) {
                            LauncherDestination.Versions
                        } else {
                            current.destination
                        },
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val message = safeMessage(failure)
                _state.update { current ->
                    current.copy(
                        manifest = VersionManifestState.Failed(message),
                        destination = if (current.destination.isWaitingForManifest()) {
                            LauncherDestination.Error(message, manifestReturnDestination)
                        } else {
                            current.destination
                        },
                    )
                }
            }
        }
    }

    private suspend fun loadInstalledMetadata(versionId: String): VersionMetadata {
        _state.value.installedMetadata[versionId]?.let { return it }
        manifestJob?.join()
        val manifest = (_state.value.manifest as? VersionManifestState.Ready)?.manifest
            ?: throw IllegalStateException("The official version manifest is unavailable")
        val entry = manifest.versions.singleOrNull { it.id == versionId }
            ?: throw IllegalStateException("Version $versionId is absent from the official manifest")
        val metadata = installationService.loadVersionMetadata(entry)
        _state.update { it.copy(installedMetadata = it.installedMetadata + (versionId to metadata)) }
        return metadata
    }

    private suspend fun refreshAuth(removeCredentialStates: Set<Uuid> = emptySet()) {
        val auth = store.auth.read { this }
        val identityIds = auth.accounts.mapTo(mutableSetOf()) { it.identity.id }
        _state.update { current ->
            current.copy(
                destination = LauncherDestination.Accounts,
                auth = auth,
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
        val returnDestination = _state.value.destination
        show(initialDestination)
        activeJob = scope.launch {
            try {
                operation()
            } catch (failure: CancellationException) {
                cancellationDestination?.let(::show)
                throw failure
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(destination = LauncherDestination.Error(safeMessage(failure), returnDestination))
                }
            }
        }
    }

    private fun cancelActive(destination: LauncherDestination) {
        cancellationDestination = destination
        activeJob?.cancel()
    }

    private fun show(destination: LauncherDestination) {
        _state.update { it.copy(destination = destination) }
    }

    private fun safeMessage(failure: Throwable): String {
        val message = failure.toString().takeIf(String::isNotBlank) ?: "Operation failed"
        val secrets = _state.value.auth?.accounts.orEmpty().flatMap { account ->
            listOfNotNull(
                account.microsoftRefreshToken,
                (account.identity as? MinecraftOnlineIdentity)?.accessToken,
            )
        }
        return redactSecrets(sanitizeTerminalText(message), secrets)
    }
}

private fun manifestLoadingDestination() = LauncherDestination.Loading(
    operation = LauncherOperation.VERSION_MANIFEST,
    cancellable = true,
)

private fun LauncherDestination.isWaitingForManifest(): Boolean =
    this is LauncherDestination.Loading && operation == LauncherOperation.VERSION_MANIFEST
