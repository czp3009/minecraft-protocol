package com.hiczp.minecraft.compression

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.*

class RawDeflateTest {
    @Test
    fun decodesIndependentStoredFixedAndDynamicStreams() {
        val hello = "hello world".encodeToByteArray()
        val dynamic =
            "The quick brown fox jumps over the lazy dog. "
                .repeat(100)
                .encodeToByteArray()
        val samples = listOf(
            hello to STORED_HELLO,
            hello to FIXED_HELLO,
            dynamic to DYNAMIC_TEXT,
        )

        samples.forEach { (expected, encoded) ->
            assertContentEquals(
                expected,
                RawDeflate.decode(encoded, expected.size),
            )
            if (expected.isNotEmpty()) {
                assertFailsWith<RawDeflateException> {
                    RawDeflate.decode(encoded, expected.size - 1)
                }
            }
        }
    }

    @Test
    fun streamingEncoderRoundTripsAcrossFormerStoredBlockBoundaries() {
        val random = Random(0x4445464C)
        val sizes = listOf(
            0,
            1,
            65_534,
            65_535,
            65_536,
            131_069,
            131_070,
            131_071,
        )

        sizes.forEach { size ->
            val input = ByteArray(size)
            random.nextBytes(input)
            val encoded = RawDeflate.encode(input)

            assertContentEquals(input, RawDeflate.decode(encoded, size))
        }
    }

    @Test
    fun fixedHuffmanEncoderCompressesLongMatches() {
        val input =
            "portable Minecraft DEFLATE ".repeat(10_000)
                .encodeToByteArray()
        val encoded = RawDeflate.encode(input)

        assertEquals(
            expected = 1,
            actual = (encoded[0].toInt() ushr 1) and 0b11,
        )
        assertTrue(encoded.size < input.size / 20)
        assertContentEquals(input, RawDeflate.decode(encoded, input.size))
    }

    @Test
    fun adaptiveEncoderUsesStoredBlocksForHighEntropyInput() {
        val input = ByteArray(32_768)
        Random(0x53544F52).nextBytes(input)

        val encoded = RawDeflate.encode(input)

        assertEquals(
            expected = 0,
            actual = (encoded[0].toInt() ushr 1) and 0b11,
        )
        assertContentEquals(input, RawDeflate.decode(encoded, input.size))
    }

    @Test
    fun rejectsEveryTruncatedPrefixAndTrailingByte() {
        for (encoded in listOf(
            STORED_HELLO,
            FIXED_HELLO,
            DYNAMIC_TEXT,
        )) {
            for (endIndex in encoded.indices) {
                assertFailsWith<RawDeflateException>(
                    "Accepted prefix $endIndex/${encoded.size}",
                ) {
                    RawDeflate.decode(
                        encoded.copyOf(endIndex),
                        10_000,
                    )
                }
            }
            assertFailsWith<RawDeflateException> {
                RawDeflate.decode(encoded + byteArrayOf(0), 10_000)
            }
        }
    }

    @Test
    fun rejectsReservedBlocksInvalidStoredLengthsAndInvalidLimits() {
        assertFailsWith<IllegalArgumentException> {
            RawDeflate.decode(byteArrayOf(), -1)
        }
        assertFailsWith<RawDeflateException> {
            RawDeflate.decode(byteArrayOf(0x07), 0)
        }
        assertFailsWith<RawDeflateException> {
            RawDeflate.decode(
                STORED_HELLO.copyOf().also {
                    it[3] = (it[3].toInt() xor 1).toByte()
                },
                11,
            )
        }
        for (reservedLiteralLengthCount in listOf(
            byteArrayOf(0xF5.toByte()),
            byteArrayOf(0xFD.toByte()),
        )) {
            assertFailsWith<RawDeflateException> {
                RawDeflate.decode(reservedLiteralLengthCount, 0)
            }
        }
    }

    @Test
    fun streamAdaptersProduceAndConsumeIncrementallyWithoutClosingOwners() {
        val input = ByteArray(128 * 1_024) { index ->
            (index * 31 + index / 17).toByte()
        }
        val encodedOwner = CloseTrackingRawSink()
        val encodedSink = encodedOwner.buffered()
        val compressor = RawDeflate.compressingSink(encodedSink).buffered()

        val firstInput = Buffer().apply {
            write(input, endIndex = input.size / 2)
        }
        compressor.write(firstInput, firstInput.size)
        compressor.flush()
        assertTrue(encodedOwner.bytes.size > 0)
        compressor.close()
        assertFalse(encodedOwner.closed)
        encodedSink.flush()

        val encodedBytes = encodedOwner.bytes.readByteArray()
        val encodedSource = Buffer().apply { write(encodedBytes) }
        val decompressor = RawDeflate
            .decompressingSource(encodedSource, input.size)
            .buffered()
        val prefix = decompressor.readByteArray(32)

        assertContentEquals(input.copyOf(32), prefix)
        assertFalse(encodedSource.exhausted())
        decompressor.close()
        assertTrue(encodedSource.size > 0)
    }

    private class CloseTrackingRawSink : RawSink {
        val bytes = Buffer()
        var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            bytes.write(source, byteCount)
        }

        override fun flush() = Unit

        override fun close() {
            closed = true
        }
    }

    companion object {
        private val STORED_HELLO = hexBytes(
            "010b00f4ff68656c6c6f20776f726c64",
        )
        private val FIXED_HELLO = hexBytes(
            "cb48cdc9c95728cf2fca490100",
        )
        private val DYNAMIC_TEXT = hexBytes(
            "edca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f",
        )

        private fun hexBytes(value: String): ByteArray {
            require(value.length % 2 == 0)
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2)
                    .toInt(16)
                    .toByte()
            }
        }
    }
}
