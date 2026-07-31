package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import kotlin.test.Test

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() {
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
