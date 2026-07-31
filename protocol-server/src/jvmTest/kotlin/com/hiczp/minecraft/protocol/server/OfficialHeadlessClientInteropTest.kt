package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import kotlin.test.Test

class OfficialHeadlessClientInteropTest {
    @Test
    fun officialHeadlessClientReachesPlayAgainstLibraryServer() {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "protocol-server",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
        OfficialClientEndToEndRunner.run(
            javaExecutable = environment.javaExecutable,
            installation = environment.officialClient(),
            workDirectory = environment.freshWorkDirectory(
                "official-client/${MinecraftProtocol.MINECRAFT_VERSION}",
            ),
            report = environment.reportFile(
                "official-client-headless.json",
            ),
            headlessLauncher = environment.headlessMinecraftLauncher(),
        )
    }
}
