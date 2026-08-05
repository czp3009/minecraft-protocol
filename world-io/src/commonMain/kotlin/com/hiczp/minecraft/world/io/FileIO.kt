package com.hiczp.minecraft.world.io

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlin.random.Random

internal fun FileSystem.readFileWithinLimit(
    path: Path,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes >= 0)
    val metadata = metadataOrNull(path)
        ?: throw WorldIOException("File does not exist: $path")
    if (!metadata.isRegularFile) {
        throw WorldIOException("Path is not a regular file: $path")
    }
    if (metadata.size !in 0L..maximumBytes.toLong()) {
        throw WorldIOException(
            "File $path size ${metadata.size} exceeds limit $maximumBytes",
        )
    }

    val source = source(path).buffered()
    try {
        val bytes = source.readByteArray(metadata.size.toInt())
        if (!source.exhausted()) {
            throw WorldIOException(
                "File $path grew beyond limit $maximumBytes while reading",
            )
        }
        return bytes
    } finally {
        source.close()
    }
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
        ".${path.name}.minecraft-protocol-${Random.nextLong().toULong()}.tmp",
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
