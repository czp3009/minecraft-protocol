package com.hiczp.minecraft.world.io

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() = runTest(
        timeout = 2.minutes,
    ) {
        OfficialWorldStorageInteropRunner.run()
    }
}
