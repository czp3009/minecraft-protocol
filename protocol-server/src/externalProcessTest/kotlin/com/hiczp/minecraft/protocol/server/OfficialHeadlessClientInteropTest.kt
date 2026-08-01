package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialHeadlessClientInteropTest {
    @Test
    fun officialHeadlessClientReachesPlayAgainstLibraryServer() = runTest(
        timeout = 45.minutes,
    ) {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "protocol-server",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
        OfficialClientEndToEndRunner.run(
            environment = environment,
            workDirectory = environment.freshWorkDirectory(
                "official-client/${MinecraftProtocol.MINECRAFT_VERSION}",
            ),
            report = environment.reportFile(
                "official-client-headless.json",
            ),
        )
    }
}
