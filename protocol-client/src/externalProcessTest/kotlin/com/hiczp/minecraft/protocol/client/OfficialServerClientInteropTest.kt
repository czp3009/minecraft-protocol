package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialServerClientInteropTest {
    @Test
    fun productionClientReachesPlayAgainstOfficialServer() = runTest(
        timeout = 5.minutes,
    ) {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "protocol-client",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
        OfficialServerClientInteropRunner.run(
            environment = environment,
            workDirectory = environment.freshWorkDirectory(
                "official-server/${MinecraftProtocol.MINECRAFT_VERSION}",
            ),
            report = environment.reportFile(
                "official-server-client.json",
            ),
        )
    }
}
