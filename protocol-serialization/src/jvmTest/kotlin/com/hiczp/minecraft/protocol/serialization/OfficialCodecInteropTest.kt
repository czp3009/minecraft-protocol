package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import com.hiczp.minecraft.test.OfficialCodecOracle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class OfficialCodecInteropTest {
    @Test
    fun everyPacketFixturePassesThroughOfficialCodec() = runTest(
        timeout = 10.minutes,
    ) {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "protocol-serialization",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
        val fixtures = environment.temporaryFile(
            "official-codec/fixtures.json",
        )
        OfficialCodecFixtureGenerator.generate(fixtures)
        OfficialCodecOracle.verify(
            environment = environment,
            fixtures = fixtures,
            report = environment.reportFile("official-codec.json"),
        )
    }
}
