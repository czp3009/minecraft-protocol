package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.serialization.MinecraftFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream

enum class MinecraftSessionSide {
    CLIENT,
    SERVER,
}

class MinecraftSession(
    val frames: MinecraftFrameStream,
    val side: MinecraftSessionSide,
    var format: MinecraftFormat = MinecraftFormat.Default,
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
        val encoded = MinecraftPacketRegistry.encodePayload(packet, format)
        if (encoded.key.state != state) {
            throw MinecraftSessionException(
                "${packet::class.simpleName} belongs to ${encoded.key.state}, " +
                        "but the session is in $state",
            )
        }
        if (encoded.key.direction != outboundDirection) {
            throw MinecraftSessionException(
                "${packet::class.simpleName} is ${encoded.key.direction}, " +
                        "but $side sends $outboundDirection packets",
            )
        }

        when (encoded.framing) {
            PacketFraming.NORMAL ->
                frames.sendPacketData(
                    encodePacketData(encoded.key.id, encoded.payload),
                )

            PacketFraming.LEGACY_UNFRAMED ->
                frames.sendUnframedPacketData(
                    byteArrayOf(encoded.key.id.toByte()) + encoded.payload,
                )
        }
        applyPostWireEffects(packet)
    }

    suspend fun receive(): Packet {
        val legacyAware =
            state == ConnectionState.HANDSHAKE &&
                    inboundDirection == PacketDirection.SERVERBOUND
        val packetData =
            if (legacyAware) {
                frames.receivePacketDataOrLegacy(
                    legacyPacketId = LEGACY_SERVER_LIST_PING_ID,
                    legacyPayloadSize = LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE,
                )
            } else {
                frames.receivePacketData()
            }
        val legacy =
            legacyAware &&
                    packetData.firstOrNull()?.toInt()?.and(0xFF) ==
                    LEGACY_SERVER_LIST_PING_ID
        val decodedData =
            if (legacy) {
                DecodedPacketData(
                    id = LEGACY_SERVER_LIST_PING_ID,
                    payload = packetData.copyOfRange(1, packetData.size),
                )
            } else {
                decodePacketData(packetData)
            }
        val codec = MinecraftPacketRegistry.codec(
            state = state,
            direction = inboundDirection,
            id = decodedData.id,
        ) ?: throw MinecraftSessionException(
            "No packet codec for $state $inboundDirection " +
                    "0x${decodedData.id.toString(16)}",
        )
        val expectedFraming =
            if (legacy) PacketFraming.LEGACY_UNFRAMED else PacketFraming.NORMAL
        if (codec.framing != expectedFraming) {
            throw MinecraftSessionException(
                "Packet 0x${decodedData.id.toString(16)} used $expectedFraming " +
                        "framing but its codec requires ${codec.framing}",
            )
        }
        val packet = try {
            MinecraftPacketRegistry.decodePayload(
                state = state,
                direction = inboundDirection,
                id = decodedData.id,
                payload = decodedData.payload,
                format = format,
            )
        } catch (failure: Throwable) {
            throw MinecraftSessionException(
                "Could not decode $state $inboundDirection packet " +
                        "0x${decodedData.id.toString(16)}",
                failure,
            )
        }
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

class MinecraftSessionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private class DecodedPacketData(
    val id: Int,
    val payload: ByteArray,
)

private fun encodePacketData(id: Int, payload: ByteArray): ByteArray =
    encodeVarInt(id) + payload

private fun decodePacketData(bytes: ByteArray): DecodedPacketData {
    var result = 0
    var shift = 0
    var position = 0
    while (position < bytes.size && position < 5) {
        val current = bytes[position++].toInt() and 0xFF
        result = result or ((current and 0x7F) shl shift)
        if (current and 0x80 == 0) {
            return DecodedPacketData(
                id = result,
                payload = bytes.copyOfRange(position, bytes.size),
            )
        }
        shift += 7
    }
    throw MinecraftSessionException("Truncated or oversized packet ID VarInt")
}

private fun encodeVarInt(value: Int): ByteArray {
    var remaining = value
    val bytes = ByteArray(5)
    var size = 0
    do {
        var current = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) current = current or 0x80
        bytes[size++] = current.toByte()
    } while (remaining != 0)
    return bytes.copyOf(size)
}
