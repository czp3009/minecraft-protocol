package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() = runTest(
        timeout = 10.minutes,
    ) {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "world-io",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
        OfficialWorldStorageInteropRunner.run(
            environment = environment,
            workDirectory = environment.freshWorkDirectory(
                "official-world/${MinecraftProtocol.MINECRAFT_VERSION}",
            ),
            report = environment.reportFile(
                "official-world-storage.json",
            ),
        )
    }
}
