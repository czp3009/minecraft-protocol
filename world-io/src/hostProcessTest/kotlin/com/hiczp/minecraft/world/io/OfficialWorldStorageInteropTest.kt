package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.test.MinecraftTestSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() = runTest {
        OfficialWorldStorageInteropRunner.run(
            report = MinecraftTestSupport.reportFile(
                "official-world-storage.json",
            ),
        )
    }
}
