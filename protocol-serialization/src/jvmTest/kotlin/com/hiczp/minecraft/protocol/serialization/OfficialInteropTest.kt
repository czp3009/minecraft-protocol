package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import com.hiczp.minecraft.test.OfficialCodecOracle
import kotlin.test.Test

class OfficialInteropTest {
    @Test
    fun statusLoginAndConfigurationInteroperateWithOfficialServer() {
        val environment = environment()
        OfficialServerInteropRunner.run(
            environment = environment,
            workDirectory = environment.freshWorkDirectory(
                "official-server/${MinecraftProtocol.MINECRAFT_VERSION}",
            ),
            report = environment.reportFile("official-server.json"),
        )
    }

    @Test
    fun everyPacketFixturePassesThroughOfficialCodec() {
        val environment = environment()
        val fixtures = environment.temporaryFile(
            "official-codec/fixtures.tsv",
        )
        OfficialCodecFixtureGenerator.generate(fixtures)
        OfficialCodecOracle.verify(
            environment = environment,
            fixtures = fixtures,
            report = environment.reportFile("official-codec.json"),
        )
    }

    private fun environment(): MinecraftTestEnvironment =
        MinecraftTestEnvironment.forModule(
            moduleName = "protocol-serialization",
            minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        )
}
