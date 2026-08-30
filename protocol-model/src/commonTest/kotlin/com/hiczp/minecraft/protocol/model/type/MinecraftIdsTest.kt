package com.hiczp.minecraft.protocol.model.type

import kotlin.test.Test
import kotlin.test.assertEquals

class MinecraftIdsTest {
    @Test
    fun commonIdsUseTheDefaultMinecraftNamespace() {
        assertEquals("minecraft:air", MinecraftBlockIds.AIR.value)
        assertEquals("minecraft:plains", MinecraftBiomeIds.PLAINS.value)
    }
}
