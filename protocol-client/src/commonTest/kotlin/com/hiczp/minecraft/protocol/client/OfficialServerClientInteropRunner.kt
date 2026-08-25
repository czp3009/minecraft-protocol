package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import com.hiczp.minecraft.test.use
import kotlinx.coroutines.CancellationException

internal object OfficialServerClientInteropRunner {
    suspend fun run() {
        MinecraftTestSupport.newOfficialServer(
            configuration = OfficialMinecraftServerConfiguration(
                properties = mapOf(
                    "level-name" to "client-interop-world",
                    "motd" to "minecraft-protocol production client interop",
                ),
            ),
        ).use { server ->
            var phase = "status query"
            try {
                OfficialServerClientScenario.run(
                    host = server.endpoint.host,
                    port = server.endpoint.port,
                ) { currentPhase ->
                    phase = currentPhase
                }
                check(MinecraftTestSupport.closeProcess(server) == 0) {
                    "Official server did not stop cleanly"
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val serverLog = try {
                    MinecraftTestSupport.logText(server)
                } catch (logFailure: CancellationException) {
                    logFailure.addSuppressed(failure)
                    throw logFailure
                } catch (logFailure: Throwable) {
                    failure.addSuppressed(logFailure)
                    "<official server log unavailable>"
                }
                throw AssertionError(
                    """
                    |Official production-client interop failed during $phase.
                    |--- client failure ---
                    |$failure
                    |cause: ${failure.cause}
                    |--- official server log ---
                    |$serverLog
                    """.trimMargin(),
                    failure,
                )
            }
        }
    }
}
