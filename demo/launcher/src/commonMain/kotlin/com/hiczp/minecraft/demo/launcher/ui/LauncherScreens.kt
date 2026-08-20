package com.hiczp.minecraft.demo.launcher.ui

import androidx.compose.runtime.*
import com.hiczp.minecraft.demo.launcher.*
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
        onKeyEvent = { event ->
            if (event.key == "Escape" && destination.cancellable) {
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
        onKeyEvent = { event ->
            when (event.key) {
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
        TextBlock(destination.message)
    }
}

@Composable
internal fun PreparingInstallScreen(
    entry: VersionEntry,
    platform: String,
    onCancel: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Esc", "Cancel")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onCancel()
                true
            } else {
                false
            }
        },
    ) {
        SectionHeading("Installing ${entry.id}")
        Status("Loading resource manifests...")
    }
}

@Composable
internal fun HomeScreen(
    auth: AuthState?,
    platform: String,
    visibleRows: Int,
    onShowVersions: () -> Unit,
    onShowInstalled: () -> Unit,
    onShowAccounts: () -> Unit,
    onExit: () -> Unit,
) {
    val selection = rememberSelectionState()
    val items = listOf(
        ActionItem("Install version", onShowVersions),
        ActionItem("Installed versions", onShowInstalled),
        ActionItem("Accounts", onShowAccounts),
        ActionItem("Exit", onExit),
    )
    val selectedAccount = auth?.accounts?.singleOrNull { it.identity.id == auth.selectedIdentityId }
    val accountLabel = selectedAccount?.let { account ->
        val kind = if (account.identity is MinecraftOnlineIdentity) "Microsoft" else "Offline"
        "${account.identity.name} | $kind"
    } ?: "$DEFAULT_OFFLINE_PLAYER_NAME | Offline default"

    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Open"), KeyHint("Esc", "Exit")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onExit()
                true
            } else {
                handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading("Home")
        PropertyRow("Account", accountLabel)
        Spacer(Modifier.height(1))
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun VersionsScreen(
    platform: String,
    visibleRows: Int,
    versionsFor: (String?) -> List<VersionEntry>,
    onInstall: (VersionEntry) -> Unit,
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf(VersionFilter.STABLE) }
    val selection = rememberSelectionState()
    val items = versionsFor(filter.type).map { entry ->
        ActionItem(entry.id, onActivate = { onInstall(entry) })
    }

    LauncherScaffold(
        platform = platform,
        hints = listOf(
            KeyHint("s", if (filter == VersionFilter.STABLE) "Show all" else "Stable only"),
            KeyHint("Up/Down", "Select"),
            KeyHint("Enter", "Install"),
            KeyHint("Esc", "Back"),
        ),
        onKeyEvent = { event ->
            when (event.key) {
                "Escape" -> {
                    onBack()
                    true
                }

                "s" -> {
                    filter = filter.toggle()
                    selection.reset()
                    true
                }

                else -> handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading("Install an official version", filter.label)
        WrappedText("Manifest availability does not guarantee launcher compatibility.")
        Spacer(Modifier.height(1))
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun ConfirmInstallScreen(
    entry: VersionEntry,
    platform: String,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Enter", "Install"), KeyHint("Esc", "Back")),
        onKeyEvent = { event -> handleConfirmationKey(event, onInstall, onBack) },
    ) {
        SectionHeading("Install ${entry.id}")
        PropertyRow("Type", entry.type)
        PropertyRow("Directory", "minecraft/${entry.id}/")
        Spacer(Modifier.height(1))
        WrappedText("Each version keeps its own libraries, assets, saves, and settings.")
    }
}

@Composable
internal fun InstallingScreen(
    entry: VersionEntry,
    progress: InstallProgress,
    platform: String,
    onCancel: () -> Unit,
) {
    val fraction = if (progress.totalFiles > 0) {
        progress.completedFiles.toFloat() / progress.totalFiles
    } else {
        0f
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Esc", "Cancel")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onCancel()
                true
            } else {
                false
            }
        },
    ) {
        SectionHeading("Installing ${entry.id}")
        ProgressBar(fraction)
        Spacer(Modifier.height(1))
        PropertyRow("Files", "${progress.completedFiles} / ${progress.totalFiles}")
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
    val selection = rememberSelectionState()
    val items = versions.map { version ->
        ActionItem(version.versionId, onActivate = { onOpen(version) })
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Actions"), KeyHint("Esc", "Back")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading("Installed versions")
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
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
    val selection = rememberSelectionState()
    val items = listOf(
        ActionItem("Launch", onLaunch),
        ActionItem("Delete", onDelete),
        ActionItem("Back", onBack),
    )
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Confirm"), KeyHint("Esc", "Back")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading(versionId)
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
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
        onKeyEvent = { event -> handleConfirmationKey(event, onDelete, onBack) },
    ) {
        SectionHeading("Delete $versionId")
        Status("This cannot be undone")
        Spacer(Modifier.height(1))
        TextBlock(
            "The entire minecraft/$versionId/ directory will be removed, including saves and settings.",
        )
    }
}

@Composable
internal fun AccountsScreen(
    auth: AuthState?,
    accountCredentials: Map<Uuid, AccountCredentialState>,
    platform: String,
    visibleRows: Int,
    onAdd: () -> Unit,
    onOpen: (StoredAccount) -> Unit,
    onBack: () -> Unit,
) {
    val selection = rememberSelectionState()
    val items = auth?.accounts.orEmpty().map { account ->
        val selected = account.identity.id == auth?.selectedIdentityId
        ActionItem(
            label = "${if (selected) "* " else ""}${account.identity.name}",
            onActivate = { onOpen(account) },
            trailingText = accountCredentials[account.identity.id].displayText(),
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
        onKeyEvent = { event ->
            when (event.key) {
                "a" -> {
                    onAdd()
                    true
                }

                "Escape" -> {
                    onBack()
                    true
                }

                else -> handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading("Accounts")
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
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
    val selection = rememberSelectionState()
    val items = listOf(
        ActionItem("Microsoft account", onAddMicrosoft),
        ActionItem("Offline identity", onAddOffline),
        ActionItem("Back", onBack),
    )
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Open"), KeyHint("Esc", "Back")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading("Add account")
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
    }
}

@Composable
internal fun AccountActionsScreen(
    account: StoredAccount?,
    credentialState: AccountCredentialState?,
    selectedIdentityId: Uuid?,
    platform: String,
    visibleRows: Int,
    onSelect: (StoredAccount) -> Unit,
    onEditOffline: (StoredAccount) -> Unit,
    onSignInAgain: () -> Unit,
    onDelete: (StoredAccount) -> Unit,
    onBack: () -> Unit,
) {
    val selection = rememberSelectionState()
    val items = if (account == null) {
        listOf(ActionItem("Back", onBack))
    } else {
        buildList {
            if (account.identity.id != selectedIdentityId) add(ActionItem("Select", { onSelect(account) }))
            when (account.identity) {
                is MinecraftOfflineIdentity -> add(ActionItem("Edit name", { onEditOffline(account) }))
                is MinecraftOnlineIdentity -> add(ActionItem("Sign in again", onSignInAgain))
            }
            add(ActionItem("Delete", { onDelete(account) }))
            add(ActionItem("Back", onBack))
        }
    }
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Up/Down", "Select"), KeyHint("Enter", "Confirm"), KeyHint("Esc", "Back")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onBack()
                true
            } else {
                handleMenuKey(event, items, selection)
            }
        },
    ) {
        SectionHeading(account?.identity?.name ?: "Account not found")
        account?.let {
            PropertyRow("Type", if (it.identity is MinecraftOnlineIdentity) "Microsoft" else "Offline")
            credentialState.displayText()?.let { status -> PropertyRow("Status", status) }
            Spacer(Modifier.height(1))
        }
        ActionMenu(items, selection, visibleRows, Modifier.weight(1f))
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
        onKeyEvent = { event ->
            when (event.key) {
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
                    val character = event.key.singleOrNull()
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
    stage: MicrosoftLoginStage,
    platform: String,
    onCancel: () -> Unit,
) {
    LauncherScaffold(
        platform = platform,
        hints = listOf(KeyHint("Esc", "Cancel")),
        onKeyEvent = { event ->
            if (event.key == "Escape") {
                onCancel()
                true
            } else {
                false
            }
        },
    ) {
        SectionHeading("Microsoft sign-in")
        Status(stage.message())
        Spacer(Modifier.height(1))
        WrappedText("Tokens are never displayed in the terminal.")
    }
}

@Composable
internal fun GameOutputScreen(
    versionId: String,
    output: GameOutputBuffer,
    platform: String,
    terminalRows: Int,
    terminalColumns: Int,
    onBack: () -> Unit,
) {
    val snapshot by output.state.collectAsState()
    var scrollFromBottom by remember { mutableIntStateOf(0) }
    val viewportHeight = (terminalRows - GAME_OUTPUT_RESERVED_ROWS).coerceAtLeast(1)
    val outputLines = snapshot.lines.flatMap { line ->
        val marker = when (line.source) {
            OutputSource.STDOUT -> "  "
            OutputSource.STDERR -> "! "
            OutputSource.SYSTEM -> "* "
        }
        wrappedTextLines("$marker${line.text}", contentWidth(terminalColumns))
    }
    val endExclusive = (outputLines.size - scrollFromBottom).coerceIn(0, outputLines.size)
    val start = (endExclusive - viewportHeight).coerceAtLeast(0)
    val stateLabel = if (snapshot.running) "Running" else "Exited | code ${snapshot.exitCode}"
    val followLabel = if (scrollFromBottom == 0) "Following output" else "$scrollFromBottom lines from latest"

    LauncherScaffold(
        platform = platform,
        hints = buildList {
            add(KeyHint("Up/Down", "Scroll"))
            add(KeyHint("PgUp/PgDn", "Page"))
            if (!snapshot.running) add(KeyHint("Esc", "Back"))
        },
        onKeyEvent = { event ->
            when (event.key) {
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

                "Escape" -> if (!snapshot.running) {
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
                outputLines.subList(start, endExclusive).forEach { line -> Text(line) }
            }
        }
    }
}

private fun LauncherDestination.Loading.message(): String = when (operation) {
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

private fun handleMenuKey(event: KeyEvent, items: List<ActionItem>, selection: SelectionState): Boolean =
    when (event.key) {
        "ArrowUp" -> {
            selection.moveBy(-1, items.size)
            true
        }

        "ArrowDown" -> {
            selection.moveBy(1, items.size)
            true
        }

        "Enter" -> {
            selection.selected(items)?.onActivate?.invoke()
            true
        }

        else -> false
    }

private fun handleConfirmationKey(event: KeyEvent, onConfirm: () -> Unit, onBack: () -> Unit): Boolean {
    when (event.key) {
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
