package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.io.Buffer

enum class MinecraftSessionSide {
    CLIENT,
    SERVER,
}

class MinecraftSession(
    val frames: MinecraftFrameStream,
    val side: MinecraftSessionSide,
    var format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
) {
    var state: ConnectionState = ConnectionState.HANDSHAKE
        private set

    val outboundDirection: PacketDirection
        get() =
            if (side == MinecraftSessionSide.CLIENT) {
                PacketDirection.SERVERBOUND
            } else {
                PacketDirection.CLIENTBOUND
            }

    val inboundDirection: PacketDirection
        get() =
            if (side == MinecraftSessionSide.CLIENT) {
                PacketDirection.CLIENTBOUND
            } else {
                PacketDirection.SERVERBOUND
            }

    fun enableEncryption(sharedSecret: ByteArray) {
        frames.enableEncryption(sharedSecret)
    }

    suspend fun send(packet: Packet) {
        val codec = MinecraftPacketRegistry.codec(packet)
            ?: throw MinecraftSessionException(
                "No packet codec for ${packet::class.simpleName}",
            )
        if (codec.key.state != state) {
            throw MinecraftSessionException(
                "${packet::class.simpleName} belongs to ${codec.key.state}, but the session is in $state",
            )
        }
        if (codec.key.direction != outboundDirection) {
            throw MinecraftSessionException(
                "${packet::class.simpleName} is ${codec.key.direction}, but $side sends $outboundDirection packets",
            )
        }

        val packetData = Buffer()
        when (codec.framing) {
            PacketFraming.NORMAL -> packetData.writeVarInt(codec.key.id)
            PacketFraming.LEGACY_UNFRAMED ->
                packetData.writeByte(codec.key.id.toByte())
        }
        MinecraftPacketRegistry.encodePayloadToSink(
            packet,
            packetData,
            format,
        )
        val packetDataBytes = packetData.size.toInt()
        when (codec.framing) {
            PacketFraming.NORMAL ->
                frames.sendPacketData(packetData, packetDataBytes)

            PacketFraming.LEGACY_UNFRAMED ->
                frames.sendUnframedPacketData(packetData, packetDataBytes)
        }
        applyPostWireEffects(packet)
    }

    suspend fun receive(): Packet {
        val legacyAware =
            state == ConnectionState.HANDSHAKE &&
                    inboundDirection == PacketDirection.SERVERBOUND
        val packetData = Buffer()
        if (legacyAware) {
            frames.receivePacketDataOrLegacyToSink(
                packetData,
                legacyPacketId = LEGACY_SERVER_LIST_PING_ID,
                legacyPayloadSize = LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE,
            )
        } else {
            frames.receivePacketDataToSink(packetData)
        }
        val legacy =
            legacyAware &&
                    packetData.peek().readByte().toInt().and(0xFF) ==
                    LEGACY_SERVER_LIST_PING_ID
        val id = if (legacy) {
            packetData.readByte().toInt() and 0xFF
        } else {
            packetData.readPacketId()
        }
        val codec = MinecraftPacketRegistry.codec(
            state = state,
            direction = inboundDirection,
            id = id,
        ) ?: throw MinecraftSessionException(
            "No packet codec for $state $inboundDirection 0x${id.toString(16)}",
        )
        val expectedFraming =
            if (legacy) PacketFraming.LEGACY_UNFRAMED else PacketFraming.NORMAL
        if (codec.framing != expectedFraming) {
            throw MinecraftSessionException(
                "Packet 0x${id.toString(16)} used $expectedFraming framing but its codec requires ${codec.framing}",
            )
        }
        val packet = MinecraftPacketRegistry.decodePayloadFromSource(
            state = state,
            direction = inboundDirection,
            id = id,
            source = packetData,
            byteCount = packetData.size.toInt(),
            format = format,
        )
        applyPostWireEffects(packet)
        return packet
    }

    private fun applyPostWireEffects(packet: Packet) {
        when (packet) {
            is SetCompressionPacket ->
                frames.configureCompression(
                    packet.threshold.takeIf { it >= 0 },
                )

            is HandshakePacket ->
                state = when (packet.nextState) {
                    HandshakeNextState.STATUS -> ConnectionState.STATUS
                    HandshakeNextState.LOGIN,
                    HandshakeNextState.TRANSFER,
                        -> ConnectionState.LOGIN

                    HandshakeNextState.UNUSED ->
                        throw MinecraftSessionException(
                            "Handshake next state zero is invalid",
                        )
                }

            is LoginAcknowledgedPacket ->
                state = ConnectionState.CONFIGURATION

            is AcknowledgeFinishConfigurationPacket ->
                state = ConnectionState.PLAY

            is AcknowledgeConfigurationPacket ->
                state = ConnectionState.CONFIGURATION

            else -> Unit
        }
    }

    companion object {
        private const val LEGACY_SERVER_LIST_PING_ID = 0xFE
        private const val LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE = 1
    }
}

/** Invalid packet direction, state, identity, or session transition. */
class MinecraftSessionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun Buffer.readPacketId(): Int {
    var result = 0
    var shift = 0
    repeat(5) {
        if (exhausted()) {
            throw MinecraftSessionException(
                "Truncated packet ID VarInt",
            )
        }
        val current = readByte().toInt() and 0xFF
        result = result or ((current and 0x7F) shl shift)
        if (current and 0x80 == 0) return result
        shift += 7
    }
    throw MinecraftSessionException("Packet ID VarInt is too wide")
}

private fun Buffer.writeVarInt(value: Int) {
    var remaining = value
    do {
        var current = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) current = current or 0x80
        writeByte(current.toByte())
    } while (remaining != 0)
}
