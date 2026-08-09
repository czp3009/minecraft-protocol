@file:OptIn(ExperimentalUnsignedTypes::class)

package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.world.format.internal.jsxxhash.xxHash32
import com.hiczp.minecraft.world.format.internal.lz4lite.compressBlock
import com.hiczp.minecraft.world.format.internal.lz4lite.decompressBlock
import kotlinx.io.IOException
import org.khronos.webgl.toUByteArray
import org.khronos.webgl.toUint8Array
import kotlin.coroutines.cancellation.CancellationException

// npm's lz4-lite and js-xxhash ABIs use Uint8Array. These conversions only
// bridge Kotlin arrays; the npm packages own raw LZ4 and XXHash32.
internal actual fun platformRawLz4Compress(input: ByteArray): ByteArray =
    mapWebCompressionFailure("Cannot compress raw LZ4 block") {
        compressBlock(input.asUByteArray().toUint8Array())
            .toUByteArray()
            .asByteArray()
    }

internal actual fun platformRawLz4Decompress(
    input: ByteArray,
    outputLength: Int,
): ByteArray = mapWebCompressionFailure("Invalid raw LZ4 block") {
    require(outputLength >= 0)
    val output = decompressBlock(
        input.asUByteArray().toUint8Array(),
        outputLength,
    ).toUByteArray().asByteArray()
    if (output.size != outputLength) {
        throw IOException(
            "Raw LZ4 output length ${output.size} does not match $outputLength",
        )
    }
    output
}

internal actual fun platformXxHash32(input: ByteArray, seed: Int): Int =
    mapWebCompressionFailure("Cannot hash LZ4 block") {
        // JavaScript returns the unsigned 32-bit value as Number; Long then Int
        // preserves its low 32 bits for the common LZ4Block container.
        xxHash32(input.asUByteArray().toUint8Array(), seed).toLong().toInt()
    }

// External JavaScript can throw values outside Kotlin's Exception hierarchy,
// so this platform boundary cannot use a narrower catch. Cancellation is flow
// control and must propagate unchanged; all other JS failures become the same
// kotlinx-io type exposed by the JVM, Android, and Native backends.
private inline fun <T> mapWebCompressionFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: IOException) {
    throw failure
} catch (failure: Throwable) {
    throw IOException(message, failure)
}
