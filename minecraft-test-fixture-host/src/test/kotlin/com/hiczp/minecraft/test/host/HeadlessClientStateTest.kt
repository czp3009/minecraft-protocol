package com.hiczp.minecraft.test.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HeadlessClientStateTest {
    @Test
    fun parsesDisplayedScreenAndNoGuiResponses() {
        assertEquals(
            "net.minecraft.client.gui.screens.TitleScreen",
            parseHeadlessClientState(
                "Screen: net.minecraft.client.gui.screens.TitleScreen",
            ).screenClassName,
        )
        assertNull(
            parseHeadlessClientState(
                "Minecraft is currently not displaying a Gui.",
            ).screenClassName,
        )
    }

    @Test
    fun rejectsAnUnrecognizedGuiResponse() {
        assertFailsWith<IllegalStateException> {
            parseHeadlessClientState("unexpected response")
        }
    }
}
