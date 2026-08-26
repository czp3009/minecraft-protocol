package com.hiczp.minecraft.protocol.transport

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.*

class MinecraftFrameCodecTest {
    @Test
    fun streamingMethodsRespectCallerProvidedBoundaries() {
        val packetData = ByteArray(32_768) { index ->
            (index * 29 + index / 7).toByte()
        }
        for (threshold in listOf<Int?>(null, 0, packetData.size + 1)) {
            val minecraftFrameCodec = MinecraftFrameCodec()
            minecraftFrameCodec.configureCompression(threshold)
            val packetSource = Buffer().apply {
                write(packetData)
                writeByte(0x5A)
            }
            val frame = Buffer()

            minecraftFrameCodec.encodeFrameToSink(
                packetSource,
                packetData.size,
                frame,
            )

            assertEquals(0x5A, packetSource.readByte().toInt() and 0xFF)
            val framedSource = Buffer().apply {
                write(frame, frame.size)
                writeByte(0x6B)
            }
            val decoded = Buffer()
            minecraftFrameCodec.decodeFrameToSink(framedSource, decoded)
            assertContentEquals(packetData, decoded.readByteArray())
            assertEquals(0x6B, framedSource.readByte().toInt() and 0xFF)
        }
    }

    @Test
    fun compressiblePacketMayExceedTheCompressedFrameCeiling() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(0)
        val packetData = ByteArray(
            MinecraftTransportConfiguration.MAXIMUM_FRAME_SIZE + 1,
        ) { index ->
            "minecraft"[index % "minecraft".length].code.toByte()
        }

        val frame = minecraftFrameCodec.encodeFrame(packetData)

        assertTrue(
            frame.size <
                    MinecraftTransportConfiguration.MAXIMUM_FRAME_SIZE,
        )
        assertContentEquals(packetData, minecraftFrameCodec.decodeFrame(frame))
    }

    @Test
    fun roundTripsAnUncompressedFrame() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        val packetData = byteArrayOf(0x01, 0x02, 0x7F)

        assertContentEquals(packetData, minecraftFrameCodec.decodeFrame(minecraftFrameCodec.encodeFrame(packetData)))
    }

    @Test
    fun coversBothCompressionEnvelopeBranches() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(32)
        val small = ByteArray(31) { it.toByte() }
        val large = ByteArray(8_192) { (it * 17).toByte() }

        assertContentEquals(small, minecraftFrameCodec.decodeFrame(minecraftFrameCodec.encodeFrame(small)))
        assertContentEquals(large, minecraftFrameCodec.decodeFrame(minecraftFrameCodec.encodeFrame(large)))
    }

    @Test
    fun roundTripsCompressionThresholdBoundariesAndDeterministicPayloads() =
        runTest {
            val random = Random(0x2602_0776)
            val thresholds = listOf(null, 0, 1, 2, 32, 256)
            thresholds.forEach { threshold ->
                val minecraftFrameCodec = MinecraftFrameCodec()
                minecraftFrameCodec.configureCompression(threshold)
                val sizes = buildSet {
                    add(1)
                    threshold?.let {
                        add(maxOf(1, it - 1))
                        add(maxOf(1, it))
                        add(maxOf(1, it + 1))
                    }
                    repeat(20) { add(random.nextInt(1, 4_097)) }
                }
                sizes.forEach { size ->
                    val packetData = ByteArray(size)
                    random.nextBytes(packetData)
                    assertContentEquals(
                        packetData,
                        minecraftFrameCodec.decodeFrame(minecraftFrameCodec.encodeFrame(packetData)),
                        "threshold=$threshold size=$size",
                    )
                }
            }
        }

    @Test
    fun roundTripsPayloadsAcrossCompressionBufferBoundaries() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(0)
        val random = Random(0x3520_4001)

        listOf(32_767, 32_768, 32_769, 35_204, 65_535, 65_536, 65_537)
            .forEach { size ->
                val packetData = ByteArray(size)
                random.nextBytes(packetData)
                assertContentEquals(
                    packetData,
                    minecraftFrameCodec.decodeFrame(minecraftFrameCodec.encodeFrame(packetData)),
                    "size=$size",
                )
            }
    }

    @Test
    fun decodesZlibMembersWhoseEpilogueCrossesAnInputBoundary() = runTest {
        val byteArray = ByteArray(4_201)
        Random(0x5A4C_4942).nextBytes(byteArray)
        var candidate: Pair<ByteArray, ByteArray>? = null
        for (size in 3_600..4_200) {
            val packetData = byteArray.copyOf(size)
            val member = Zlib.compress(packetData)
            if (member.size % 4_096 in 1..3) {
                candidate = packetData to member
                break
            }
        }
        val (packetData, member) = assertNotNull(candidate)
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(1)

        assertContentEquals(
            packetData,
            minecraftFrameCodec.decodeFrameBody(encodeVarInt(packetData.size) + member),
        )
    }

    @Test
    fun decodesStandardStoredFixedAndDynamicZlibStreams() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(1)
        val hello = "hello world".encodeToByteArray()
        val dynamic =
            "The quick brown fox jumps over the lazy dog. "
                .repeat(100)
                .encodeToByteArray()
        val samples = listOf(
            hello to "7801010b00f4ff68656c6c6f20776f726c641a0b045d".hexToByteArray(),
            hello to "789ccb48cdc9c95728cf2fca4901001a0b045d".hexToByteArray(),
            dynamic to "789cedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f1dc84f97".hexToByteArray(),
        )

        samples.forEach { (expected, zlib) ->
            assertContentEquals(
                expected,
                minecraftFrameCodec.decodeFrameBody(encodeVarInt(expected.size) + zlib),
            )
        }
    }

    @Test
    fun rejectsMalformedAndInconsistentFrames() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.decodeFrame(byteArrayOf(0))
        }
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.decodeFrame(byteArrayOf(2, 1))
        }

        minecraftFrameCodec.configureCompression(8)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            minecraftFrameCodec.decodeFrameBody(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)),
        )
        assertFailsWith<IOException> {
            minecraftFrameCodec.decodeFrameBody(
                byteArrayOf(11) +
                        "789ccb48cdc9c95728cf2fca4901001a0b045c".hexToByteArray(),
            )
        }
    }

    @Test
    fun rejectsEveryMalformedZlibEnvelopeClass() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(1)

        suspend fun reject(zlib: ByteArray, declaredSize: Int = 1) {
            assertFailsWith<IOException> {
                minecraftFrameCodec.decodeFrameBody(encodeVarInt(declaredSize) + zlib)
            }
        }

        reject(byteArrayOf(0x78, 0x9C.toByte(), 0, 0, 0))
        reject(byteArrayOf(0x79, 0, 0, 0, 0, 0))
        reject(byteArrayOf(0x88.toByte(), 0, 0, 0, 0, 0))
        reject(byteArrayOf(0x78, 0, 0, 0, 0, 0))
        reject(byteArrayOf(0x78, 0x20, 0, 0, 0, 0))

        val hello = "hello world".encodeToByteArray()
        val compressed = Zlib.compress(hello)
        reject(compressed, declaredSize = hello.size - 1)
        reject(compressed, declaredSize = hello.size + 1)

        val wrongChecksum = compressed.copyOf()
        wrongChecksum[wrongChecksum.lastIndex] = (wrongChecksum.last().toInt() xor 1).toByte()
        reject(wrongChecksum, declaredSize = hello.size)
    }

    @Test
    fun rejectsOversizedDeclaredPacketsBeforeInflation() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        minecraftFrameCodec.configureCompression(1)
        val oversizedLength = MinecraftTransportConfiguration.MAXIMUM_UNCOMPRESSED_PACKET_SIZE + 1

        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.decodeFrameBody(
                encodeVarInt(oversizedLength) +
                        "789c030000000001".hexToByteArray(),
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
                maximumFrameSize = MinecraftTransportConfiguration.MAXIMUM_FRAME_SIZE + 1,
            )
        }
        assertEquals(
            15,
            MinecraftTransportConfiguration(
                maximumFrameSize = 16,
                maximumUncompressedPacketSize = 15,
            ).maximumUncompressedPacketSize,
        )

        val minecraftFrameCodec = MinecraftFrameCodec(
            MinecraftTransportConfiguration(
                maximumFrameSize = 4,
                maximumUncompressedPacketSize = 8,
            ),
        )
        minecraftFrameCodec.configureCompression(-1)
        assertNull(minecraftFrameCodec.compressionThreshold)
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.encodeFrame(byteArrayOf())
        }
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.encodeFrame(ByteArray(9))
        }
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.encodeFrame(ByteArray(5) { it.toByte() })
        }

        minecraftFrameCodec.configureCompression(0)
        assertEquals(0, minecraftFrameCodec.compressionThreshold)
        minecraftFrameCodec.configureCompression(null)
        assertNull(minecraftFrameCodec.compressionThreshold)
    }

    @Test
    fun rejectsMalformedVarIntsAndEmptyCompressionPayloads() = runTest {
        val minecraftFrameCodec = MinecraftFrameCodec()
        assertContentEquals(
            byteArrayOf(1),
            minecraftFrameCodec.decodeFrame(byteArrayOf(0x81.toByte(), 0x00, 0x01)),
        )
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.decodeFrame(
                byteArrayOf(
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x00,
                ),
            )
        }

        minecraftFrameCodec.configureCompression(8)
        assertFailsWith<MinecraftTransportException> {
            minecraftFrameCodec.decodeFrameBody(byteArrayOf(0))
        }
        assertContentEquals(
            ByteArray(7),
            minecraftFrameCodec.decodeFrameBody(encodeVarInt(7) + Zlib.compress(ByteArray(7))),
        )

        val strictVarInts = MinecraftFrameCodec(
            MinecraftTransportConfiguration(rejectNonMinimalVarInts = true),
        )
        assertFailsWith<MinecraftTransportException> {
            strictVarInts.decodeFrame(byteArrayOf(0x81.toByte(), 0x00, 0x01))
        }
        val strictCompression = MinecraftFrameCodec(
            MinecraftTransportConfiguration(validateCompressionThreshold = true),
        )
        strictCompression.configureCompression(8)
        assertFailsWith<MinecraftTransportException> {
            strictCompression.decodeFrameBody(encodeVarInt(7) + Zlib.compress(ByteArray(7)))
        }
    }

    @Test
    fun officialDefaultsAcceptNoncanonicalCompressionThresholdBranches() =
        runTest {
            val minecraftFrameCodec = MinecraftFrameCodec()
            minecraftFrameCodec.configureCompression(8)
            val atThreshold = ByteArray(8) { it.toByte() }
            assertContentEquals(
                atThreshold,
                minecraftFrameCodec.decodeFrameBody(byteArrayOf(0) + atThreshold),
            )

            val belowThreshold = byteArrayOf(1, 2, 3)
            assertContentEquals(
                belowThreshold,
                minecraftFrameCodec.decodeFrameBody(
                    encodeVarInt(belowThreshold.size) +
                            Zlib.compress(belowThreshold),
                ),
            )

            val permissiveVarInt = MinecraftFrameCodec()
            assertContentEquals(
                byteArrayOf(1),
                permissiveVarInt.decodeFrame(
                    byteArrayOf(0x81.toByte(), 0x00, 0x01),
                ),
            )
        }
}

private fun encodeVarInt(value: Int): ByteArray {
    val buffer = Buffer()
    buffer.writeVarInt(value)
    return buffer.readByteArray()
}
