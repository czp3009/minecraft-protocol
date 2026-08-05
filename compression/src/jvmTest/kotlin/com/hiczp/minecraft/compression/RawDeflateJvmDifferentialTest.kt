package com.hiczp.minecraft.compression

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

private const val MAXIMUM_MUTATION_OUTPUT_BYTES = 32_768

class RawDeflateJvmDifferentialTest {
    @Test
    fun decoderReadsEveryJvmCompressionLevelAcrossBoundaries() {
        val random = Random(0x5A4C4942)
        val samples = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            "repeated text ".repeat(1_000).encodeToByteArray(),
            ByteArray(65_535).also(random::nextBytes),
            ByteArray(65_536).also(random::nextBytes),
            ByteArray(131_089).also(random::nextBytes),
        )

        for (level in listOf(
            Deflater.NO_COMPRESSION,
            Deflater.BEST_SPEED,
            Deflater.DEFAULT_COMPRESSION,
            Deflater.BEST_COMPRESSION,
        )) {
            samples.forEachIndexed { index, input ->
                val encoded = jvmDeflate(input, level)
                assertContentEquals(
                    input,
                    RawDeflate.decode(encoded, input.size),
                    "level=$level sample=$index",
                )
            }
        }
    }

    @Test
    fun jvmInflaterReadsPortableStoredAndFixedEncoding() {
        val random = Random(0x53544F52)
        val samples = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            ByteArray(65_535).also(random::nextBytes),
            ByteArray(65_536).also(random::nextBytes),
            ByteArray(131_089).also(random::nextBytes),
            "fixed Huffman match ".repeat(20_000).encodeToByteArray(),
        )

        samples.forEachIndexed { index, input ->
            val decoded = InflaterInputStream(
                ByteArrayInputStream(RawDeflate.encode(input)),
                Inflater(true),
            ).use { it.readBytes() }
            assertContentEquals(input, decoded, "sample=$index")
        }
    }

    @Test
    fun portableEncoderMatchesJvmAcrossRandomizedLz77Patterns() {
        val random = Random(0x4C5A3737)
        var fixedHuffmanStreams = 0

        repeat(500) { sample ->
            val input = randomizedLz77Input(
                random = random,
                size = random.nextInt(32, 65_537),
            )
            val encoded = RawDeflate.encode(input)
            if ((encoded[0].toInt() ushr 1) and 0b11 == 1) {
                fixedHuffmanStreams++
            }
            val decoded = InflaterInputStream(
                ByteArrayInputStream(encoded),
                Inflater(true),
            ).use { it.readBytes() }

            assertContentEquals(input, decoded, "sample=$sample")
        }
        assertTrue(
            fixedHuffmanStreams >= 400,
            "Expected structured inputs to exercise fixed-Huffman encoding, but only $fixedHuffmanStreams/500 did",
        )
    }

    @Test
    fun deterministicMutationsAgreeWithStrictJvmInflation() {
        val random = Random(0x4D555441)
        val sources = listOf(
            "The quick brown fox jumps over the lazy dog. "
                .repeat(100)
                .encodeToByteArray(),
            ByteArray(4_096).also { random.nextBytes(it) },
            ByteArray(8_192) { (it * 31).toByte() },
        )
        val encodedSamples = buildList {
            for (level in listOf(
                Deflater.NO_COMPRESSION,
                Deflater.BEST_SPEED,
                Deflater.DEFAULT_COMPRESSION,
                Deflater.BEST_COMPRESSION,
            )) {
                sources.forEach { add(jvmDeflate(it, level)) }
            }
        }

        repeat(500) { sample ->
            val original = encodedSamples[random.nextInt(encodedSamples.size)]
            val mutated = original.copyOf()
            val index = random.nextInt(mutated.size)
            mutated[index] =
                (mutated[index].toInt() xor (1 shl random.nextInt(8)))
                    .toByte()
            val expected = runCatching {
                strictJvmInflate(mutated)
            }
            val actual = runCatching {
                RawDeflate.decode(mutated, MAXIMUM_MUTATION_OUTPUT_BYTES)
            }

            if (expected.isSuccess) {
                assertTrue(
                    actual.isSuccess,
                    "Portable decoder rejected JVM-accepted mutation $sample: ${actual.exceptionOrNull()}",
                )
                assertContentEquals(
                    expected.getOrThrow(),
                    actual.getOrThrow(),
                    "Mutation $sample decoded differently",
                )
            } else {
                assertTrue(
                    actual.isFailure,
                    "Portable decoder accepted JVM-rejected mutation $sample",
                )
            }
        }
    }

    private fun jvmDeflate(
        input: ByteArray,
        level: Int,
    ): ByteArray {
        val deflater = Deflater(level, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0 || deflater.finished())
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun randomizedLz77Input(
        random: Random,
        size: Int,
    ): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        while (offset < output.size) {
            val copyMatch =
                offset >= 3 &&
                        output.size - offset >= 3 &&
                        random.nextInt(4) != 0
            if (copyMatch) {
                val distance = random.nextInt(
                    from = 1,
                    until = minOf(offset, 32_768) + 1,
                )
                val length = random.nextInt(
                    from = 3,
                    until = minOf(258, output.size - offset) + 1,
                )
                repeat(length) {
                    output[offset] = output[offset - distance]
                    offset++
                }
            } else {
                val length = random.nextInt(
                    from = 1,
                    until = minOf(64, output.size - offset) + 1,
                )
                repeat(length) {
                    output[offset++] = random.nextInt(256).toByte()
                }
            }
        }
        return output
    }

    private fun strictJvmInflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(input)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    if (output.size() > MAXIMUM_MUTATION_OUTPUT_BYTES - count) {
                        throw DataFormatException("Output limit exceeded")
                    }
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw DataFormatException("Truncated or dictionary stream")
                } else {
                    throw DataFormatException("Inflater made no progress")
                }
            }
            if (inflater.remaining != 0) {
                throw DataFormatException("Trailing raw-DEFLATE bytes")
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
