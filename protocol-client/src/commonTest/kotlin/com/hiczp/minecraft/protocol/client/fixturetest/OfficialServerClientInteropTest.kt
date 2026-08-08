package com.hiczp.minecraft.protocol.client.fixturetest

import com.hiczp.minecraft.protocol.client.OfficialServerClientInteropRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialServerClientInteropTest {
    @Test
    fun productionClientReachesPlayAgainstOfficialServer() = runTest {
        OfficialServerClientInteropRunner.run()
    }
}
