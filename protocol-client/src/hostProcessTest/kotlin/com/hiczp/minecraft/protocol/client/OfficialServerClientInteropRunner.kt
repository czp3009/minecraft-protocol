package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import com.hiczp.minecraft.test.writeJsonReport
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object OfficialServerClientInteropRunner {
    suspend fun run(
        report: Path,
    ) {
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
                val result = OfficialServerClientScenario.run(
                    host = server.endpoint.host,
                    port = server.endpoint.port,
                ) { currentPhase ->
                    phase = currentPhase
                }
                writeReport(report, server.officialServerSha256, result)
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

    private fun writeReport(
        output: Path,
        serverSha256: String,
        result: MinecraftClientLoginResult,
    ) {
        output.writeJsonReport(
            buildJsonObject {
                put("schema_version", 1)
                put("minecraft_version", MinecraftProtocol.MINECRAFT_VERSION)
                put("protocol_version", MinecraftProtocol.PROTOCOL_VERSION)
                put("official_server_sha256", serverSha256)
                put(
                    "client_stack",
                    "protocol-client -> protocol-session -> protocol-transport",
                )
                put("status_round_trip", true)
                put("online_mode", false)
                put(
                    "configuration_registry_packets",
                    result.configuration.registries.size,
                )
                put("configuration_matches_vanilla_data", true)
                put("play_login_received", true)
            },
        )
    }
}
