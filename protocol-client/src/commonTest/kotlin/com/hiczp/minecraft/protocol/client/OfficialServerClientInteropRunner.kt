package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import com.hiczp.minecraft.test.useRemote

internal object OfficialServerClientInteropRunner {
    suspend fun run() {
        MinecraftTestSupport.newOfficialServer(
            configuration = OfficialMinecraftServerConfiguration(
                properties = mapOf(
                    "level-name" to "client-interop-world",
                    "motd" to "minecraft-protocol production client interop",
                ),
            ),
        ).useRemote { server ->
            var phase = "status query"
            try {
                OfficialServerClientScenario.run(
                    host = server.endpoint.host,
                    port = server.endpoint.port,
                ) { currentPhase ->
                    phase = currentPhase
                }
                check(server.stop() == 0) {
                    "Official server did not stop cleanly"
                }
            } catch (failure: Throwable) {
                throw AssertionError(
                    """
                    |Official production-client interop failed during $phase.
                    |--- official server log ---
                    |${server.logText()}
                    """.trimMargin(),
                    failure,
                )
            }
        }
    }
}
