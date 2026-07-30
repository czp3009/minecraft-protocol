package com.hiczp.minecraft.protocol.transport

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ZlibJvmCompatibilityTest {
    @Test
    fun portableEncoderProducesStandardZlibStreams() = runTest {
        val random = Random(0x5A4C4942)
        val samples = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            ByteArray(65_535).also(random::nextBytes),
            ByteArray(65_536).also(random::nextBytes),
            ByteArray(131_089).also(random::nextBytes),
            "standard zlib match ".repeat(20_000).encodeToByteArray(),
        )
        samples.forEachIndexed { index, input ->
            val encoded = Zlib.compress(input)
            val decoded = InflaterInputStream(
                ByteArrayInputStream(encoded),
            ).use { it.readBytes() }

            assertContentEquals(input, decoded, "sample=$index")
        }
    }
}
