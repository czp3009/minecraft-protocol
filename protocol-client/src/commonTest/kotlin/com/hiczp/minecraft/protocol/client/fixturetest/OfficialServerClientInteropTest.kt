package com.hiczp.minecraft.protocol.client.fixturetest

import com.hiczp.minecraft.protocol.client.OfficialServerClientInteropRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialServerClientInteropTest {
    @Test
    fun productionClientReachesPlayAgainstOfficialServer() = runTest(
        timeout = 4.minutes
    ) {
        OfficialServerClientInteropRunner.run()
    }
}
