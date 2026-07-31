package com.hiczp.minecraft.protocol.serialization

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class PacketFramingTest {
    @Test
    fun `uncompressed frame length covers one and multi-byte VarInts`() {
        for (size in listOf(1, 127, 128, 16_384)) {
            val payload = ByteArray(size) { it.toByte() }
            assertContentEquals(payload, roundTrip(payload, compressionThreshold = null))
        }
    }

    @Test
    fun `compression framing covers below at and above threshold`() {
        val threshold = 64
        for (size in listOf(1, threshold - 1, threshold, threshold + 1, 4_096)) {
            val payload = ByteArray(size) { (it * 31).toByte() }
            assertContentEquals(payload, roundTrip(payload, threshold))
        }
    }

    @Test
    fun `reader tolerates a stream that returns only one byte at a time`() {
        val payload = ByteArray(1_024) { (it * 17).toByte() }
        val output = ByteArrayOutputStream()
        JvmPacketFraming.writeFrame(output, payload, compressionThreshold = 32)
        val oneByteInput = object : InputStream() {
            private val delegate = ByteArrayInputStream(output.toByteArray())

            override fun read(): Int = delegate.read()

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                delegate.read(bytes, offset, minOf(length, 1))
        }

        assertContentEquals(
            payload,
            JvmPacketFraming.readFrame(oneByteInput, compressionThreshold = 32),
        )
    }

    @Test
    fun `truncated oversized and non-minimal frames are rejected`() {
        assertFailsWith<EOFException> {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(byteArrayOf(0x80.toByte())),
                compressionThreshold = null,
            )
        }
        assertFailsWith<EOFException> {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(byteArrayOf(0x02, 0x01)),
                compressionThreshold = null,
            )
        }
        assertFails {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(
                    byteArrayOf(
                        0x80.toByte(),
                        0x80.toByte(),
                        0x80.toByte(),
                        0x80.toByte(),
                        0x80.toByte(),
                        0x00,
                    ),
                ),
                compressionThreshold = null,
            )
        }
        assertFails {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(byteArrayOf(0x81.toByte(), 0x00, 0x01)),
                compressionThreshold = null,
            )
        }
        assertFails {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(byteArrayOf(0x08)),
                compressionThreshold = null,
                maximumFrameSize = 7,
            )
        }
    }

    @Test
    fun `compression threshold and zlib length invariants are enforced`() {
        assertFails {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(framed(byteArrayOf(0x00, 0x01, 0x02, 0x03))),
                compressionThreshold = 3,
            )
        }

        val valid = ByteArrayOutputStream().also {
            JvmPacketFraming.writeFrame(
                it,
                ByteArray(64) { index -> index.toByte() },
                compressionThreshold = 64,
            )
        }.toByteArray()
        valid[1] = 65
        assertFails {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(valid),
                compressionThreshold = 64,
            )
        }

        assertFails {
            JvmPacketFraming.readFrame(
                ByteArrayInputStream(framed(byteArrayOf(0x40, 0x78, 0x01, 0x00))),
                compressionThreshold = 64,
            )
        }
    }

    private fun roundTrip(
        payload: ByteArray,
        compressionThreshold: Int?,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        JvmPacketFraming.writeFrame(output, payload, compressionThreshold)
        return JvmPacketFraming.readFrame(
            ByteArrayInputStream(output.toByteArray()),
            compressionThreshold,
        )
    }

    private fun framed(body: ByteArray): ByteArray =
        byteArrayOf(body.size.toByte()) + body
}
