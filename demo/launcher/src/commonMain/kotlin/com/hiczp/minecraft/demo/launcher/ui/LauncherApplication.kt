package com.hiczp.minecraft.demo.launcher.ui

import androidx.compose.runtime.*
import com.hiczp.minecraft.demo.launcher.LauncherController
import com.hiczp.minecraft.demo.launcher.LauncherDestination
import com.hiczp.minecraft.demo.launcher.LauncherOperation
import com.hiczp.minecraft.demo.launcher.LauncherPlatform
import com.jakewharton.mosaic.LocalTerminalState
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun LauncherApplication(launcherController: LauncherController, launcherPlatform: LauncherPlatform) {
    var running by remember { mutableStateOf(true) }
    if (!running) return

    val launcherState by launcherController.state.collectAsState()
    val installProgress by launcherController.installProgress.collectAsState()
    val terminal = LocalTerminalState.current.size
    val visibleRows = (terminal.rows - MENU_RESERVED_ROWS).coerceAtLeast(1)
    val exit = { running = false }
    when (val launcherDestination = launcherState.launcherDestination) {
        is LauncherDestination.Loading -> LoadingScreen(
            destination = launcherDestination,
            platform = launcherPlatform.platformKey,
            onCancel = when (launcherDestination.launcherOperation) {
                LauncherOperation.PREPARE_GAME, LauncherOperation.REFRESH_ACCOUNT ->
                    launcherController::cancelGamePreparation

                LauncherOperation.VERSION_MANIFEST -> launcherController::showHome
                else -> null
            },
        )

        is LauncherDestination.Error -> ErrorScreen(
            launcherDestination,
            launcherPlatform.platformKey,
            launcherController::dismissError,
            exit,
        )

        LauncherDestination.Home -> HomeScreen(
            launcherState.authState,
            launcherPlatform.platformKey,
            visibleRows,
            launcherController::showVersions,
            launcherController::showInstalled,
            launcherController::showAccounts,
            exit,
        )

        LauncherDestination.Versions -> VersionsScreen(
            launcherPlatform.platformKey,
            visibleRows,
            launcherController::availableVersions,
            launcherState.installedState.installations
                .mapTo(mutableSetOf()) { it.versionId },
            launcherController::confirmInstall,
            launcherController::showHome,
        )

        is LauncherDestination.ConfirmInstall -> ConfirmInstallScreen(
            launcherDestination.versionEntry,
            launcherState.installedState.installations.any {
                it.versionId == launcherDestination.versionEntry.id
            },
            launcherPlatform.platformKey,
            onInstall = { launcherController.install(launcherDestination.versionEntry) },
            onBack = launcherController::showVersions,
        )

        is LauncherDestination.PreparingInstall -> PreparingInstallScreen(
            launcherDestination.versionEntry,
            launcherPlatform.platformKey,
            launcherController::cancelInstallation,
        )

        is LauncherDestination.Installing -> InstallingScreen(
            launcherDestination.versionEntry,
            installProgress,
            launcherPlatform.platformKey,
            launcherController::cancelInstallation,
        )

        LauncherDestination.Installed -> InstalledScreen(
            launcherController.installedVersions(),
            launcherPlatform.platformKey,
            visibleRows,
            onOpen = { launcherController.showVersionActions(it.versionId) },
            onBack = launcherController::showHome,
        )

        is LauncherDestination.VersionActions -> VersionActionsScreen(
            launcherDestination.versionId,
            launcherPlatform.platformKey,
            visibleRows,
            onLaunch = { launcherController.launchGame(launcherDestination.versionId) },
            onDelete = { launcherController.confirmDelete(launcherDestination.versionId) },
            onBack = launcherController::showInstalled,
        )

        is LauncherDestination.ConfirmDelete -> ConfirmDeleteScreen(
            launcherDestination.versionId,
            launcherPlatform.platformKey,
            onDelete = { launcherController.deleteVersion(launcherDestination.versionId) },
            onBack = { launcherController.showVersionActions(launcherDestination.versionId) },
        )

        LauncherDestination.Accounts -> AccountsScreen(
            launcherState.authState,
            launcherState.accountCredentials,
            launcherPlatform.platformKey,
            visibleRows,
            launcherController::showAddAccount,
            launcherController::showAccountActions,
            launcherController::showHome,
        )

        LauncherDestination.AddAccount -> AddAccountScreen(
            launcherPlatform.platformKey,
            visibleRows,
            onAddMicrosoft = { launcherController.loginMicrosoft() },
            onAddOffline = { launcherController.showOfflineInput() },
            onBack = launcherController::showAccounts,
        )

        is LauncherDestination.AccountActions -> AccountActionsScreen(
            storedAccount = launcherState.authState?.accounts?.singleOrNull { it.minecraftIdentity.id == launcherDestination.identityId },
            accountCredentialState = launcherState.accountCredentials[launcherDestination.identityId],
            selectedIdentityId = launcherState.authState?.selectedIdentityId,
            platform = launcherPlatform.platformKey,
            visibleRows = visibleRows,
            onSelect = launcherController::selectAccount,
            onEditOffline = launcherController::showOfflineInput,
            onSignInAgain = { launcherController.loginMicrosoft(launcherDestination.identityId) },
            onDelete = launcherController::deleteAccount,
            onBack = launcherController::showAccounts,
        )

        is LauncherDestination.OfflineInput -> {
            val storedAccount =
                launcherState.authState?.accounts?.singleOrNull { it.minecraftIdentity.id == launcherDestination.replacingIdentityId }
            OfflineInputScreen(
                platform = launcherPlatform.platformKey,
                initialName = storedAccount?.minecraftIdentity?.name.orEmpty(),
                editing = launcherDestination.replacingIdentityId != null,
                onSave = { name ->
                    launcherController.saveOfflineIdentity(
                        name,
                        launcherDestination.replacingIdentityId
                    )
                },
                onBack = launcherController::showAccounts,
            )
        }

        is LauncherDestination.MicrosoftLogin -> MicrosoftLoginScreen(
            launcherDestination.microsoftLoginStage,
            launcherPlatform.platformKey,
            launcherController::cancelMicrosoftLogin,
        )

        is LauncherDestination.GameOutput -> GameOutputScreen(
            launcherDestination.versionId,
            launcherDestination.gameOutputBuffer,
            launcherPlatform.platformKey,
            terminal.rows,
            terminal.columns,
            launcherController::showInstalled,
        )
    }

    LaunchedEffect(Unit) {
        awaitCancellation()
    }
}

private const val MENU_RESERVED_ROWS = 10
