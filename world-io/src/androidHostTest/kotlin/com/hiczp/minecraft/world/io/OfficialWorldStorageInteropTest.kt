package com.hiczp.minecraft.world.io

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() = runTest {
        OfficialWorldStorageInteropRunner.run()
    }
}
