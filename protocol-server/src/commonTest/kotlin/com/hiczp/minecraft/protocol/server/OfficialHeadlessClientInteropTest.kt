package com.hiczp.minecraft.protocol.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialHeadlessClientInteropTest {
    @Test
    fun officialHeadlessClientReachesPlayAgainstLibraryServer() = runTest {
        OfficialClientEndToEndRunner.run()
    }
}
