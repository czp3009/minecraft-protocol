package com.hiczp.minecraft.world.io

import kotlinx.io.*
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlin.random.Random

private const val ATOMIC_TEMPORARY_RANDOM_RADIX = 36
private const val ATOMIC_TEMPORARY_RANDOM_WIDTH = 13
private const val ATOMIC_TEMPORARY_PREFIX = ".tmp-"

internal fun FileSystem.readFileWithinLimit(
    path: Path,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes >= 0)
    return readFile(path, maximumBytes) { source, size ->
        source.readByteArray(size.toInt())
    }
}

internal fun <T> FileSystem.readFile(
    path: Path,
    maximumBytes: Int,
    block: (Source, Long) -> T,
): T {
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

    val fileSource = source(path).buffered()
    val limitedSource = LimitedRawSource(fileSource, maximumBytes).buffered()
    var failure: Throwable? = null
    try {
        val value = block(limitedSource, metadata.size)
        if (!limitedSource.exhausted()) {
            throw WorldIOException("File $path was not fully consumed")
        }
        return value
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeAllPreserving(failure, limitedSource::close)
    }
}

internal fun FileSystem.writeByteArrayAtomically(
    path: Path,
    bytes: ByteArray,
): Unit = writeAtomically(path, bytes.size) { sink ->
    sink.write(bytes)
}

internal fun <T> FileSystem.writeAtomically(
    path: Path,
    maximumBytes: Int,
    block: (Sink) -> T,
): T {
    require(maximumBytes >= 0)
    val parent = path.parent
        ?: throw WorldIOException("File has no parent directory: $path")
    createDirectories(parent)
    val temporary = Path(
        parent,
        atomicTemporaryFileName(Random.nextLong().toULong()),
    )
    try {
        val fileSink = sink(temporary).buffered()
        val limitedSink = LimitedRawSink(fileSink, maximumBytes).buffered()
        var failure: Throwable? = null
        val value = try {
            block(limitedSink).also {
                limitedSink.flush()
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(
                failure,
                limitedSink::close,
                fileSink::close,
            )
        }
        replaceAtomically(temporary, path)
        return value
    } catch (failure: Throwable) {
        try {
            if (exists(temporary)) delete(temporary)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

internal fun closeAllPreserving(
    failure: Throwable?,
    vararg closes: () -> Unit,
) {
    var primary = failure
    closes.forEach { close ->
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (primary == null) {
                primary = closeFailure
            } else {
                primary.addSuppressed(closeFailure)
            }
        }
    }
    if (failure == null) throw primary ?: return
}

private class LimitedRawSource(
    private val delegate: Source,
    private val maximumBytes: Int,
) : RawSource {
    private var bytesRead = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0
        val remaining = maximumBytes.toLong() - bytesRead
        val read = delegate.readAtMostTo(sink, minOf(byteCount, remaining + 1))
        if (read < 0) return -1
        bytesRead += read
        if (bytesRead > maximumBytes) {
            throw WorldIOException(
                "File grew beyond limit $maximumBytes while reading",
            )
        }
        return read
    }

    override fun close() {
        delegate.close()
    }
}

internal class LimitedRawSink(
    private val delegate: Sink,
    private val maximumBytes: Int,
) : RawSink {
    private var bytesWritten = 0L

    override fun write(source: Buffer, byteCount: Long) {
        if (
            byteCount < 0 ||
            byteCount > maximumBytes.toLong() - bytesWritten
        ) {
            throw WorldIOException(
                "Output exceeds configured limit $maximumBytes",
            )
        }
        delegate.write(source, byteCount)
        bytesWritten += byteCount
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() = Unit
}

/** Returns a short extension-free sibling name shared by every atomic write. */
internal fun atomicTemporaryFileName(
    random: ULong,
): String {
    val randomToken = random
        .toString(ATOMIC_TEMPORARY_RANDOM_RADIX)
        .padStart(ATOMIC_TEMPORARY_RANDOM_WIDTH, '0')
    return "${ATOMIC_TEMPORARY_PREFIX}${randomToken}"
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

/** A filesystem-policy failure reported through the standard I/O hierarchy. */
class WorldIOException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
