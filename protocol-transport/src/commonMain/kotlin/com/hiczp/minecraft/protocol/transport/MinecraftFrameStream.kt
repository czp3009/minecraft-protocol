package com.hiczp.minecraft.protocol.transport

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * A bidirectional Minecraft byte stream with one sequential reader and one
 * sequential writer. Reading and writing may proceed concurrently; concurrent
 * calls within the same direction are not supported.
 */
class MinecraftFrameStream(
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val codec: MinecraftFrameCodec = MinecraftFrameCodec(),
) {
    private val wireTransition = MutableStateFlow<CompletableDeferred<Unit>?>(null)
    private var decryptor: MinecraftStreamCipher? = null
    private var encryptor: MinecraftStreamCipher? = null
    private val decryptInput = ByteArray(CHANNEL_COPY_BYTES)
    private val decryptOutput = ByteArray(CHANNEL_COPY_BYTES)
    private val encryptInput = ByteArray(CHANNEL_COPY_BYTES)
    private val encryptOutput = ByteArray(CHANNEL_COPY_BYTES)

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
    ) {
        val frame = Buffer()
        codec.encodeFrameToSink(
            packetData,
            packetDataByteCount,
            frame,
        )
        writeEncrypted(frame, frame.size)
    }

    /** Appends one complete frame, then commits state that affects later bytes. */
    suspend fun sendPacketDataAndCommit(
        packetData: Source,
        packetDataByteCount: Int,
        commit: suspend () -> Unit,
    ) {
        val frame = Buffer()
        codec.encodeFrameToSink(
            packetData,
            packetDataByteCount,
            frame,
        )
        val transition = CompletableDeferred<Unit>()
        check(wireTransition.compareAndSet(null, transition)) {
            "Another wire transition is already in progress"
        }
        try {
            writeEncrypted(frame, frame.size)
            commit()
            transition.complete(Unit)
            wireTransition.compareAndSet(transition, null)
        } catch (cause: Throwable) {
            transition.completeExceptionally(cause)
            wireTransition.compareAndSet(transition, null)
            throw cause
        }
    }

    /** In-memory adapter over [sendPacketDataAndCommit]. */
    suspend fun sendPacketDataAndCommit(
        packetData: ByteArray,
        commit: suspend () -> Unit,
    ) {
        val source = Buffer().apply { write(packetData) }
        sendPacketDataAndCommit(source, packetData.size, commit)
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
    ) {
        if (packetDataByteCount <= 0) {
            throw MinecraftTransportException("Packet data cannot be empty")
        }
        writeEncrypted(packetData, packetDataByteCount.toLong())
    }

    /** In-memory adapter over the streaming unframed send method. */
    suspend fun sendUnframedPacketData(packetData: ByteArray) {
        val source = Buffer().apply { write(packetData) }
        sendUnframedPacketData(source, packetData.size)
    }

    /** Flushes bytes buffered by the underlying write channel. */
    suspend fun flush() {
        output.flush()
    }

    fun cancel(cause: Throwable? = null) {
        input.cancel(cause)
        output.cancel(cause)
    }

    private suspend fun receivePacketDataToSink(
        sink: Sink,
        legacyPacketId: Int?,
        legacyPayloadSize: Int,
    ): Long {
        val firstEncrypted = input.readByte()
        wireTransition.value?.await()
        val first = decryptByte(firstEncrypted)
        if (legacyPacketId != null && first == legacyPacketId) {
            sink.writeByte(first.toByte())
            readDecryptedToSink(legacyPayloadSize, sink)
            return legacyPayloadSize + 1L
        }

        val frameLength = readEncryptedFrameLength(first)
        codec.validateFrameLength(frameLength)
        val frameBody = Buffer()
        readDecryptedToSink(frameLength, frameBody)
        return codec.decodeFrameBodyToSink(frameBody, frameLength, sink)
    }

    private suspend fun readDecryptedToSink(
        byteCount: Int,
        sink: Sink,
    ) {
        val cipher = decryptor
        if (cipher == null) {
            sink.write(input.readPacket(byteCount), byteCount.toLong())
            return
        }
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, decryptInput.size)
            input.readFully(decryptInput, start = 0, end = count)
            val written = cipher.process(decryptInput, 0, count, decryptOutput, 0)
            checkCipherOutput(count, written)
            sink.write(decryptOutput, endIndex = written)
            remaining -= count
        }
    }

    private suspend fun writeEncrypted(
        source: Source,
        byteCount: Long,
    ) {
        require(byteCount >= 0)
        val cipher = encryptor
        if (cipher == null) {
            output.writeBuffer(source, byteCount)
            return
        }
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, encryptInput.size.toLong()).toInt()
            var offset = 0
            while (offset < count) {
                val read = source.readAtMostTo(
                    encryptInput,
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
            val written = cipher.process(encryptInput, 0, count, encryptOutput, 0)
            checkCipherOutput(count, written)
            output.writeFully(encryptOutput, endIndex = written)
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
            current = decryptByte(input.readByte())
        }
        throw MinecraftTransportException(
            "Frame length is wider than 21 bits",
        )
    }

    private fun decryptByte(value: Byte): Int {
        val cipher = decryptor ?: return value.toInt() and 0xFF
        decryptInput[0] = value
        val written = cipher.process(decryptInput, 0, 1, decryptOutput, 0)
        checkCipherOutput(expected = 1, actual = written)
        return decryptOutput[0].toInt() and 0xFF
    }

    private fun checkCipherOutput(expected: Int, actual: Int) {
        if (actual != expected) {
            throw MinecraftTransportException(
                "AES/CFB8 transform produced $actual bytes for $expected input bytes",
            )
        }
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
    val frameStream: MinecraftFrameStream = MinecraftFrameStream(
        input = socket.openReadChannel(),
        output = socket.openWriteChannel(autoFlush),
        codec = MinecraftFrameCodec(configuration),
    )

    override fun close() {
        socket.close()
    }
}
