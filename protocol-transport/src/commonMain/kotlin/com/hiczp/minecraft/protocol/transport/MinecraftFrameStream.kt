package com.hiczp.minecraft.protocol.transport

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MinecraftFrameStream(
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val codec: MinecraftFrameCodec = MinecraftFrameCodec(),
) {
    private val receiveMutex = Mutex()
    private val sendMutex = Mutex()
    private var decryptor: MinecraftStreamCipher? = null
    private var encryptor: MinecraftStreamCipher? = null

    fun configureCompression(threshold: Int?) {
        codec.configureCompression(threshold)
    }

    fun enableEncryption(sharedSecret: ByteArray) {
        enableEncryption(
            encryptor = AesCfb8Cipher.encryptor(sharedSecret),
            decryptor = AesCfb8Cipher.decryptor(sharedSecret),
        )
    }

    fun enableEncryption(
        encryptor: MinecraftStreamCipher,
        decryptor: MinecraftStreamCipher,
    ) {
        check(this.encryptor == null && this.decryptor == null) {
            "Stream encryption is already enabled"
        }
        this.encryptor = encryptor
        this.decryptor = decryptor
    }

    suspend fun receivePacketData(): ByteArray =
        receivePacketData(legacyPacketId = null, legacyPayloadSize = 0)

    suspend fun receivePacketDataOrLegacy(
        legacyPacketId: Int,
        legacyPayloadSize: Int,
    ): ByteArray {
        require(legacyPacketId in 0..0xFF)
        require(legacyPayloadSize >= 0)
        return receivePacketData(legacyPacketId, legacyPayloadSize)
    }

    private suspend fun receivePacketData(
        legacyPacketId: Int?,
        legacyPayloadSize: Int,
    ): ByteArray = receiveMutex.withLock {
        val first = readDecryptedByte()
        if (legacyPacketId != null && first == legacyPacketId) {
            val encryptedPayload = input.readByteArray(legacyPayloadSize)
            val payload = decryptor?.process(encryptedPayload) ?: encryptedPayload
            return@withLock byteArrayOf(first.toByte()) + payload
        }
        val frameLength = readEncryptedFrameLength(first)
        codec.validateFrameLength(frameLength)
        val encryptedBody = input.readByteArray(frameLength)
        val frameBody = decryptor?.process(encryptedBody) ?: encryptedBody
        codec.decodeFrameBody(frameBody)
    }

    suspend fun sendPacketData(packetData: ByteArray) = sendMutex.withLock {
        val frame = codec.encodeFrame(packetData)
        val encryptedFrame = encryptor?.process(frame) ?: frame
        output.writeFully(encryptedFrame)
        output.flush()
    }

    suspend fun sendUnframedPacketData(packetData: ByteArray) =
        sendMutex.withLock {
            if (packetData.isEmpty()) {
                throw MinecraftTransportException("Packet data cannot be empty")
            }
            val encrypted = encryptor?.process(packetData) ?: packetData
            output.writeFully(encrypted)
            output.flush()
        }

    fun cancel(cause: Throwable? = null) {
        input.cancel(cause)
        output.cancel(cause)
    }

    private suspend fun readEncryptedFrameLength(firstByte: Int): Int {
        var result = 0
        var shift = 0
        var count = 0
        var current = firstByte
        while (count < 3) {
            result = result or ((current and 0x7F) shl shift)
            count++
            if (current and 0x80 == 0) {
                if (
                    codec.configuration.rejectNonMinimalVarInts &&
                    count != varIntSize(result)
                ) {
                    throw MinecraftTransportException(
                        "Non-minimal frame-length VarInt",
                    )
                }
                return result
            }
            shift += 7
            current = readDecryptedByte()
        }
        throw MinecraftTransportException("Frame length is wider than 21 bits")
    }

    private suspend fun readDecryptedByte(): Int {
        val encrypted = byteArrayOf(input.readByte())
        return (decryptor?.process(encrypted)?.single() ?: encrypted.single())
            .toInt() and 0xFF
    }
}

class MinecraftTransport(
    val socket: Socket,
    configuration: MinecraftTransportConfiguration =
        MinecraftTransportConfiguration(),
    autoFlush: Boolean = false,
) : Closeable {
    val input: ByteReadChannel = socket.openReadChannel()
    val output: ByteWriteChannel = socket.openWriteChannel(autoFlush)
    val frames: MinecraftFrameStream = MinecraftFrameStream(
        input = input,
        output = output,
        codec = MinecraftFrameCodec(configuration),
    )

    fun configureCompression(threshold: Int?) {
        frames.configureCompression(threshold)
    }

    fun enableEncryption(sharedSecret: ByteArray) {
        frames.enableEncryption(sharedSecret)
    }

    suspend fun receivePacketData(): ByteArray = frames.receivePacketData()

    suspend fun receivePacketDataOrLegacy(
        legacyPacketId: Int,
        legacyPayloadSize: Int,
    ): ByteArray =
        frames.receivePacketDataOrLegacy(legacyPacketId, legacyPayloadSize)

    suspend fun sendPacketData(packetData: ByteArray) {
        frames.sendPacketData(packetData)
    }

    suspend fun sendUnframedPacketData(packetData: ByteArray) {
        frames.sendUnframedPacketData(packetData)
    }

    override fun close() {
        socket.close()
    }
}
