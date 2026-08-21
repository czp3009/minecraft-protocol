package com.hiczp.minecraft.demo.launcher.ui

import androidx.compose.runtime.*
import com.hiczp.minecraft.demo.launcher.LauncherController
import com.hiczp.minecraft.demo.launcher.LauncherDestination
import com.hiczp.minecraft.demo.launcher.LauncherOperation
import com.hiczp.minecraft.demo.launcher.LauncherPlatform
import com.jakewharton.mosaic.LocalTerminalState
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun LauncherApplication(controller: LauncherController, platform: LauncherPlatform) {
    var running by remember { mutableStateOf(true) }
    if (!running) return

    val state by controller.state.collectAsState()
    val progress by controller.installProgress.collectAsState()
    val terminal = LocalTerminalState.current.size
    val visibleRows = (terminal.rows - MENU_RESERVED_ROWS).coerceAtLeast(1)
    val exit = { running = false }
    when (val destination = state.destination) {
        is LauncherDestination.Loading -> LoadingScreen(
            destination = destination,
            platform = platform.platformKey,
            onCancel = when (destination.operation) {
                LauncherOperation.PREPARE_GAME, LauncherOperation.REFRESH_ACCOUNT ->
                    controller::cancelGamePreparation

                LauncherOperation.VERSION_MANIFEST -> controller::showHome
                else -> null
            },
        )

        is LauncherDestination.Error -> ErrorScreen(
            destination,
            platform.platformKey,
            controller::dismissError,
            exit,
        )

        LauncherDestination.Home -> HomeScreen(
            state.auth,
            platform.platformKey,
            visibleRows,
            controller::showVersions,
            controller::showInstalled,
            controller::showAccounts,
            exit,
        )

        LauncherDestination.Versions -> VersionsScreen(
            platform.platformKey,
            visibleRows,
            controller::availableVersions,
            state.installed.installations
                .filter { it.platformKey == platform.platformKey }
                .mapTo(mutableSetOf()) { it.versionId },
            controller::confirmInstall,
            controller::showHome,
        )

        is LauncherDestination.ConfirmInstall -> ConfirmInstallScreen(
            destination.entry,
            state.installed.installations.any {
                it.versionId == destination.entry.id && it.platformKey == platform.platformKey
            },
            platform.platformKey,
            onInstall = { controller.install(destination.entry) },
            onBack = controller::showVersions,
        )

        is LauncherDestination.PreparingInstall -> PreparingInstallScreen(
            destination.entry,
            platform.platformKey,
            controller::cancelInstallation,
        )

        is LauncherDestination.Installing -> InstallingScreen(
            destination.entry,
            progress,
            platform.platformKey,
            controller::cancelInstallation,
        )

        LauncherDestination.Installed -> InstalledScreen(
            controller.installedVersions(),
            platform.platformKey,
            visibleRows,
            onOpen = { controller.showVersionActions(it.versionId) },
            onBack = controller::showHome,
        )

        is LauncherDestination.VersionActions -> VersionActionsScreen(
            destination.versionId,
            platform.platformKey,
            visibleRows,
            onLaunch = { controller.launchGame(destination.versionId) },
            onDelete = { controller.confirmDelete(destination.versionId) },
            onBack = controller::showInstalled,
        )

        is LauncherDestination.ConfirmDelete -> ConfirmDeleteScreen(
            destination.versionId,
            platform.platformKey,
            onDelete = { controller.deleteVersion(destination.versionId) },
            onBack = { controller.showVersionActions(destination.versionId) },
        )

        LauncherDestination.Accounts -> AccountsScreen(
            state.auth,
            state.accountCredentials,
            platform.platformKey,
            visibleRows,
            controller::showAddAccount,
            controller::showAccountActions,
            controller::showHome,
        )

        LauncherDestination.AddAccount -> AddAccountScreen(
            platform.platformKey,
            visibleRows,
            onAddMicrosoft = { controller.loginMicrosoft() },
            onAddOffline = { controller.showOfflineInput() },
            onBack = controller::showAccounts,
        )

        is LauncherDestination.AccountActions -> AccountActionsScreen(
            account = state.auth?.accounts?.singleOrNull { it.identity.id == destination.identityId },
            credentialState = state.accountCredentials[destination.identityId],
            selectedIdentityId = state.auth?.selectedIdentityId,
            platform = platform.platformKey,
            visibleRows = visibleRows,
            onSelect = controller::selectAccount,
            onEditOffline = controller::showOfflineInput,
            onSignInAgain = { controller.loginMicrosoft(destination.identityId) },
            onDelete = controller::deleteAccount,
            onBack = controller::showAccounts,
        )

        is LauncherDestination.OfflineInput -> {
            val account = state.auth?.accounts?.singleOrNull { it.identity.id == destination.replacingIdentityId }
            OfflineInputScreen(
                platform = platform.platformKey,
                initialName = account?.identity?.name.orEmpty(),
                editing = destination.replacingIdentityId != null,
                onSave = { name -> controller.saveOfflineIdentity(name, destination.replacingIdentityId) },
                onBack = controller::showAccounts,
            )
        }

        is LauncherDestination.MicrosoftLogin -> MicrosoftLoginScreen(
            destination.stage,
            platform.platformKey,
            controller::cancelMicrosoftLogin,
        )

        is LauncherDestination.GameOutput -> GameOutputScreen(
            destination.versionId,
            destination.output,
            platform.platformKey,
            terminal.rows,
            terminal.columns,
            controller::showInstalled,
        )
    }

    LaunchedEffect(Unit) {
        awaitCancellation()
    }
}

private const val MENU_RESERVED_ROWS = 10
