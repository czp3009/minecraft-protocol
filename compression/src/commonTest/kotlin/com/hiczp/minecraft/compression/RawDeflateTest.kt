package com.hiczp.minecraft.compression

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
    fun storedEncoderRoundTripsEveryBlockBoundary() {
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
            val expectedBlocks = if (size == 0) {
                1
            } else {
                (size + 65_534) / 65_535
            }
            assertEquals(size + expectedBlocks * 5, encoded.size)
            assertEquals(1, encoded.lastBlockHeader(input.size))
        }
    }

    @Test
    fun fixedHuffmanEncoderCompressesLongMatches() {
        val input =
            "portable Minecraft DEFLATE ".repeat(10_000)
                .encodeToByteArray()
        val encoded = RawDeflate.encode(input)

        assertEquals(0b011, encoded[0].toInt() and 0b111)
        assertTrue(encoded.size < input.size / 20)
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
                RawDeflate.encode(byteArrayOf(1)).also {
                    it[3] = (it[3].toInt() xor 1).toByte()
                },
                1,
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

    private fun ByteArray.lastBlockHeader(inputSize: Int): Int {
        var offset = 0
        var remaining = inputSize
        while (true) {
            val header = this[offset].toInt() and 0xFF
            val length = minOf(65_535, remaining)
            if (header and 1 != 0) return header
            offset += 5 + length
            remaining -= length
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
            "edca470180301045412b5f016a628092d0d910084d3d88e0f8ce33ae" +
                    "f35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891" +
                    "c96432994c2693c96432994c2693ffc82f",
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
