package com.hiczp.minecraft.test

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MinecraftStatusProbeTest {
    @Test
    fun completesTheStatusAndPongExchange() = runTest {
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().bind(LOOPBACK, 0).use { listener ->
                val server = launch(start = CoroutineStart.UNDISPATCHED) {
                    serveStatusOnce(listener)
                }

                val status = MinecraftStatusProbe(selector).query(
                    host = LOOPBACK,
                    port = listener.port,
                    socketTimeoutMillis = 2_000,
                )

                assertEquals(
                    TEST_PROTOCOL_VERSION,
                    status.getValue("version")
                        .jsonObject
                        .getValue("protocol")
                        .jsonPrimitive
                        .int,
                )
                server.join()
            }
        }
    }

    @Test
    fun rejectsAConnectionClosedBeforeStatusIsPublished() = runTest {
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().bind(LOOPBACK, 0).use { listener ->
                val server = launch(start = CoroutineStart.UNDISPATCHED) {
                    listener.accept().use { socket ->
                        val input = socket.openReadChannel()
                        input.readTestFrame()
                        input.readTestFrame()
                    }
                }

                val failure = runCatching {
                    MinecraftStatusProbe(selector).query(
                        host = LOOPBACK,
                        port = listener.port,
                        socketTimeoutMillis = 2_000,
                    )
                }.exceptionOrNull()

                assertNotNull(failure)
                server.join()
            }
        }
    }

    private suspend fun serveStatusOnce(listener: ServerSocket) {
        listener.accept().use { socket ->
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            input.readTestFrame()
            input.readTestFrame()
            output.writeTestFrame(statusPacket())

            val ping = Buffer().apply { write(input.readTestFrame()) }
            assertEquals(1, ping.readTestVarInt())
            val payload = ping.readLong()
            output.writeTestFrame(
                Buffer().apply {
                    writeTestVarInt(1)
                    writeLong(payload)
                }.readByteArray(),
            )
        }
    }

    private fun statusPacket(): ByteArray {
        val status = buildJsonObject {
            put("description", "ready")
            putJsonObject("version") {
                put("name", "test")
                put("protocol", TEST_PROTOCOL_VERSION)
            }
        }
        val encoded = testJson.encodeToString(
            JsonElement.serializer(),
            status,
        ).encodeToByteArray()
        return Buffer().apply {
            writeTestVarInt(0)
            writeTestVarInt(encoded.size)
            write(encoded)
        }.readByteArray()
    }
}

private suspend fun ByteReadChannel.readTestFrame(): ByteArray {
    val length = readTestVarInt { readByte() }
    require(length in 1..TEST_MAXIMUM_FRAME_BYTES)
    return readByteArray(length)
}

private suspend fun ByteWriteChannel.writeTestFrame(payload: ByteArray) {
    val frame = Buffer().apply {
        writeTestVarInt(payload.size)
        write(payload)
    }.readByteArray()
    writeFully(frame)
    flush()
}

private suspend fun Buffer.readTestVarInt(): Int =
    readTestVarInt { readByte() }

private suspend fun readTestVarInt(readNext: suspend () -> Byte): Int {
    var result = 0
    repeat(5) { index ->
        val current = readNext().toInt() and 0xFF
        result = result or ((current and 0x7F) shl (index * 7))
        if (current and 0x80 == 0) return result
    }
    error("Oversized test VarInt")
}

private fun Buffer.writeTestVarInt(value: Int) {
    var remaining = value
    while (remaining and 0x7F.inv() != 0) {
        writeByte((remaining and 0x7F or 0x80).toByte())
        remaining = remaining ushr 7
    }
    writeByte(remaining.toByte())
}

private const val LOOPBACK = "127.0.0.1"
private const val TEST_PROTOCOL_VERSION = 776
private const val TEST_MAXIMUM_FRAME_BYTES = 1024 * 1024
