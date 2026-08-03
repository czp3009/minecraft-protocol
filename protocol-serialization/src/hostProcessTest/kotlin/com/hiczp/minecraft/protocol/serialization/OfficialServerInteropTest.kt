package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.test.MinecraftTestSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialServerInteropTest {
    @Test
    fun statusLoginAndConfigurationInteroperateWithOfficialServer() = runTest {
        OfficialServerInteropRunner.run(
            report = MinecraftTestSupport.reportFile("official-server.json"),
        )
    }
}
