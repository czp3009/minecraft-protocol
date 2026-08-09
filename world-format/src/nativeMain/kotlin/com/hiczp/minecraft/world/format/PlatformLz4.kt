@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.minecraft.world.format

import com.appmattus.crypto.Algorithm
import com.hiczp.minecraft.world.format.internal.lz4.LZ4_compressBound
import com.hiczp.minecraft.world.format.internal.lz4.LZ4_compress_default
import com.hiczp.minecraft.world.format.internal.lz4.LZ4_decompress_safe
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException

internal actual fun platformRawLz4Compress(input: ByteArray): ByteArray {
    val capacity = LZ4_compressBound(input.size)
    if (capacity <= 0) {
        throw IOException("liblz4 rejected input length ${input.size}")
    }
    // Kotlin/Native cannot take addressOf(0) on an empty array, while liblz4
    // still requires a non-null pointer for the zero-length C call. The dummy
    // byte is never included because srcSize remains input.size.
    val source = if (input.isEmpty()) ByteArray(1) else input
    val output = ByteArray(capacity)
    val size = source.usePinned { pinnedSource ->
        output.usePinned { pinnedOutput ->
            LZ4_compress_default(
                pinnedSource.addressOf(0),
                pinnedOutput.addressOf(0),
                input.size,
                output.size,
            )
        }
    }
    if (size <= 0) {
        throw IOException("liblz4 could not compress raw block")
    }
    return output.copyOf(size)
}

internal actual fun platformRawLz4Decompress(
    input: ByteArray,
    outputLength: Int,
): ByteArray {
    require(outputLength >= 0)
    // C pointers must remain addressable for empty blocks; the explicit C
    // lengths preserve the logical empty input/output despite dummy storage.
    val source = if (input.isEmpty()) ByteArray(1) else input
    val output = ByteArray(maxOf(outputLength, 1))
    val size = source.usePinned { pinnedSource ->
        output.usePinned { pinnedOutput ->
            LZ4_decompress_safe(
                pinnedSource.addressOf(0),
                pinnedOutput.addressOf(0),
                input.size,
                outputLength,
            )
        }
    }
    if (size != outputLength) {
        throw IOException(
            "Raw LZ4 output length $size does not match $outputLength",
        )
    }
    return output.copyOf(outputLength)
}

// Appmattus exposes digest bytes, while LZ4Block stores the numeric XXHash32.
// Buffer's big-endian read converts the library result without
// reimplementing any hash round.
internal actual fun platformXxHash32(input: ByteArray, seed: Int): Int =
    Buffer().apply {
        write(Algorithm.XXHash32(seed).hash(input))
    }.readInt()
