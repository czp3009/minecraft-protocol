package com.hiczp.minecraft.world.format

import kotlinx.coroutines.test.runTest
import net.jpountz.lz4.LZ4BlockInputStream
import net.jpountz.lz4.LZ4BlockOutputStream
import net.jpountz.xxhash.XXHashFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Lz4JavaCompatibilityTest {
    @Test
    fun kotlinEncoderIsReadableByVanillaLz4Java() = runTest {
        val input = ByteArray(200_000) { (it * 17).toByte() }

        val encoded = RegionCompressionCodecs.compress(
            RegionCompression.LZ4,
            input,
        )
        var encodedOffset = 0
        var inputOffset = 0
        while (inputOffset < input.size) {
            val length = minOf(65_536, input.size - inputOffset)
            val expected = XXHashFactory.fastestInstance()
                .hash32()
                .hash(input, inputOffset, length, 0x9747b28c.toInt()) and
                    0x0fff_ffff
            val actual =
                (encoded[encodedOffset + 17].toInt() and 0xff) or
                        ((encoded[encodedOffset + 18].toInt() and 0xff) shl 8) or
                        ((encoded[encodedOffset + 19].toInt() and 0xff) shl 16) or
                        ((encoded[encodedOffset + 20].toInt() and 0xff) shl 24)
            assertContentEquals(
                input.copyOfRange(inputOffset, inputOffset + length),
                encoded.copyOfRange(
                    encodedOffset + 21,
                    encodedOffset + 21 + length,
                ),
            )
            assertEquals(expected, actual)
            inputOffset += length
            encodedOffset += 21 + length
        }
        val decoded = LZ4BlockInputStream.newBuilder().build(ByteArrayInputStream(encoded)).use { it.readBytes() }

        assertContentEquals(input, decoded)
    }

    @Test
    fun kotlinDecoderReadsCompressedLz4JavaBlocks() = runTest {
        val input = ByteArray(200_000) { (it / 1_000).toByte() }
        val output = ByteArrayOutputStream()
        LZ4BlockOutputStream(output).use { it.write(input) }

        val decoded = RegionCompressionCodecs.decompress(
            RegionCompression.LZ4,
            output.toByteArray(),
            input.size,
        )

        assertContentEquals(input, decoded)
    }
}
