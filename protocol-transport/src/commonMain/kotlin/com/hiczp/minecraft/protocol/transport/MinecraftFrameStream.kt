package com.hiczp.minecraft.protocol.transport

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

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

    /** Receives one packet directly into a caller-owned [sink]. */
    suspend fun receivePacketDataToSink(sink: Sink): Long =
        receivePacketDataToSink(
            sink,
            legacyPacketId = null,
            legacyPayloadSize = 0,
        )

    /** In-memory adapter over [receivePacketDataToSink]. */
    suspend fun receivePacketData(): ByteArray {
        val buffer = Buffer()
        receivePacketDataToSink(buffer)
        return buffer.readByteArray()
    }

    /** Receives one packet or the legacy ping form into [sink]. */
    suspend fun receivePacketDataOrLegacyToSink(
        sink: Sink,
        legacyPacketId: Int,
        legacyPayloadSize: Int,
    ): Long {
        require(legacyPacketId in 0..0xFF)
        require(legacyPayloadSize >= 0)
        return receivePacketDataToSink(
            sink,
            legacyPacketId,
            legacyPayloadSize,
        )
    }

    /** In-memory adapter over [receivePacketDataOrLegacyToSink]. */
    suspend fun receivePacketDataOrLegacy(
        legacyPacketId: Int,
        legacyPayloadSize: Int,
    ): ByteArray {
        val buffer = Buffer()
        receivePacketDataOrLegacyToSink(
            buffer,
            legacyPacketId,
            legacyPayloadSize,
        )
        return buffer.readByteArray()
    }

    /**
     * Frames exactly [packetDataByteCount] bytes and sends them. The source is
     * not closed and bytes beyond the declared boundary remain unread.
     */
    suspend fun sendPacketData(
        packetData: Source,
        packetDataByteCount: Int,
    ) = sendMutex.withLock {
        val frame = Buffer()
        codec.encodeFrameToSink(
            packetData,
            packetDataByteCount,
            frame,
        )
        writeEncrypted(frame, frame.size)
        output.flush()
    }

    /** In-memory adapter over the streaming [sendPacketData] overload. */
    suspend fun sendPacketData(packetData: ByteArray) {
        val source = Buffer().apply { write(packetData) }
        sendPacketData(source, packetData.size)
    }

    /** Sends exactly [packetDataByteCount] unframed bytes. */
    suspend fun sendUnframedPacketData(
        packetData: Source,
        packetDataByteCount: Int,
    ) = sendMutex.withLock {
        if (packetDataByteCount <= 0) {
            throw MinecraftTransportException("Packet data cannot be empty")
        }
        writeEncrypted(packetData, packetDataByteCount.toLong())
        output.flush()
    }

    /** In-memory adapter over the streaming unframed send method. */
    suspend fun sendUnframedPacketData(packetData: ByteArray) {
        val source = Buffer().apply { write(packetData) }
        sendUnframedPacketData(source, packetData.size)
    }

    fun cancel(cause: Throwable? = null) {
        input.cancel(cause)
        output.cancel(cause)
    }

    private suspend fun receivePacketDataToSink(
        sink: Sink,
        legacyPacketId: Int?,
        legacyPayloadSize: Int,
    ): Long = receiveMutex.withLock {
        val first = readDecryptedByte()
        if (legacyPacketId != null && first == legacyPacketId) {
            sink.writeByte(first.toByte())
            readDecryptedToSink(legacyPayloadSize, sink)
            return@withLock legacyPayloadSize + 1L
        }

        val frameLength = readEncryptedFrameLength(first)
        codec.validateFrameLength(frameLength)
        val frameBody = Buffer()
        readDecryptedToSink(frameLength, frameBody)
        codec.decodeFrameBodyToSink(frameBody, frameLength, sink)
    }

    private suspend fun readDecryptedToSink(
        byteCount: Int,
        sink: Sink,
    ) {
        val encrypted = ByteArray(minOf(byteCount, CHANNEL_COPY_BYTES))
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, encrypted.size)
            input.readFully(encrypted, start = 0, end = count)
            val cipher = decryptor
            if (cipher == null) {
                sink.write(encrypted, endIndex = count)
            } else {
                val chunk = if (count == encrypted.size) {
                    encrypted
                } else {
                    encrypted.copyOf(count)
                }
                sink.write(cipher.process(chunk))
            }
            remaining -= count
        }
    }

    private suspend fun writeEncrypted(
        source: Source,
        byteCount: Long,
    ) {
        require(byteCount >= 0)
        val plaintext = ByteArray(
            minOf(byteCount, CHANNEL_COPY_BYTES.toLong()).toInt(),
        )
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, plaintext.size.toLong()).toInt()
            var offset = 0
            while (offset < count) {
                val read = source.readAtMostTo(
                    plaintext,
                    startIndex = offset,
                    endIndex = count,
                )
                if (read < 0) {
                    throw MinecraftTransportException(
                        "Packet source ended with ${remaining - offset} byte(s) missing",
                    )
                }
                offset += read
            }
            val cipher = encryptor
            if (cipher == null) {
                output.writeFully(plaintext, endIndex = count)
            } else {
                val chunk = if (count == plaintext.size) {
                    plaintext
                } else {
                    plaintext.copyOf(count)
                }
                output.writeFully(cipher.process(chunk))
            }
            remaining -= count
        }
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
        throw MinecraftTransportException(
            "Frame length is wider than 21 bits",
        )
    }

    private suspend fun readDecryptedByte(): Int {
        val encrypted = byteArrayOf(input.readByte())
        return (decryptor?.process(encrypted)?.single() ?: encrypted.single())
            .toInt() and 0xFF
    }

    private companion object {
        const val CHANNEL_COPY_BYTES = 8_192
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

    suspend fun receivePacketDataToSink(sink: Sink): Long =
        frames.receivePacketDataToSink(sink)

    suspend fun receivePacketData(): ByteArray = frames.receivePacketData()

    suspend fun receivePacketDataOrLegacyToSink(
        sink: Sink,
        legacyPacketId: Int,
        legacyPayloadSize: Int,
    ): Long = frames.receivePacketDataOrLegacyToSink(
        sink,
        legacyPacketId,
        legacyPayloadSize,
    )

    suspend fun receivePacketDataOrLegacy(
        legacyPacketId: Int,
        legacyPayloadSize: Int,
    ): ByteArray =
        frames.receivePacketDataOrLegacy(
            legacyPacketId,
            legacyPayloadSize,
        )

    suspend fun sendPacketData(
        packetData: Source,
        packetDataByteCount: Int,
    ) {
        frames.sendPacketData(packetData, packetDataByteCount)
    }

    suspend fun sendPacketData(packetData: ByteArray) {
        frames.sendPacketData(packetData)
    }

    suspend fun sendUnframedPacketData(
        packetData: Source,
        packetDataByteCount: Int,
    ) {
        frames.sendUnframedPacketData(packetData, packetDataByteCount)
    }

    suspend fun sendUnframedPacketData(packetData: ByteArray) {
        frames.sendUnframedPacketData(packetData)
    }

    override fun close() {
        socket.close()
    }
}
