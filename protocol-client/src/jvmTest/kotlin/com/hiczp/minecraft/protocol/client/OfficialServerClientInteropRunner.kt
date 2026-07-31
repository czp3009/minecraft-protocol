package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import com.hiczp.minecraft.test.startOfficialServer
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

internal object OfficialServerClientInteropRunner {
    fun run(
        environment: MinecraftTestEnvironment,
        workDirectory: Path,
        report: Path,
    ) {
        val serverJar = environment.officialServer().jar
        Files.createDirectories(workDirectory)
        Files.createDirectories(report.parent)
        val port = ServerSocket(0).use { it.localPort }
        writeServerConfiguration(workDirectory, port)

        environment.startOfficialServer(
            workDirectory = workDirectory,
            threadName = "official-client-interop-log",
        ).use { server ->
            try {
                server.waitForLog(
                    "[Server thread/INFO]: Done (",
                    Duration.ofMinutes(2),
                )
                server.waitForPort(
                    "127.0.0.1",
                    port,
                    Duration.ofMinutes(2),
                )
                val result = runBlocking {
                    SelectorManager(Dispatchers.IO).use { selector ->
                        MinecraftClientConnection.connect(
                            selectorManager = selector,
                            host = "127.0.0.1",
                            port = port,
                        ).use { statusClient ->
                            val status = statusClient.protocol.queryStatus(
                                0x0102_0304_0506_0708,
                            )
                            check(
                                Regex(
                                    """"protocol"\s*:\s*${MinecraftProtocol.PROTOCOL_VERSION}""",
                                ).containsMatchIn(
                                    status.response.jsonResponse,
                                ),
                            ) {
                                "Official status did not advertise protocol " +
                                        MinecraftProtocol.PROTOCOL_VERSION
                            }
                        }

                        MinecraftClientConnection.connect(
                            selectorManager = selector,
                            host = "127.0.0.1",
                            port = port,
                        ).use { loginClient ->
                            val login = loginClient.protocol.login(
                                MinecraftOfflineIdentity("KmpClientProbe"),
                                options = MinecraftClientOptions(
                                    information =
                                        MinecraftClientOptions()
                                            .information.copy(
                                                viewDistance = 2,
                                            ),
                                ),
                            )
                            check(
                                loginClient.transport.frames.codec
                                    .compressionThreshold == 64,
                            ) {
                                "Official server did not negotiate " +
                                        "compression threshold 64"
                            }
                            verifyVanillaConfiguration(login)
                            login
                        }
                    }
                }
                writeReport(report, serverJar, result)
                println(
                    "Production client reached Play against official " +
                            "Minecraft " +
                            MinecraftProtocol.MINECRAFT_VERSION,
                )
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Official production-client interop failed.\n" +
                            "--- official server log ---\n" +
                            server.logText(),
                    failure,
                )
            }
        }
    }

    private fun verifyVanillaConfiguration(
        result: MinecraftClientLoginResult,
    ) {
        val configuration = result.configuration
        check(configuration.knownPacks?.knownPacks == VanillaProtocolData.knownPacks) {
            "Official Known Packs differ from protocol-vanilla-data"
        }
        check(configuration.featureFlags == VanillaProtocolData.featureFlags) {
            "Official Feature Flags differ from protocol-vanilla-data"
        }
        check(
            configuration.registries ==
                    VanillaProtocolData.registryPackets(VanillaProtocolData.knownPacks),
        ) {
            "Official compact registries differ from protocol-vanilla-data"
        }
        check(
            configuration.tags != null &&
                    tagsSemanticallyEqual(configuration.tags, VanillaProtocolData.tags),
        ) {
            "Official tags differ from protocol-vanilla-data"
        }
    }

    private fun tagsSemanticallyEqual(
        first: ConfigurationUpdateTagsPacket,
        second: ConfigurationUpdateTagsPacket,
    ): Boolean =
        first.registries.associate { registry ->
            registry.registry to registry.tags.associate { tag ->
                tag.name to tag.entries.toSet()
            }
        } ==
                second.registries.associate { registry ->
                    registry.registry to registry.tags.associate { tag ->
                        tag.name to tag.entries.toSet()
                    }
                }

    private fun writeServerConfiguration(workDirectory: Path, port: Int) {
        Files.writeString(workDirectory.resolve("eula.txt"), "eula=true\n")
        Files.writeString(
            workDirectory.resolve("server.properties"),
            """
            |accepts-transfers=false
            |enable-status=true
            |enforce-secure-profile=false
            |generate-structures=false
            |level-name=client-interop-world-$port
            |level-type=minecraft:flat
            |max-players=1
            |max-tick-time=-1
            |motd=minecraft-protocol production client interop
            |network-compression-threshold=64
            |online-mode=false
            |pause-when-empty-seconds=-1
            |server-ip=127.0.0.1
            |server-port=$port
            |simulation-distance=2
            |spawn-protection=0
            |sync-chunk-writes=false
            |view-distance=2
            """.trimMargin(),
        )
    }

    private fun writeReport(
        output: Path,
        serverJar: Path,
        result: MinecraftClientLoginResult,
    ) {
        val serverSha256 = MessageDigest.getInstance("SHA-256").run {
            Files.newInputStream(serverJar).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    update(buffer, 0, read)
                }
            }
            digest().joinToString(separator = "") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }
        Files.writeString(
            output,
            """
            |{
            |  "schema_version": 1,
            |  "minecraft_version": "${MinecraftProtocol.MINECRAFT_VERSION}",
            |  "protocol_version": ${MinecraftProtocol.PROTOCOL_VERSION},
            |  "official_server_sha256": "$serverSha256",
            |  "client_stack": "protocol-client -> protocol-session -> protocol-transport",
            |  "status_round_trip": true,
            |  "online_mode": false,
            |  "configuration_registry_packets": ${result.configuration.registries.size},
            |  "configuration_matches_vanilla_data": true,
            |  "play_login_received": true
            |}
            """.trimMargin() + "\n",
        )
    }
}
