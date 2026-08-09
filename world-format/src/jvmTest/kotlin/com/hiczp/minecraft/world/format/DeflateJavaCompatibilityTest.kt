package com.hiczp.minecraft.world.format

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DeflateJavaCompatibilityTest {
    @Test
    fun portableZlibAndGzipEncodersAreReadableByJava() = runTest {
        samples().forEachIndexed { index, input ->
            val zlib = RegionCompressionCodecs.compress(
                RegionCompression.ZLIB,
                input,
            )
            assertContentEquals(
                input,
                InflaterInputStream(ByteArrayInputStream(zlib))
                    .use { it.readBytes() },
                "zlib sample=$index",
            )

            val gzip = RegionCompressionCodecs.compress(
                RegionCompression.GZIP,
                input,
            )
            assertContentEquals(
                input,
                GZIPInputStream(ByteArrayInputStream(gzip))
                    .use { it.readBytes() },
                "gzip sample=$index",
            )
        }
    }

    @Test
    fun javaZlibAndGzipEncodersAreReadableByPortableDecoders() = runTest {
        samples().forEachIndexed { index, input ->
            val zlib = ByteArrayOutputStream().also { output ->
                DeflaterOutputStream(output).use { it.write(input) }
            }.toByteArray()
            assertContentEquals(
                input,
                RegionCompressionCodecs.decompress(
                    RegionCompression.ZLIB,
                    zlib,
                    input.size,
                ),
                "zlib sample=$index",
            )

            val gzip = ByteArrayOutputStream().also { output ->
                GZIPOutputStream(output).use { it.write(input) }
            }.toByteArray()
            assertContentEquals(
                input,
                RegionCompressionCodecs.decompress(
                    RegionCompression.GZIP,
                    gzip,
                    input.size,
                ),
                "gzip sample=$index",
            )
        }
    }

    private fun samples(): List<ByteArray> {
        val random = Random(0x574F524C)
        return listOf(
            byteArrayOf(),
            byteArrayOf(0),
            ByteArray(65_535).also(random::nextBytes),
            ByteArray(65_536).also(random::nextBytes),
            ByteArray(131_089).also(random::nextBytes),
            "standard world compression ".repeat(20_000)
                .encodeToByteArray(),
        )
    }
}
