package com.hiczp.minecraft.demo.launcher.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LauncherComponentsTest {
    @Test
    fun textWrapsWithoutEllipsisOrDroppedCharacters() {
        assertEquals("abcd\nef", wrapText("abcdef", 4))
        assertEquals("ab\n\ncd", wrapText("ab\n\ncd", 4))
        assertEquals("a\nb", wrapText("ab", 1))
    }
}
