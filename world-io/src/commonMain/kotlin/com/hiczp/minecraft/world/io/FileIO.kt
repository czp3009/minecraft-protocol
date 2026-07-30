package com.hiczp.minecraft.world.io

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlin.random.Random

internal fun FileSystem.readByteArray(
    path: Path,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes >= 0)
    val metadata = metadataOrNull(path)
        ?: throw WorldIOException("File does not exist: $path")
    if (!metadata.isRegularFile) {
        throw WorldIOException("Path is not a regular file: $path")
    }
    if (metadata.size < 0 || metadata.size > maximumBytes) {
        throw WorldIOException(
            "File $path size ${metadata.size} exceeds limit $maximumBytes",
        )
    }

    val source = source(path).buffered()
    val output = FileByteAccumulator(
        initialCapacity = metadata.size.toInt().coerceAtLeast(1),
    )
    val chunk = ByteArray(8_192)
    try {
        while (true) {
            val read = source.readAtMostTo(chunk)
            if (read < 0) break
            if (read == 0) continue
            if (output.size > maximumBytes - read) {
                throw WorldIOException(
                    "File $path grew beyond limit $maximumBytes while reading",
                )
            }
            output.append(chunk, read)
        }
    } finally {
        source.close()
    }
    return output.toByteArray()
}

internal fun FileSystem.writeByteArrayAtomically(
    path: Path,
    bytes: ByteArray,
) {
    val parent = path.parent
        ?: throw WorldIOException("File has no parent directory: $path")
    createDirectories(parent)
    val temporary = Path(
        parent,
        ".${path.name}.minecraft-protocol-" +
                "${Random.nextLong().toULong()}.tmp",
    )
    try {
        val sink = sink(temporary).buffered()
        try {
            sink.write(bytes)
            sink.flush()
        } finally {
            sink.close()
        }
        replaceAtomically(temporary, path)
    } catch (failure: Throwable) {
        runCatching {
            if (exists(temporary)) delete(temporary)
        }
        throw WorldIOException("Cannot atomically write $path", failure)
    }
}

/**
 * Concurrent replacements can briefly fail while another atomic rename still
 * owns the destination on some filesystems. The source remains present in that
 * case, so retrying the same atomic operation preserves the contract.
 */
private fun FileSystem.replaceAtomically(source: Path, destination: Path) {
    var lastFailure: Throwable? = null
    repeat(256) { attempt ->
        try {
            atomicMove(source, destination)
            return
        } catch (failure: Throwable) {
            lastFailure = failure
            val destinationMetadata = metadataOrNull(destination)
            if (
                !exists(source) ||
                destinationMetadata?.isDirectory == true ||
                attempt == 255
            ) {
                throw failure
            }
        }
    }
    throw checkNotNull(lastFailure)
}

internal fun FileSystem.deleteIfExists(path: Path) {
    if (exists(path)) delete(path)
}

class WorldIOException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private class FileByteAccumulator(initialCapacity: Int) {
    private var bytes = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    fun append(source: ByteArray, length: Int) {
        ensureCapacity(size + length)
        source.copyInto(bytes, destinationOffset = size, endIndex = length)
        size += length
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return
        var capacity = bytes.size.coerceAtLeast(1)
        while (capacity < required) {
            capacity = (capacity * 2).coerceAtLeast(required)
        }
        bytes = bytes.copyOf(capacity)
    }
}
