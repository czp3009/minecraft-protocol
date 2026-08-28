package com.hiczp.minecraft.world.format

import kotlin.test.Test
import kotlin.test.assertTrue

class MinecraftWorldFormatTest {
    @Test
    fun generatedWorldVersionIsNonNegative() {
        assertTrue(MinecraftWorldFormat.WORLD_VERSION >= 0)
    }
}
