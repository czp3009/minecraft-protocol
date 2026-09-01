package com.hiczp.minecraft.demo.launcher.ui

import androidx.compose.runtime.BroadcastFrameClock
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.Terminal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LauncherComponentsTest {
    @Test
    fun textWrapsWithoutEllipsisOrDroppedCharacters() {
        assertEquals("abcd\nef", wrapText("abcdef", 4))
        assertEquals("ab\n\ncd", wrapText("ab\n\ncd", 4))
        assertEquals("a\nb", wrapText("ab", 1))
    }

    @Test
    fun scaffoldHandlesResizeToAOneCellTerminal() = runTest {
        val broadcastFrameClock = BroadcastFrameClock()
        val testTerminal = TestTerminal(Terminal.Size(columns = 80, rows = 24))
        val mosaic = Mosaic(coroutineContext + broadcastFrameClock, onDraw = {}, terminal = testTerminal)
        try {
            mosaic.setContent {
                LauncherScaffold(
                    platform = "test",
                    hints = listOf(KeyHint("Enter", "Open")),
                    onKeyEvent = { false },
                ) {
                    ActionMenu(
                        items = listOf(ActionItem("Open", onActivate = {})),
                        selectionState = SelectionState(),
                        visibleRows = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            testTerminal.resize(Terminal.Size(columns = 1, rows = 1))
            broadcastFrameClock.sendFrame(0L)
            testScheduler.runCurrent()
            broadcastFrameClock.sendFrame(1L)

            assertEquals("R", mosaic.draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false))
        } finally {
            mosaic.cancel()
            testTerminal.close()
        }
    }
}

private class TestTerminal(initialSize: Terminal.Size) : Terminal {
    private val eventChannel = Channel<Event>()
    private val terminalSize = MutableStateFlow(initialSize)

    override val name: String? = null
    override val interactive: Boolean = true
    override val state: Terminal.State = object : Terminal.State {
        override val focused: StateFlow<Boolean> = MutableStateFlow(true)
        override val theme: StateFlow<Terminal.Theme> = MutableStateFlow(Terminal.Theme.Unknown)
        override val size: StateFlow<Terminal.Size> = terminalSize
    }
    override val capabilities: Terminal.Capabilities = object : Terminal.Capabilities {
        override val ansiLevel: AnsiLevel = AnsiLevel.NONE
        override val cursorVisibility: Boolean = false
        override val focusEvents: Boolean = false
        override val inBandResizeEvents: Boolean = false
        override val kittyGraphics: Boolean = false
        override val kittyKeyboard: Boolean = false
        override val kittyNotifications: Boolean = false
        override val kittyPointerShape: Boolean = false
        override val kittyTextSizingScale: Boolean = false
        override val kittyTextSizingWidth: Boolean = false
        override val kittyUnderline: Boolean = false
        override val synchronizedOutput: Boolean = false
        override val themeEvents: Boolean = false
    }
    override val events: ReceiveChannel<Event> = eventChannel

    fun resize(size: Terminal.Size) {
        terminalSize.value = size
    }

    override fun close() {
        eventChannel.close()
    }
}
