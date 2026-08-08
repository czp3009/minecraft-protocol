package com.hiczp.minecraft.protocol.server.fixturetest

import com.hiczp.minecraft.protocol.server.HeadlessClientEndToEndRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialHeadlessClientInteropTest {
    @Test
    fun officialHeadlessClientReachesPlayAgainstLibraryServer() = runTest(
        timeout = 2.minutes,
    ) {
        HeadlessClientEndToEndRunner.run()
    }
}
