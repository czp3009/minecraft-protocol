package com.hiczp.minecraft.test

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A minimal, protocol-level readiness check for an external vanilla server. */
internal class MinecraftStatusProbe(
    private val selectorManager: SelectorManager,
) {
    suspend fun query(
        host: String,
        port: Int,
        socketTimeoutMillis: Long,
    ): JsonObject {
        require(host.isNotEmpty()) { "Status host is empty" }
        require(port in 1..0xFFFF) { "Invalid status port: $port" }
        require(socketTimeoutMillis > 0) {
            "Status socket timeout must be positive"
        }
        return aSocket(selectorManager).tcp().connect(host, port) {
            socketTimeout = socketTimeoutMillis
        }.use { socket ->
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            output.writeFrame(handshakePayload(host, port))
            output.writeFrame(byteArrayOf(STATUS_REQUEST_PACKET_ID))

            val status = readStatus(input)
            val pingPayload = STATUS_PING_PAYLOAD
            output.writeFrame(
                Buffer().apply {
                    writeVarInt(STATUS_PING_PACKET_ID)
                    writeLong(pingPayload)
                }.readByteArray(),
            )
            readPong(input, pingPayload)
            status
        }
    }

    private fun handshakePayload(host: String, port: Int): ByteArray =
        Buffer().apply {
            writeVarInt(HANDSHAKE_PACKET_ID)
            // Status handshakes are answered independently of the client's
            // protocol version. The returned JSON carries the authoritative
            // server version used by the caller's actual interoperability test.
            writeVarInt(STATUS_PROBE_PROTOCOL_VERSION)
            val hostBytes = host.encodeToByteArray()
            writeVarInt(hostBytes.size)
            write(hostBytes)
            writeShort(port.toShort())
            writeVarInt(STATUS_NEXT_STATE)
        }.readByteArray()

    private suspend fun readStatus(input: ByteReadChannel): JsonObject {
        val payload = input.readFrame()
        val packet = Buffer().apply { write(payload) }
        check(packet.readVarInt() == STATUS_RESPONSE_PACKET_ID) {
            "Official server returned a non-status packet"
        }
        val jsonLength = packet.readVarInt()
        check(jsonLength in 1..MAXIMUM_STATUS_JSON_BYTES) {
            "Official status JSON has invalid length $jsonLength"
        }
        val json = packet.readByteArray(jsonLength)
            .decodeToString()
            .let(testJson::parseToJsonElement)
            .jsonObject
        check(packet.exhausted()) {
            "Official status response has trailing bytes"
        }
        json.getValue("version")
            .jsonObject
            .getValue("protocol")
            .jsonPrimitive
            .int
        return json
    }

    private suspend fun readPong(
        input: ByteReadChannel,
        expectedPayload: Long,
    ) {
        val payload = input.readFrame()
        val packet = Buffer().apply { write(payload) }
        check(packet.readVarInt() == STATUS_PONG_PACKET_ID) {
            "Official server returned a non-pong packet"
        }
        check(packet.readLong() == expectedPayload) {
            "Official status pong did not preserve its payload"
        }
        check(packet.exhausted()) {
            "Official status pong has trailing bytes"
        }
    }
}

private suspend fun ByteWriteChannel.writeFrame(payload: ByteArray) {
    val frame = Buffer().apply {
        writeVarInt(payload.size)
        write(payload)
    }.readByteArray()
    writeFully(frame)
    flush()
}

private suspend fun ByteReadChannel.readFrame(): ByteArray {
    val length = readVarInt()
    check(length in 1..MAXIMUM_STATUS_FRAME_BYTES) {
        "Official status frame has invalid length $length"
    }
    return readByteArray(length)
}

private suspend fun ByteReadChannel.readVarInt(): Int =
    decodeVarInt { readByte() }

private suspend fun Buffer.readVarInt(): Int =
    decodeVarInt { readByte() }

private suspend fun decodeVarInt(readNext: suspend () -> Byte): Int {
    var result = 0
    repeat(MAXIMUM_VAR_INT_BYTES) { index ->
        val current = readNext().toInt() and 0xFF
        result = result or ((current and 0x7F) shl (index * 7))
        if (current and 0x80 == 0) return result
    }
    error("Official status data contains an oversized VarInt")
}

private fun Buffer.writeVarInt(value: Int) {
    var remaining = value
    while (remaining and 0x7F.inv() != 0) {
        writeByte((remaining and 0x7F or 0x80).toByte())
        remaining = remaining ushr 7
    }
    writeByte(remaining.toByte())
}

private const val HANDSHAKE_PACKET_ID = 0
private const val STATUS_PROBE_PROTOCOL_VERSION = 0
private const val STATUS_NEXT_STATE = 1
private const val STATUS_REQUEST_PACKET_ID: Byte = 0
private const val STATUS_RESPONSE_PACKET_ID = 0
private const val STATUS_PING_PACKET_ID = 1
private const val STATUS_PONG_PACKET_ID = 1
private const val STATUS_PING_PAYLOAD = 0x0102_0304_0506_0708L
private const val MAXIMUM_VAR_INT_BYTES = 5
private const val MAXIMUM_STATUS_JSON_BYTES = 1024 * 1024
private const val MAXIMUM_STATUS_FRAME_BYTES = MAXIMUM_STATUS_JSON_BYTES + 16
