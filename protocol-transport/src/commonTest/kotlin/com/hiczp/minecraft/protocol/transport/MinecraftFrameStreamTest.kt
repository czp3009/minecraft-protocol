package com.hiczp.minecraft.protocol.transport

import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class MinecraftFrameStreamTest {
    @Test
    fun streamingSendAndReceiveLeaveCallerBoundariesIntact() = runTest {
        val channel = ByteChannel()
        val sender = MinecraftFrameStream(ByteChannel(), channel)
        val receiver = MinecraftFrameStream(channel, ByteChannel())
        sender.configureCompression(0)
        receiver.configureCompression(0)
        val packet = ByteArray(32_768) { index -> (index * 17).toByte() }
        val source = Buffer().apply {
            write(packet)
            writeByte(0x44)
        }
        val send = launch {
            sender.sendPacketData(source, packet.size)
            sender.flush()
        }
        val sink = Buffer()

        receiver.receivePacketDataToSink(sink)
        send.join()

        assertContentEquals(packet, sink.readByteArray())
        assertEquals(0x44, source.readByte().toInt() and 0xFF)
    }

    @Test
    fun readsAFrameArrivingOneByteAtATime() = runTest {
        val input = ByteChannel()
        val output = ByteChannel()
        val codec = MinecraftFrameCodec()
        val stream = MinecraftFrameStream(input, output, codec)
        val packetData = ByteArray(1_024) { (it * 13).toByte() }
        val frame = codec.encodeFrame(packetData)

        val writer = launch {
            frame.forEach { byte ->
                input.writeByte(byte)
                input.flush()
            }
            input.close()
        }

        assertContentEquals(packetData, stream.receivePacketData())
        writer.join()
    }

    @Test
    fun composesCompressionFramingAndEncryptionInWireOrder() = runTest {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        val client = MinecraftFrameStream(
            input = serverToClient,
            output = clientToServer,
        )
        val server = MinecraftFrameStream(
            input = clientToServer,
            output = serverToClient,
        )
        val secret = ByteArray(16) { (it * 7).toByte() }
        client.configureCompression(64)
        server.configureCompression(64)
        client.enableEncryption(secret)
        server.enableEncryption(secret)
        val clientPacket = ByteArray(4_096) { (it * 19).toByte() }
        val serverPacket = byteArrayOf(1, 2, 3, 4)

        client.sendPacketData(clientPacket)
        client.flush()
        assertContentEquals(clientPacket, server.receivePacketData())
        server.sendPacketData(serverPacket)
        server.flush()
        assertContentEquals(serverPacket, client.receivePacketData())
    }

    @Test
    fun preservesLegacyUnframedPacketsWithAndWithoutEncryption() = runTest {
        for (encrypted in listOf(false, true)) {
            val input = ByteChannel()
            val sender = MinecraftFrameStream(ByteChannel(), input)
            val receiver = MinecraftFrameStream(input, ByteChannel())
            if (encrypted) {
                val secret = ByteArray(16) { (it * 11).toByte() }
                sender.enableEncryption(secret)
                receiver.enableEncryption(secret)
            }
            val packet = byteArrayOf(0xFE.toByte(), 0x01)

            sender.sendUnframedPacketData(packet)
            sender.flush()

            assertContentEquals(
                packet,
                receiver.receivePacketDataOrLegacy(
                    legacyPacketId = 0xFE,
                    legacyPayloadSize = 1,
                ),
            )
        }
    }

    @Test
    fun rejectsInvalidLegacyArgumentsEmptyPayloadAndDuplicateEncryption() =
        runTest {
            val stream = MinecraftFrameStream(ByteChannel(), ByteChannel())
            assertFailsWith<IllegalArgumentException> {
                stream.receivePacketDataOrLegacy(-1, 1)
            }
            assertFailsWith<IllegalArgumentException> {
                stream.receivePacketDataOrLegacy(256, 1)
            }
            assertFailsWith<IllegalArgumentException> {
                stream.receivePacketDataOrLegacy(0xFE, -1)
            }
            assertFailsWith<MinecraftTransportException> {
                stream.sendUnframedPacketData(byteArrayOf())
            }

            val secret = ByteArray(16)
            stream.enableEncryption(secret)
            assertFailsWith<IllegalStateException> {
                stream.enableEncryption(secret)
            }
        }

    @Test
    fun buffersOrdinaryFramesUntilExplicitlyFlushed() = runTest {
        val channel = ByteChannel()
        val sender = MinecraftFrameStream(ByteChannel(), channel)
        val receiver = MinecraftFrameStream(channel, ByteChannel())
        val packet = ByteArray(4_096) { 0x11 }

        sender.sendPacketData(packet)
        assertEquals(0, channel.availableForRead)

        sender.flush()
        assertContentEquals(packet, receiver.receivePacketData())
    }

    @Test
    fun immediateResponsesWaitForTheSendingSideWireCommit() = runTest {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        val clientOutput = CountingFlushByteWriteChannel(clientToServer)
        val client = MinecraftFrameStream(serverToClient, clientOutput)
        val server = MinecraftFrameStream(clientToServer, serverToClient)
        val responseSent = CompletableDeferred<Unit>()
        var committed = false
        val response = async {
            client.receivePacketData().also {
                assertTrue(committed)
            }
        }
        val peer = launch {
            assertContentEquals(
                byteArrayOf(1),
                server.receivePacketData(),
            )
            server.sendPacketData(byteArrayOf(2))
            server.flush()
            responseSent.complete(Unit)
        }

        client.sendPacketDataAndCommit(byteArrayOf(1)) {
            clientToServer.flush()
            responseSent.await()
            committed = true
        }

        assertEquals(0, clientOutput.flushCount)
        assertContentEquals(byteArrayOf(2), response.await())
        peer.join()
    }
}

private class CountingFlushByteWriteChannel(
    private val delegate: ByteWriteChannel,
) : ByteWriteChannel by delegate {
    var flushCount: Int = 0
        private set

    override suspend fun flush() {
        flushCount++
        delegate.flush()
    }
}
