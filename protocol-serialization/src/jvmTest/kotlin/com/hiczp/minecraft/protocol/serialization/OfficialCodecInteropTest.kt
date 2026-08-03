package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialCodecOracle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialCodecInteropTest {
    @Test
    fun everyPacketFixturePassesThroughOfficialCodec() = runTest {
        val fixtures = MinecraftTestSupport.temporaryFile(
            "official-codec/fixtures.json",
        )
        OfficialCodecFixtureGenerator.generate(fixtures)
        OfficialCodecOracle.verify(
            fixtures = fixtures,
            report = MinecraftTestSupport.reportFile("official-codec.json"),
        )
    }
}
