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
            val zlib = CompressionRegistry.compress(
                Compression.ZLIB,
                input,
            )
            assertContentEquals(
                input,
                InflaterInputStream(ByteArrayInputStream(zlib))
                    .use { it.readBytes() },
                "zlib sample=$index",
            )

            val gzip = CompressionRegistry.compress(
                Compression.GZIP,
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
            val zlib = ByteArrayOutputStream().also { byteArrayOutputStream ->
                DeflaterOutputStream(byteArrayOutputStream).use { it.write(input) }
            }.toByteArray()
            assertContentEquals(
                input,
                CompressionRegistry.decompress(
                    Compression.ZLIB,
                    zlib,
                ),
                "zlib sample=$index",
            )

            val gzip = ByteArrayOutputStream().also { byteArrayOutputStream ->
                GZIPOutputStream(byteArrayOutputStream).use { it.write(input) }
            }.toByteArray()
            assertContentEquals(
                input,
                CompressionRegistry.decompress(
                    Compression.GZIP,
                    gzip,
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
