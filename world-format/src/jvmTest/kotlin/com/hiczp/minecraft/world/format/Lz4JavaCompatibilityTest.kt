package com.hiczp.minecraft.world.format

import kotlinx.coroutines.test.runTest
import net.jpountz.lz4.LZ4BlockInputStream
import net.jpountz.lz4.LZ4BlockOutputStream
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
        val firstBlockToken = encoded[8].toInt() and 0xFF
        assertEquals(0x20, firstBlockToken and 0xF0)
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
