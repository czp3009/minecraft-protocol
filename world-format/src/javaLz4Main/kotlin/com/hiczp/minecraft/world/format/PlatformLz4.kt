package com.hiczp.minecraft.world.format

import kotlinx.io.IOException
import net.jpountz.lz4.LZ4Exception
import net.jpountz.lz4.LZ4Factory
import net.jpountz.xxhash.XXHashFactory

private val lz4Factory = LZ4Factory.fastestInstance()
private val xxHashFactory = XXHashFactory.fastestInstance()

internal actual fun platformRawLz4Compress(input: ByteArray): ByteArray {
    val lz4Compressor = lz4Factory.fastCompressor()
    val output = ByteArray(lz4Compressor.maxCompressedLength(input.size))
    val size = try {
        lz4Compressor.compress(
            input,
            0,
            input.size,
            output,
            0,
            output.size,
        )
    } catch (failure: LZ4Exception) {
        throw IOException("Cannot compress raw LZ4 block", failure)
    }
    // lz4-java writes into a maximum-sized destination and returns the actual
    // raw block length; expose only that library-produced prefix.
    return output.copyOf(size)
}

internal actual fun platformRawLz4Decompress(
    input: ByteArray,
    outputLength: Int,
): ByteArray {
    require(outputLength >= 0)
    val output = ByteArray(outputLength)
    val size = try {
        lz4Factory.safeDecompressor().decompress(
            input,
            0,
            input.size,
            output,
            0,
            output.size,
        )
    } catch (failure: LZ4Exception) {
        throw IOException("Invalid raw LZ4 block", failure)
    }
    if (size != outputLength) {
        throw IOException(
            "Raw LZ4 output length $size does not match $outputLength",
        )
    }
    return output
}

internal actual fun platformXxHash32(input: ByteArray, seed: Int): Int =
    xxHashFactory.hash32().hash(input, 0, input.size, seed)
