package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.test.MinecraftTestSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialHeadlessClientInteropTest {
    @Test
    fun officialHeadlessClientReachesPlayAgainstLibraryServer() = runTest {
        OfficialClientEndToEndRunner.run(
            report = MinecraftTestSupport.reportFile(
                "official-client-headless.json",
            ),
        )
    }
}
