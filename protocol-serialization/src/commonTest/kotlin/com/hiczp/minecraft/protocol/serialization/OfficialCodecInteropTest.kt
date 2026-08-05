package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.test.MinecraftTestSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialCodecInteropTest {
    @Test
    fun everyPacketFixturePassesThroughOfficialCodec() = runTest {
        MinecraftTestSupport.verifyOfficialCodec(
            fixtures = OfficialCodecFixtureGenerator.generate(),
        )
    }
}
