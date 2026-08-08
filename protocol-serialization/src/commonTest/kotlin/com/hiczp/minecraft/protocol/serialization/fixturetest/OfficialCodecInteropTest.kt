package com.hiczp.minecraft.protocol.serialization.fixturetest

import com.hiczp.minecraft.protocol.serialization.OfficialCodecFixtureGenerator
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
