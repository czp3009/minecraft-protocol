package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ChatMode
import com.hiczp.minecraft.protocol.model.type.ClientInformation
import com.hiczp.minecraft.protocol.model.type.MainHand
import com.hiczp.minecraft.protocol.model.type.ParticleStatus
import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import com.hiczp.minecraft.test.use
import kotlin.uuid.Uuid

/**
 * Black-box smoke test against the exact official server in offline mode.
 *
 * Process management remains inside the JVM fixture host. Network framing
 * uses the portable production transport, so the same black-box scenario can
 * run on each standard test runtime that can reach the Fixture Host.
 */
internal object OfficialServerInteropRunner {
    suspend fun run(
        openTransport: suspend (Int) -> OfficialServerTransport,
    ) {
        MinecraftTestSupport.newOfficialServer(
            configuration = OfficialMinecraftServerConfiguration(
                properties = mapOf(
                    "level-name" to "interop-world",
                    "motd" to "minecraft-protocol official interop",
                ),
            ),
        ).use { server ->
            val port = server.endpoint.port
            try {
                verifyStatus(port, openTransport)
                verifyOfflineLoginAndConfiguration(port, openTransport)
                check(MinecraftTestSupport.closeProcess(server) == 0) {
                    "Official server did not stop cleanly"
                }
            } catch (failure: Throwable) {
                throw AssertionError(
                    """
                    |Official server interop failed.
                    |--- official server log ---
                    |${MinecraftTestSupport.logText(server)}
                    """.trimMargin(),
                    failure,
                )
            }
        }
    }

    private suspend fun verifyStatus(
        port: Int,
        openTransport: suspend (Int) -> OfficialServerTransport,
    ) {
        withConnection(port, openTransport) { connection ->
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
                "Official status did not advertise protocol ${MinecraftProtocol.PROTOCOL_VERSION}: ${response.jsonResponse}"
            }

            val timestamp = 0x0102_0304_0506_0708L
            connection.send(StatusPingRequestPacket(timestamp))
            check(connection.receive(ConnectionState.STATUS) == StatusPongResponsePacket(timestamp)) {
                "Official status pong did not preserve its fixed-width timestamp"
            }
        }
    }

    private suspend fun verifyOfflineLoginAndConfiguration(
        port: Int,
        openTransport: suspend (Int) -> OfficialServerTransport,
    ) {
        withConnection(port, openTransport) { connection ->
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
                    playerUuid = Uuid.fromLongs(1L, 2L),
                ),
            )

            var state = ConnectionState.LOGIN
            var loginSucceeded = false
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
                    }

                    is CodeOfConductPacket -> {
                        connection.send(AcceptCodeOfConductPacket)
                    }

                    is FinishConfigurationPacket -> {
                        connection.send(AcknowledgeFinishConfigurationPacket)
                        state = ConnectionState.PLAY
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
                "Official server did not negotiate the configured compression threshold; received ${connection.compressionThreshold}"
            }
        }
    }

    private suspend fun <T> withConnection(
        port: Int,
        openTransport: suspend (Int) -> OfficialServerTransport,
        block: suspend (FramedConnection) -> T,
    ): T {
        val transport = openTransport(port)
        try {
            return block(FramedConnection(transport))
        } finally {
            transport.close()
        }
    }

    private class FramedConnection(
        private val transport: OfficialServerTransport,
    ) {
        private val format = MinecraftProtocolFormat(
            MinecraftProtocolFormatConfiguration(chunkSectionCount = 24),
        )
        var compressionThreshold: Int? = null
            private set

        suspend fun send(packet: Packet) {
            val encoded = MinecraftPacketRegistry.encodePayload(packet, format)
            val body = ByteArrayOutput()
            body.writeVarInt(encoded.key.id)
            body.write(encoded.payload)
            val bytes = body.toByteArray()
            transport.sendPacketData(bytes)
        }

        suspend fun receive(state: ConnectionState): Packet {
            val frame = transport.receivePacketData()
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
                    "Could not decode $state clientbound packet 0x${packetId.toString(16)} payload=${payload.toHexString()}",
                    failure,
                )
            }
            if (packet is SetCompressionPacket) {
                compressionThreshold = packet.threshold
                transport.configureCompression(packet.threshold)
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
}

internal interface OfficialServerTransport {
    suspend fun sendPacketData(packetData: ByteArray)

    suspend fun receivePacketData(): ByteArray

    fun configureCompression(threshold: Int)

    fun close()
}
