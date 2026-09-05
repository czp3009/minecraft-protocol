package com.hiczp.minecraft.demo.launcher.ui

import androidx.compose.runtime.*
import com.hiczp.minecraft.demo.launcher.*
import com.hiczp.minecraft.distribution.metadata.MinecraftVersionReference
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import kotlin.uuid.Uuid

@Composable
internal fun LoadingScreen(
    destination: LauncherDestination.Loading,
    platform: String,
    onCancel: (() -> Unit)?,
) {
    LauncherScaffold(
        platform = platform,
        hints = if (destination.cancellable) listOf(KeyHint("Esc", "Cancel")) else emptyList(),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape" && destination.cancellable) {
                onCancel?.invoke()
                true
            } else {
                false
            }
        },
    ) {
        Status(destination.message())
    }
}

@Composable
internal fun ErrorScreen(
    destination: LauncherDestination.Error,
    platform: String,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Enter / Esc", "Back"), KeyHint("q", "Exit")),
        onKeyEvent = { keyEvent ->
            when (keyEvent.key) {
                "Enter" -> {
                    onDismiss()
                    true
                }

                "Escape" -> {
                    onDismiss()
                    true
                }

                "q" -> {
                    onExit()
                    true
                }

                else -> false
            }
        },
    ) {
        Status("Operation failed")
        Spacer(Modifier.height(1))
        WrappedText(destination.message)
    }
}

@Composable
internal fun PreparingInstallScreen(
    minecraftVersionReference: MinecraftVersionReference,
    platform: String,
    onCancel: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Esc", "Cancel")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onCancel()
                true
            } else {
                false
            }
        },
    ) {
        SectionHeading("Installing ${minecraftVersionReference.id}")
        Status("Loading resource manifests...")
    }
}

@Composable
internal fun HomeScreen(
    authState: AuthState?,
    platform: String,
    visibleRows: Int,
    onShowVersions: () -> Unit,
    onShowInstalled: () -> Unit,
    onShowAccounts: () -> Unit,
    onExit: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val items = listOf(
        ActionItem("Install version", onShowVersions),
        ActionItem("Installed versions", onShowInstalled),
        ActionItem("Accounts", onShowAccounts),
        ActionItem("Exit", onExit),
    )
    val selectedAccount = authState?.accounts?.singleOrNull { it.minecraftIdentity.id == authState.selectedIdentityId }
    val accountLabel = selectedAccount?.let { storedAccount ->
        val kind = if (storedAccount.minecraftIdentity is MinecraftOnlineIdentity) "Microsoft" else "Offline"
        "${storedAccount.minecraftIdentity.name} | $kind"
    } ?: "$DEFAULT_OFFLINE_PLAYER_NAME | Offline default"

    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Open"), KeyHint("Esc", "Exit")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onExit()
                true
            } else {
                handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading("Home")
        PropertyRow("Account", accountLabel)
        Spacer(Modifier.height(1))
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun VersionsScreen(
    platform: String,
    visibleRows: Int,
    versionsFor: (String?) -> List<MinecraftVersionReference>,
    installedVersionIds: Set<String>,
    onInstall: (MinecraftVersionReference) -> Unit,
    onBack: () -> Unit,
) {
    var versionFilter by remember { mutableStateOf(VersionFilter.STABLE) }
    val selectionState = rememberSelectionState()
    val items = versionsFor(versionFilter.type).map { minecraftVersionReference ->
        ActionItem(
            label = minecraftVersionReference.id,
            onActivate = { onInstall(minecraftVersionReference) },
            trailingText = if (minecraftVersionReference.id in installedVersionIds) "Installed" else null,
        )
    }

    LauncherScaffold(
        platform = platform,
        hints = listOf(
            KeyHint("s", if (versionFilter == VersionFilter.STABLE) "Show all" else "Stable only"),
            KeyHint("Up/Down", "Select"),
            KeyHint("Enter", "Install"),
            KeyHint("Esc", "Back"),
        ),
        onKeyEvent = { keyEvent ->
            when (keyEvent.key) {
                "Escape" -> {
                    onBack()
                    true
                }

                "s" -> {
                    versionFilter = versionFilter.toggle()
                    selectionState.reset()
                    true
                }

                else -> handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading("Install an official version", versionFilter.label)
        WrappedText("Manifest availability does not guarantee launcher compatibility.")
        Spacer(Modifier.height(1))
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun ConfirmInstallScreen(
    minecraftVersionReference: MinecraftVersionReference,
    installed: Boolean,
    platform: String,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Enter", if (installed) "Reinstall" else "Install"), KeyHint("Esc", "Back")),
        onKeyEvent = { keyEvent -> handleConfirmationKey(keyEvent, onInstall, onBack) },
    ) {
        SectionHeading(if (installed) "Reinstall ${minecraftVersionReference.id}" else "Install ${minecraftVersionReference.id}")
        PropertyRow("Type", minecraftVersionReference.type)
        PropertyRow("Directory", "minecraft/${minecraftVersionReference.id}/")
        Spacer(Modifier.height(1))
        if (installed) {
            Status("Already installed. Continuing will overwrite this version.")
            Spacer(Modifier.height(1))
        }
        WrappedText("Each version keeps its own libraries, assets, saves, and settings.")
    }
}

@Composable
internal fun InstallingScreen(
    minecraftVersionReference: MinecraftVersionReference,
    installProgress: InstallProgress,
    platform: String,
    onCancel: () -> Unit,
) {
    val fraction = if (installProgress.totalFiles > 0) {
        installProgress.completedFiles.toFloat() / installProgress.totalFiles
    } else {
        0f
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Esc", "Cancel")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onCancel()
                true
            } else {
                false
            }
        },
    ) {
        SectionHeading("Installing ${minecraftVersionReference.id}")
        ProgressBar(fraction)
        Spacer(Modifier.height(1))
        PropertyRow("Files", "${installProgress.completedFiles} / ${installProgress.totalFiles}")
    }
}

@Composable
internal fun InstalledScreen(
    versions: List<InstalledVersion>,
    platform: String,
    visibleRows: Int,
    onOpen: (InstalledVersion) -> Unit,
    onBack: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val items = versions.map { installedVersion ->
        ActionItem(installedVersion.versionId, onActivate = { onOpen(installedVersion) })
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Actions"), KeyHint("Esc", "Back")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading("Installed versions")
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun VersionActionsScreen(
    versionId: String,
    platform: String,
    visibleRows: Int,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val items = listOf(
        ActionItem("Launch", onLaunch),
        ActionItem("Delete", onDelete),
        ActionItem("Back", onBack),
    )
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Confirm"), KeyHint("Esc", "Back")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading(versionId)
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun ConfirmDeleteScreen(
    versionId: String,
    platform: String,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Enter", "Delete"), KeyHint("Esc", "Cancel")),
        onKeyEvent = { keyEvent -> handleConfirmationKey(keyEvent, onDelete, onBack) },
    ) {
        SectionHeading("Delete $versionId")
        Status("This cannot be undone")
        Spacer(Modifier.height(1))
        WrappedText(
            "The entire minecraft/$versionId/ directory will be removed, including saves and settings.",
        )
    }
}

@Composable
internal fun AccountsScreen(
    authState: AuthState?,
    accountCredentials: Map<Uuid, AccountCredentialState>,
    platform: String,
    visibleRows: Int,
    onAdd: () -> Unit,
    onOpen: (StoredAccount) -> Unit,
    onBack: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val items = authState?.accounts.orEmpty().map { storedAccount ->
        val selected = storedAccount.minecraftIdentity.id == authState?.selectedIdentityId
        ActionItem(
            label = "${if (selected) "* " else ""}${storedAccount.minecraftIdentity.name}",
            onActivate = { onOpen(storedAccount) },
            trailingText = accountCredentials[storedAccount.minecraftIdentity.id].displayText(),
        )
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(
            KeyHint("Up/Down", "Select"),
            KeyHint("Enter", "Open"),
            KeyHint("a", "Add account"),
            KeyHint("Esc", "Back"),
        ),
        onKeyEvent = { keyEvent ->
            when (keyEvent.key) {
                "a" -> {
                    onAdd()
                    true
                }

                "Escape" -> {
                    onBack()
                    true
                }

                else -> handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading("Accounts")
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun AddAccountScreen(
    platform: String,
    visibleRows: Int,
    onAddMicrosoft: () -> Unit,
    onAddOffline: () -> Unit,
    onBack: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val items = listOf(
        ActionItem("Microsoft account", onAddMicrosoft),
        ActionItem("Offline identity", onAddOffline),
        ActionItem("Back", onBack),
    )
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Open"), KeyHint("Esc", "Back")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading("Add account")
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun AccountActionsScreen(
    storedAccount: StoredAccount?,
    accountCredentialState: AccountCredentialState?,
    selectedIdentityId: Uuid?,
    platform: String,
    visibleRows: Int,
    onSelect: (StoredAccount) -> Unit,
    onEditOffline: (StoredAccount) -> Unit,
    onSignInAgain: () -> Unit,
    onDelete: (StoredAccount) -> Unit,
    onBack: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val items = if (storedAccount == null) {
        listOf(ActionItem("Back", onBack))
    } else {
        buildList {
            if (storedAccount.minecraftIdentity.id != selectedIdentityId) add(
                ActionItem(
                    "Select",
                    { onSelect(storedAccount) })
            )
            when (storedAccount.minecraftIdentity) {
                is MinecraftOfflineIdentity -> add(ActionItem("Edit name", { onEditOffline(storedAccount) }))
                is MinecraftOnlineIdentity -> add(ActionItem("Sign in again", onSignInAgain))
            }
            add(ActionItem("Delete", { onDelete(storedAccount) }))
            add(ActionItem("Back", onBack))
        }
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Confirm"), KeyHint("Esc", "Back")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(keyEvent, items, selectionState)
            }
        },
    ) {
        SectionHeading(storedAccount?.minecraftIdentity?.name ?: "Account not found")
        storedAccount?.let {
            PropertyRow("Type", if (it.minecraftIdentity is MinecraftOnlineIdentity) "Microsoft" else "Offline")
            accountCredentialState.displayText()?.let { status -> PropertyRow("Status", status) }
            Spacer(Modifier.height(1))
        }
        ActionMenu(items, selectionState, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun OfflineInputScreen(
    platform: String,
    initialName: String,
    editing: Boolean,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    LauncherScaffold(
        platform = platform,
        hints = listOf(
            KeyHint("Type", "Name"),
            KeyHint("Backspace", "Delete"),
            KeyHint("Enter", "Save"),
            KeyHint("Esc", "Cancel"),
        ),
        onKeyEvent = { keyEvent ->
            when (keyEvent.key) {
                "Escape" -> {
                    onBack()
                    true
                }

                "Backspace" -> {
                    name = name.dropLast(1)
                    true
                }

                "Enter" -> {
                    if (name.isNotBlank()) onSave(name)
                    true
                }

                else -> {
                    val character = keyEvent.key.singleOrNull()
                    if (character == null || character.code < 32 || character.code == 127 ||
                        name.length >= MAXIMUM_ACCOUNT_NAME_LENGTH
                    ) {
                        false
                    } else {
                        name = "$name$character"
                        true
                    }
                }
            }
        },
    ) {
        SectionHeading(if (editing) "Edit offline identity" else "Add offline identity")
        WrappedText("Name > $name")
        Spacer(Modifier.height(1))
        WrappedText("Offline identities do not represent accounts that passed entitlement checks.")
    }
}

private fun AccountCredentialState?.displayText(): String? = when (this) {
    AccountCredentialState.REFRESHING -> "Refreshing"
    AccountCredentialState.LOGIN_EXPIRED -> "Login expired"
    null -> null
}

@Composable
internal fun MicrosoftLoginScreen(
    microsoftLoginStage: MicrosoftLoginStage,
    platform: String,
    onCancel: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Esc", "Cancel")),
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == "Escape") {
                onCancel()
                true
            } else {
                false
            }
        },
    ) {
        SectionHeading("Microsoft sign-in")
        Status(microsoftLoginStage.message())
        Spacer(Modifier.height(1))
        WrappedText("Tokens are never displayed in the terminal.")
    }
}

@Composable
internal fun GameOutputScreen(
    versionId: String,
    gameOutputBuffer: GameOutputBuffer,
    platform: String,
    terminalRows: Int,
    terminalColumns: Int,
    onBack: () -> Unit,
) {
    val gameOutputSnapshot by gameOutputBuffer.state.collectAsState()
    var scrollFromBottom by remember { mutableIntStateOf(0) }
    val viewportHeight = (terminalRows - GAME_OUTPUT_RESERVED_ROWS).coerceAtLeast(1)
    val outputLines = gameOutputSnapshot.lines.flatMap { gameOutputLine ->
        val marker = when (gameOutputLine.outputSource) {
            OutputSource.STDOUT -> "  "
            OutputSource.STDERR -> "! "
            OutputSource.SYSTEM -> "* "
        }
        wrappedTextLines("$marker${gameOutputLine.text}", contentWidth(terminalColumns))
    }
    val endExclusive = (outputLines.size - scrollFromBottom).coerceIn(0, outputLines.size)
    val start = (endExclusive - viewportHeight).coerceAtLeast(0)
    val stateLabel = if (gameOutputSnapshot.running) "Running" else "Exited | code ${gameOutputSnapshot.exitCode}"
    val followLabel = if (scrollFromBottom == 0) "Following output" else "$scrollFromBottom lines from latest"

    LauncherScaffold(
        platform = platform,
        hints = buildList {
            add(KeyHint("Up/Down", "Scroll"))
            add(KeyHint("PgUp/PgDn", "Page"))
            if (!gameOutputSnapshot.running) add(KeyHint("Esc", "Back"))
        },
        onKeyEvent = { keyEvent ->
            when (keyEvent.key) {
                "ArrowUp" -> {
                    scrollFromBottom = (scrollFromBottom + 1).coerceAtMost(outputLines.size)
                    true
                }

                "ArrowDown" -> {
                    scrollFromBottom = (scrollFromBottom - 1).coerceAtLeast(0)
                    true
                }

                "PageUp" -> {
                    scrollFromBottom = (scrollFromBottom + viewportHeight).coerceAtMost(outputLines.size)
                    true
                }

                "PageDown" -> {
                    scrollFromBottom = (scrollFromBottom - viewportHeight).coerceAtLeast(0)
                    true
                }

                "Escape" -> if (!gameOutputSnapshot.running) {
                    onBack()
                    true
                } else {
                    false
                }

                else -> false
            }
        },
    ) {
        SectionHeading(versionId, followLabel)
        Status(stateLabel)
        Spacer(Modifier.height(1))
        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (outputLines.isEmpty()) {
                WrappedText("Waiting for game output...")
            } else {
                outputLines.subList(start, endExclusive).forEach { gameOutputLine -> Text(gameOutputLine) }
            }
        }
    }
}

private fun LauncherDestination.Loading.message(): String = when (launcherOperation) {
    LauncherOperation.VERSION_MANIFEST -> "Loading version manifest..."
    LauncherOperation.DELETE_VERSION -> "Deleting ${subject ?: "version"}..."
    LauncherOperation.SELECT_ACCOUNT -> "Selecting account..."
    LauncherOperation.DELETE_ACCOUNT -> "Deleting account..."
    LauncherOperation.SAVE_OFFLINE_IDENTITY -> "Saving offline identity..."
    LauncherOperation.REFRESH_ACCOUNT -> "Refreshing the login token for ${subject ?: "account"}..."
    LauncherOperation.PREPARE_GAME -> "Validating ${subject ?: "version"} and preparing to launch..."
}

private fun MicrosoftLoginStage.message(): String = when (this) {
    MicrosoftLoginStage.STARTING_CALLBACK -> "Starting the local authorization callback..."
    MicrosoftLoginStage.WAITING_FOR_BROWSER -> "Waiting for authorization in the browser..."
    MicrosoftLoginStage.VERIFYING_ACCOUNT -> "Verifying the Xbox and Minecraft accounts..."
    MicrosoftLoginStage.COMPLETE -> "Sign-in complete"
}

private fun handleMenuKey(keyEvent: KeyEvent, items: List<ActionItem>, selectionState: SelectionState): Boolean =
    when (keyEvent.key) {
        "ArrowUp" -> {
            selectionState.moveBy(-1, items.size)
            true
        }

        "ArrowDown" -> {
            selectionState.moveBy(1, items.size)
            true
        }

        "Enter" -> {
            selectionState.selected(items)?.onActivate?.invoke()
            true
        }

        else -> false
    }

private fun handleConfirmationKey(keyEvent: KeyEvent, onConfirm: () -> Unit, onBack: () -> Unit): Boolean {
    when (keyEvent.key) {
        "Enter" -> onConfirm()
        "Escape" -> onBack()
        else -> return false
    }
    return true
}

private enum class VersionFilter(val label: String, val type: String?) {
    STABLE("Stable channel", "release"),
    ALL("All versions", null),
    ;

    fun toggle(): VersionFilter = if (this == STABLE) ALL else STABLE
}

private const val MAXIMUM_ACCOUNT_NAME_LENGTH = 32
private const val GAME_OUTPUT_RESERVED_ROWS = 10
