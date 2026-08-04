package com.hiczp.minecraft.protocol.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialServerClientInteropTest {
    @Test
    fun productionClientReachesPlayAgainstOfficialServer() = runTest {
        OfficialServerClientInteropRunner.run()
    }
}
