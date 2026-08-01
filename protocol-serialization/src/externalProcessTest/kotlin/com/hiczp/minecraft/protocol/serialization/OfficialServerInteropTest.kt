package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import io.ktor.util.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialServerInteropTest {
    @Test
    fun statusLoginAndConfigurationInteroperateWithOfficialServer() = runTest(
        timeout = 5.minutes,
    ) {
        if (
            (PlatformUtils.IS_JS || PlatformUtils.IS_WASM_JS) &&
            !PlatformUtils.IS_NODE
        ) {
            return@runTest
        }
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "protocol-serialization",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
        OfficialServerInteropRunner.run(
            environment = environment,
            workDirectory = environment.freshWorkDirectory(
                "official-server/${MinecraftProtocol.MINECRAFT_VERSION}",
            ),
            report = environment.reportFile("official-server.json"),
        )
    }
}
