package com.hiczp.minecraft.protocol.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MinecraftFrameCodecTest {
    @Test
    fun roundTripsAnUncompressedFrame() = runTest {
        val codec = MinecraftFrameCodec()
        val packetData = byteArrayOf(0x01, 0x02, 0x7F)

        assertContentEquals(packetData, codec.decodeFrame(codec.encodeFrame(packetData)))
    }

    @Test
    fun coversBothCompressionEnvelopeBranches() = runTest {
        val codec = MinecraftFrameCodec()
        codec.configureCompression(32)
        val small = ByteArray(31) { it.toByte() }
        val large = ByteArray(8_192) { (it * 17).toByte() }

        assertContentEquals(small, codec.decodeFrame(codec.encodeFrame(small)))
        assertContentEquals(large, codec.decodeFrame(codec.encodeFrame(large)))
    }

    @Test
    fun decodesAStandardZlibStream() = runTest {
        val codec = MinecraftFrameCodec()
        codec.configureCompression(1)
        val body = byteArrayOf(11) + hexBytes(
            "789ccb48cdc9c95728cf2fca4901001a0b045d",
        )

        assertContentEquals(
            "hello world".encodeToByteArray(),
            codec.decodeFrameBody(body),
        )
    }

    @Test
    fun rejectsMalformedAndInconsistentFrames() = runTest {
        val codec = MinecraftFrameCodec()
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrame(byteArrayOf(0))
        }
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrame(byteArrayOf(2, 1))
        }

        codec.configureCompression(8)
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrameBody(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        }
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrameBody(
                byteArrayOf(11) +
                        hexBytes("789ccb48cdc9c95728cf2fca4901001a0b045c"),
            )
        }
    }

    @Test
    fun rejectsOversizedDeclaredPacketsBeforeInflation() = runTest {
        val codec = MinecraftFrameCodec()
        codec.configureCompression(1)
        val oversizedLength =
            MinecraftTransportConfiguration.MAXIMUM_UNCOMPRESSED_PACKET_SIZE + 1

        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrameBody(
                encodeVarInt(oversizedLength) +
                        hexBytes("789c030000000001"),
            )
        }
    }

    @Test
    fun validatesConfigurationCompressionAndEncodeLimits() = runTest {
        assertFailsWith<IllegalArgumentException> {
            MinecraftTransportConfiguration(maximumFrameSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftTransportConfiguration(
                maximumFrameSize =
                    MinecraftTransportConfiguration.MAXIMUM_FRAME_SIZE + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftTransportConfiguration(
                maximumFrameSize = 16,
                maximumUncompressedPacketSize = 15,
            )
        }

        val codec = MinecraftFrameCodec(
            MinecraftTransportConfiguration(
                maximumFrameSize = 4,
                maximumUncompressedPacketSize = 8,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.configureCompression(-1)
        }
        assertFailsWith<MinecraftTransportException> {
            codec.encodeFrame(byteArrayOf())
        }
        assertFailsWith<MinecraftTransportException> {
            codec.encodeFrame(ByteArray(9))
        }
        assertFailsWith<MinecraftTransportException> {
            codec.encodeFrame(ByteArray(5) { it.toByte() })
        }

        codec.configureCompression(0)
        assertEquals(0, codec.compressionThreshold)
        codec.configureCompression(null)
        assertNull(codec.compressionThreshold)
    }

    @Test
    fun rejectsMalformedVarIntsAndEmptyCompressionPayloads() = runTest {
        val codec = MinecraftFrameCodec()
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrame(byteArrayOf(0x81.toByte(), 0x00, 0x01))
        }
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrame(
                byteArrayOf(
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x00,
                ),
            )
        }

        codec.configureCompression(8)
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrameBody(byteArrayOf(0))
        }
        assertFailsWith<MinecraftTransportException> {
            codec.decodeFrameBody(encodeVarInt(7) + Zlib.compress(ByteArray(7)))
        }
    }

    @Test
    fun optionalValidationAcceptsNoncanonicalCompressionThresholdBranches() =
        runTest {
            val codec = MinecraftFrameCodec(
                MinecraftTransportConfiguration(
                    validateCompressionThreshold = false,
                ),
            )
            codec.configureCompression(8)
            val atThreshold = ByteArray(8) { it.toByte() }
            assertContentEquals(
                atThreshold,
                codec.decodeFrameBody(byteArrayOf(0) + atThreshold),
            )

            val belowThreshold = byteArrayOf(1, 2, 3)
            assertContentEquals(
                belowThreshold,
                codec.decodeFrameBody(
                    encodeVarInt(belowThreshold.size) +
                            Zlib.compress(belowThreshold),
                ),
            )

            val permissiveVarInt = MinecraftFrameCodec(
                MinecraftTransportConfiguration(
                    rejectNonMinimalVarInts = false,
                ),
            )
            assertContentEquals(
                byteArrayOf(1),
                permissiveVarInt.decodeFrame(
                    byteArrayOf(0x81.toByte(), 0x00, 0x01),
                ),
            )
        }
}

private fun hexBytes(value: String): ByteArray =
    ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
