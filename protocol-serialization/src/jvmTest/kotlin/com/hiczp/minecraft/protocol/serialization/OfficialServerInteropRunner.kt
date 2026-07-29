package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/**
 * Black-box smoke test against the exact official server in offline mode.
 *
 * Framing and process management intentionally remain test-only: the
 * production modules currently own packet payload models and codecs, not a
 * transport/client implementation.
 */
internal object OfficialServerInteropRunner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 4) {
            "Expected <analysis-java> <official-server.jar> " +
                    "<work-directory> <report.json>"
        }
        val javaExecutable = Path.of(arguments[0]).toAbsolutePath().normalize()
        val serverJar = Path.of(arguments[1]).toAbsolutePath().normalize()
        val workDirectory = Path.of(arguments[2]).toAbsolutePath().normalize()
        val report = Path.of(arguments[3]).toAbsolutePath().normalize()
        require(Files.isRegularFile(javaExecutable)) {
            "Analysis Java does not exist: $javaExecutable"
        }
        require(Files.isRegularFile(serverJar)) {
            "Official server does not exist: $serverJar"
        }

        Files.createDirectories(workDirectory)
        Files.createDirectories(report.parent)
        Files.deleteIfExists(report)
        val port = ServerSocket(0).use { it.localPort }
        Files.writeString(workDirectory.resolve("eula.txt"), "eula=true\n")
        Files.writeString(
            workDirectory.resolve("server.properties"),
            """
            |accepts-transfers=false
            |enable-status=true
            |enforce-secure-profile=false
            |generate-structures=false
            |level-name=interop-world-$port
            |level-type=minecraft:flat
            |max-players=1
            |max-tick-time=-1
            |motd=minecraft-protocol official interop
            |network-compression-threshold=64
            |online-mode=false
            |server-ip=127.0.0.1
            |server-port=$port
            |simulation-distance=2
            |spawn-protection=0
            |sync-chunk-writes=false
            |view-distance=2
            """.trimMargin(),
        )

        val serverLog = StringBuilder()
        val process = ProcessBuilder(
            javaExecutable.absolutePathString(),
            "-Djava.awt.headless=true",
            "-jar",
            serverJar.absolutePathString(),
            "nogui",
        )
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val logThread = Thread.ofVirtual().name("official-server-log").start {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(serverLog) {
                        serverLog.appendLine(line)
                        if (serverLog.length > 200_000) {
                            serverLog.delete(0, serverLog.length - 150_000)
                        }
                    }
                }
            }
        }

        try {
            waitForServer(process, port, serverLog)
            verifyStatus(port)
            val login = verifyOfflineLoginAndConfiguration(port)
            writeReport(report, serverJar, login)
            println(
                "Official server interop passed for protocol " +
                        MinecraftProtocol.PROTOCOL_VERSION,
            )
        } catch (failure: Throwable) {
            val log = synchronized(serverLog) { serverLog.toString() }
            throw AssertionError(
                "Official server interop failed.\n--- official server log ---\n$log",
                failure,
            )
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            logThread.join(Duration.ofSeconds(5))
        }
    }

    private fun waitForServer(
        process: Process,
        port: Int,
        serverLog: StringBuilder,
    ) {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) {
                "Official server exited with ${process.exitValue()}: " +
                        synchronized(serverLog) { serverLog.toString() }
            }
            val ready = synchronized(serverLog) {
                serverLog.contains("[Server thread/INFO]: Done (")
            }
            if (!ready) {
                Thread.sleep(100)
                continue
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    return
                }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        error("Official server did not listen on port $port within two minutes")
    }

    private fun verifyStatus(port: Int) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val connection = FramedConnection(socket)
            connection.send(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = "127.0.0.1",
                    serverPort = port,
                    nextState = HandshakeNextState.STATUS,
                ),
            )
            connection.send(StatusRequestPacket)
            val response = connection.receive(ConnectionState.STATUS)
            check(response is StatusResponsePacket) {
                "Expected status response, received $response"
            }
            check(
                Regex(""""protocol"\s*:\s*${MinecraftProtocol.PROTOCOL_VERSION}""")
                    .containsMatchIn(response.jsonResponse),
            ) {
                "Official status did not advertise protocol " +
                        "${MinecraftProtocol.PROTOCOL_VERSION}: " +
                        response.jsonResponse
            }

            val timestamp = 0x0102_0304_0506_0708L
            connection.send(StatusPingRequestPacket(timestamp))
            check(connection.receive(ConnectionState.STATUS) == StatusPongResponsePacket(timestamp)) {
                "Official status pong did not preserve its fixed-width timestamp"
            }
        }
    }

    private fun verifyOfflineLoginAndConfiguration(
        port: Int,
    ): LoginInteropResult {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            val connection = FramedConnection(socket)
            connection.send(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = "127.0.0.1",
                    serverPort = port,
                    nextState = HandshakeNextState.LOGIN,
                ),
            )
            connection.send(
                LoginStartPacket(
                    name = "CodecProbe",
                    playerUuid = Uuid(1L, 2L),
                ),
            )

            var state = ConnectionState.LOGIN
            var loginSucceeded = false
            var configurationStarted = false
            var knownPacksExchanged = false
            var codeOfConductRequested = false
            var codeOfConductAccepted = false
            var configurationFinished = false
            var playLogin: PlayLoginPacket? = null
            val received = mutableListOf<String>()
            var remainingPackets = 512
            while (remainingPackets-- > 0 && playLogin == null) {
                val packet = connection.receive(state)
                received += packet::class.simpleName ?: packet.toString()
                when (packet) {
                    is LoginDisconnectPacket ->
                        error("Official server rejected offline login: ${packet.reason.json}")

                    is LoginCookieRequestPacket ->
                        connection.send(LoginCookieResponsePacket(packet.key, null))

                    is LoginPluginRequestPacket ->
                        connection.send(LoginPluginResponsePacket(packet.messageId, null))

                    is LoginSuccessPacket -> {
                        loginSucceeded = true
                        connection.send(LoginAcknowledgedPacket)
                        state = ConnectionState.CONFIGURATION
                        configurationStarted = true
                        connection.send(
                            ConfigurationClientInformationPacket(
                                ClientInformation(
                                    locale = "en_us",
                                    viewDistance = 2,
                                    chatMode = ChatMode.ENABLED,
                                    chatColors = true,
                                    displayedSkinParts = 0x7F,
                                    mainHand = MainHand.RIGHT,
                                    enableTextFiltering = false,
                                    allowServerListings = true,
                                    particleStatus = ParticleStatus.ALL,
                                ),
                            ),
                        )
                    }

                    is ConfigurationDisconnectPacket ->
                        error("Official server rejected configuration: ${packet.reason}")

                    is ConfigurationCookieRequestPacket ->
                        connection.send(ConfigurationCookieResponsePacket(packet.key, null))

                    is ConfigurationClientboundKeepAlivePacket ->
                        connection.send(ConfigurationServerboundKeepAlivePacket(packet.id))

                    is ConfigurationPingPacket ->
                        connection.send(ConfigurationPongPacket(packet.id))

                    is ConfigurationClientboundKnownPacksPacket -> {
                        connection.send(
                            ConfigurationServerboundKnownPacksPacket(packet.knownPacks),
                        )
                        knownPacksExchanged = true
                    }

                    is CodeOfConductPacket -> {
                        codeOfConductRequested = true
                        connection.send(AcceptCodeOfConductPacket)
                        codeOfConductAccepted = true
                    }

                    is FinishConfigurationPacket -> {
                        connection.send(AcknowledgeFinishConfigurationPacket)
                        state = ConnectionState.PLAY
                        configurationFinished = true
                    }

                    is PlayLoginPacket -> {
                        playLogin = packet
                    }

                    else -> Unit
                }
            }
            check(loginSucceeded) {
                "Official server never completed login; received $received"
            }
            check(playLogin != null) {
                "Official server never entered Play; received $received"
            }
            check(connection.compressionThreshold == 64) {
                "Official server did not negotiate the configured compression " +
                        "threshold; received ${connection.compressionThreshold}"
            }
            println("Official login/configuration packets: ${received.joinToString()}")
            return LoginInteropResult(
                loginSucceeded = loginSucceeded,
                compressionThreshold = connection.compressionThreshold,
                configurationStarted = configurationStarted,
                knownPacksExchanged = knownPacksExchanged,
                codeOfConductRequested = codeOfConductRequested,
                codeOfConductAccepted = codeOfConductAccepted,
                configurationFinished = configurationFinished,
                playLoginReceived = true,
            )
        }
    }

    private fun writeReport(
        output: Path,
        serverJar: Path,
        login: LoginInteropResult,
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
            digest().toHex()
        }
        Files.writeString(
            output,
            """
            |{
            |  "schema_version": 1,
            |  "minecraft_version": "${MinecraftProtocol.MINECRAFT_VERSION}",
            |  "protocol_version": ${MinecraftProtocol.PROTOCOL_VERSION},
            |  "official_server_sha256": "$serverSha256",
            |  "online_mode": false,
            |  "status": {
            |    "response_decoded": true,
            |    "ping_round_trip": true
            |  },
            |  "login": {
            |    "success": ${login.loginSucceeded},
            |    "compression_threshold": ${login.compressionThreshold},
            |    "configuration_started": ${login.configurationStarted},
            |    "known_packs_exchanged": ${login.knownPacksExchanged},
            |    "code_of_conduct_requested": ${login.codeOfConductRequested},
            |    "code_of_conduct_accepted": ${login.codeOfConductAccepted},
            |    "configuration_finished": ${login.configurationFinished},
            |    "play_login_received": ${login.playLoginReceived}
            |  }
            |}
            """.trimMargin() + "\n",
        )
    }

    private data class LoginInteropResult(
        val loginSucceeded: Boolean,
        val compressionThreshold: Int?,
        val configurationStarted: Boolean,
        val knownPacksExchanged: Boolean,
        val codeOfConductRequested: Boolean,
        val codeOfConductAccepted: Boolean,
        val configurationFinished: Boolean,
        val playLoginReceived: Boolean,
    )

    private class FramedConnection(socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val format = MinecraftFormat(
            MinecraftFormatConfiguration(chunkSectionCount = 24),
        )
        var compressionThreshold: Int? = null
            private set

        fun send(packet: Packet) {
            val encoded = MinecraftPacketRegistry.encodePayload(packet, format)
            val body = ByteArrayOutput()
            body.writeVarInt(encoded.key.id)
            body.write(encoded.payload)
            val bytes = body.toByteArray()
            TestPacketFraming.writeFrame(
                output,
                bytes,
                compressionThreshold,
            )
            output.flush()
        }

        fun receive(state: ConnectionState): Packet {
            val frame = TestPacketFraming.readFrame(
                input,
                compressionThreshold,
            )
            val cursor = ByteArrayInput(frame)
            val packetId = cursor.readVarInt()
            val payload = cursor.remainingBytes()
            val packet = try {
                MinecraftPacketRegistry.decodePayload(
                    state = state,
                    direction = PacketDirection.CLIENTBOUND,
                    id = packetId,
                    payload = payload,
                    format = format,
                )
            } catch (failure: Throwable) {
                throw IllegalStateException(
                    "Could not decode $state clientbound packet " +
                            "0x${packetId.toString(16)} payload=${payload.toHex()}",
                    failure,
                )
            }
            if (packet is SetCompressionPacket) {
                compressionThreshold = packet.threshold
            }
            return packet
        }
    }

    private class ByteArrayOutput {
        private var bytes = ByteArray(32)
        private var size = 0

        fun writeVarInt(value: Int) {
            var remaining = value
            do {
                var current = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) {
                    current = current or 0x80
                }
                write(current)
            } while (remaining != 0)
        }

        fun write(value: ByteArray) {
            ensure(size + value.size)
            value.copyInto(bytes, destinationOffset = size)
            size += value.size
        }

        private fun write(value: Int) {
            ensure(size + 1)
            bytes[size++] = value.toByte()
        }

        private fun ensure(required: Int) {
            if (required > bytes.size) {
                bytes = bytes.copyOf(maxOf(required, bytes.size * 2))
            }
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    private class ByteArrayInput(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun readVarInt(): Int {
            var result = 0
            var shift = 0
            while (shift < 35) {
                check(position < bytes.size) { "Truncated VarInt" }
                val current = bytes[position++].toInt() and 0xFF
                result = result or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) {
                    return result
                }
                shift += 7
            }
            error("VarInt is wider than five bytes")
        }

        fun remainingBytes(): ByteArray = bytes.copyOfRange(position, bytes.size)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}
