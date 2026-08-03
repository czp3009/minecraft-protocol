package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.test.MinecraftTestSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialServerClientInteropTest {
    @Test
    fun productionClientReachesPlayAgainstOfficialServer() = runTest {
        OfficialServerClientInteropRunner.run(
            report = MinecraftTestSupport.reportFile(
                "official-server-client.json",
            ),
        )
    }
}
