package com.hiczp.minecraft.demo.launcher.ui

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*

internal data class KeyHint(val key: String, val action: String)

internal data class ActionItem(
    val label: String,
    val onActivate: () -> Unit,
    val trailingText: String? = null,
)

@Stable
internal class SelectionState(initialIndex: Int = 0) {
    var index by mutableIntStateOf(initialIndex)
        private set

    fun moveBy(offset: Int, itemCount: Int) {
        index = (normalizedIndex(itemCount) + offset).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    }

    fun reset() {
        index = 0
    }

    fun <T> selected(items: List<T>): T? = items.getOrNull(normalizedIndex(items.size))

    fun normalizedIndex(itemCount: Int): Int = index.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
}

@Composable
internal fun rememberSelectionState(initialIndex: Int = 0): SelectionState = remember {
    SelectionState(initialIndex)
}

@Composable
internal fun LauncherScaffold(
    platform: String,
    hints: List<KeyHint>,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val terminal = LocalTerminalState.current.size
    // Wrapped fixed rows can otherwise consume more space than Mosaic can distribute to weighted content.
    if (terminal.columns < MINIMUM_TERMINAL_COLUMNS || terminal.rows < MINIMUM_TERMINAL_ROWS) {
        Text(
            value = TERMINAL_TOO_SMALL_MESSAGE.take(terminal.columns.coerceAtLeast(1)),
        )
        return
    }

    // Mosaic terminates every canvas row with CRLF, so a full-height surface would scroll its first row away.
    val canvasRows = (terminal.rows - 1).coerceAtLeast(1)
    Column(
        modifier = Modifier
            .size(terminal.columns.coerceAtLeast(1), canvasRows)
            .onKeyEvent { keyEvent -> !(keyEvent.ctrl || keyEvent.alt) && onKeyEvent(keyEvent) },
    ) {
        WrappedText("$LAUNCHER_TITLE  $platform", Modifier.padding(horizontal = 2))
        Spacer(Modifier.height(1))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 2),
            content = content,
        )
        Spacer(Modifier.height(1))
        WrappedText(
            value = hints.joinToString(separator = "  |  ") { keyHint -> "${keyHint.key} ${keyHint.action}" },
            modifier = Modifier.padding(horizontal = 2),
        )
    }
}

@Composable
internal fun SectionHeading(title: String, supportingText: String? = null) {
    WrappedText(title)
    supportingText?.let { WrappedText(it) }
    Spacer(Modifier.height(1))
}

@Composable
internal fun PropertyRow(label: String, value: String) {
    WrappedText("$label$PROPERTY_SEPARATOR$value")
}

@Composable
internal fun Status(text: String) {
    WrappedText("* $text")
}

@Composable
internal fun ActionMenu(
    items: List<ActionItem>,
    selectionState: SelectionState,
    visibleRows: Int,
    modifier: Modifier = Modifier,
    emptyText: String = "<empty>",
) {
    Column(modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            WrappedText(emptyText)
            return@Column
        }

        val selectedIndex = selectionState.normalizedIndex(items.size)
        val rowCount = visibleRows.coerceAtLeast(1)
        val start = (selectedIndex - rowCount / 2).coerceIn(0, (items.size - rowCount).coerceAtLeast(0))
        val end = (start + rowCount).coerceAtMost(items.size)
        for (index in start until end) {
            ActionRow(items[index], selected = index == selectedIndex)
        }
    }
}

@Composable
private fun ActionRow(actionItem: ActionItem, selected: Boolean) {
    val prefix = if (selected) "> " else "  "
    Row(Modifier.fillMaxWidth()) {
        Text(prefix)
        Text(actionItem.label, Modifier.weight(1f))
        actionItem.trailingText?.let { Text("($it)") }
    }
}

@Composable
internal fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(modifier.widthIn(max = PROGRESS_BAR_MAXIMUM_WIDTH).fillMaxWidth().height(1)) {
        Filler('-', Modifier.matchParentSize())
        if (fraction > 0f) {
            Filler('=', Modifier.fillMaxWidth(fraction).height(1))
        }
    }
}

@Composable
internal fun WrappedText(value: String, modifier: Modifier = Modifier) {
    Text(wrapText(value, contentWidth(LocalTerminalState.current.size.columns)), modifier)
}

internal fun wrapText(value: String, maximumWidth: Int): String =
    wrappedTextLines(value, maximumWidth).joinToString("\n")

internal fun wrappedTextLines(value: String, maximumWidth: Int): List<String> {
    val width = maximumWidth.coerceAtLeast(1)
    return value.lines().flatMap { line -> line.chunked(width).ifEmpty { listOf("") } }
}

internal fun contentWidth(terminalColumns: Int): Int =
    (terminalColumns - CONTENT_HORIZONTAL_PADDING).coerceAtLeast(1)

private const val LAUNCHER_TITLE = "Minecraft Launcher Demo"
private const val CONTENT_HORIZONTAL_PADDING = 4
private const val PROGRESS_BAR_MAXIMUM_WIDTH = 48
private const val PROPERTY_SEPARATOR = ": "
private const val MINIMUM_TERMINAL_COLUMNS = 40
private const val MINIMUM_TERMINAL_ROWS = 14
private const val TERMINAL_TOO_SMALL_MESSAGE =
    "Resize terminal to at least $MINIMUM_TERMINAL_COLUMNS x $MINIMUM_TERMINAL_ROWS"
