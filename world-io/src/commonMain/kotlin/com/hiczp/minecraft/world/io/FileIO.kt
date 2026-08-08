package com.hiczp.minecraft.world.io

import kotlinx.io.*
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import okio.Sink as OkioSink

private const val TEMPORARY_RANDOM_RADIX = 36
private const val TEMPORARY_RANDOM_WIDTH = 13
private const val TEMPORARY_PREFIX = ".tmp-"
private const val TEMPORARY_ATTEMPTS = 256

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
    val size = metadata.size
        ?: throw WorldIOException("Regular file has no size: $path")
    if (size !in 0L..maximumBytes.toLong()) {
        throw WorldIOException(
            "File $path size $size exceeds limit $maximumBytes",
        )
    }

    val limitedSource = LimitedRawSource(
        source(path).asKotlinxIoRawSource(),
        maximumBytes,
    ).buffered()
    return limitedSource.use { source ->
        val value = block(source, size)
        if (!source.exhausted()) {
            throw WorldIOException("File $path was not fully consumed")
        }
        value
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
            val current = primary
            if (current == null) {
                primary = closeFailure
            } else {
                current.addSuppressed(closeFailure)
            }
        }
    }
    if (failure == null) throw primary ?: return
}

private class LimitedRawSource(
    private val delegate: RawSource,
    maximumBytes: Int,
) : RawSource {
    private val maximumBytes = maximumBytes.toLong()
    private var bytesRead = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0
        val remaining = maximumBytes - bytesRead
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
    private val delegate: RawSink,
    maximumBytes: Int,
    private val closeDelegate: Boolean = false,
) : RawSink {
    private val maximumBytes = maximumBytes.toLong()
    internal var bytesWritten = 0L
        private set

    init {
        require(maximumBytes >= 0)
    }

    override fun write(source: Buffer, byteCount: Long) {
        if (byteCount < 0 || byteCount > maximumBytes - bytesWritten) {
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

    override fun close() {
        if (closeDelegate) delegate.close()
    }
}

internal fun temporaryFileName(
    random: ULong,
    prefix: String = TEMPORARY_PREFIX,
    suffix: String = "",
): String {
    val randomToken = random
        .toString(TEMPORARY_RANDOM_RADIX)
        .padStart(TEMPORARY_RANDOM_WIDTH, '0')
    return "$prefix$randomToken$suffix"
}

internal fun FileSystem.openUniqueTemporarySink(
    directory: Path,
    prefix: String = TEMPORARY_PREFIX,
    suffix: String = "",
): TemporaryFileSink {
    createDirectories(directory)
    var lastCollision: Throwable? = null
    repeat(TEMPORARY_ATTEMPTS) {
        val path = directory / temporaryFileName(
            random = Random.nextLong().toULong(),
            prefix = prefix,
            suffix = suffix,
        )
        try {
            return TemporaryFileSink(path, sink(path, mustCreate = true))
        } catch (failure: IOException) {
            if (!exists(path)) throw failure
            lastCollision = failure
        }
    }
    throw WorldIOException(
        "Could not create a unique temporary file in $directory",
        lastCollision,
    )
}

internal fun FileSystem.openUniqueTemporaryHandle(
    directory: Path,
    prefix: String = TEMPORARY_PREFIX,
    suffix: String = "",
): TemporaryFileHandle {
    createDirectories(directory)
    var lastCollision: Throwable? = null
    repeat(TEMPORARY_ATTEMPTS) {
        val path = directory / temporaryFileName(
            random = Random.nextLong().toULong(),
            prefix = prefix,
            suffix = suffix,
        )
        try {
            return TemporaryFileHandle(
                path,
                openReadWrite(path, mustCreate = true),
            )
        } catch (failure: IOException) {
            if (!exists(path)) throw failure
            lastCollision = failure
        }
    }
    throw WorldIOException(
        "Could not create a unique temporary file in $directory",
        lastCollision,
    )
}

internal data class TemporaryFileSink(
    val path: Path,
    val sink: OkioSink,
)

internal data class TemporaryFileHandle(
    val path: Path,
    val handle: FileHandle,
)

internal fun FileSystem.replaceWithBackup(
    temporary: Path,
    target: Path,
    backup: Path,
) {
    val targetExists = exists(target)
    var createdBackup = false
    if (targetExists) {
        retryFileOperation("back up $target") {
            deleteIfExists(backup)
            atomicMove(target, backup)
            metadataOrNull(backup)?.isRegularFile == true
        }
        createdBackup = true
    }
    retryFileOperation("remove destination $target") {
        deleteIfExists(target)
        !exists(target)
    }
    try {
        retryFileOperation("move replacement to $target") {
            atomicMove(temporary, target)
            metadataOrNull(target)?.isRegularFile == true
        }
    } catch (failure: IOException) {
        if (createdBackup) {
            try {
                retryFileOperation("restore $target from $backup") {
                    atomicMove(backup, target)
                    metadataOrNull(target)?.isRegularFile == true
                }
            } catch (rollbackFailure: IOException) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
}

internal fun FileSystem.replaceWithoutRollback(
    replacement: Path,
    target: Path,
    displaced: Path,
) {
    if (exists(target)) {
        retryFileOperation("move displaced $target to $displaced") {
            deleteIfExists(displaced)
            atomicMove(target, displaced)
            metadataOrNull(displaced)?.isRegularFile == true
        }
    }
    retryFileOperation("remove destination $target") {
        deleteIfExists(target)
        !exists(target)
    }
    retryFileOperation("move replacement to $target") {
        atomicMove(replacement, target)
        metadataOrNull(target)?.isRegularFile == true
    }
}

private fun retryFileOperation(
    description: String,
    block: () -> Boolean,
) {
    var lastFailure: Throwable? = null
    repeat(10) {
        try {
            if (block()) return
            lastFailure = WorldIOException("Could not $description")
        } catch (failure: IOException) {
            lastFailure = failure
        }
    }
    throw WorldIOException("Could not $description after 10 attempts", lastFailure)
}

internal fun FileSystem.deleteIfExists(path: Path) {
    delete(path, mustExist = false)
}

internal fun FileSystem.deleteIfExistsPreserving(
    path: Path,
    failure: Throwable,
) {
    try {
        deleteIfExists(path)
    } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
    }
}

/** A filesystem-policy failure reported through Okio's I/O hierarchy. */
class WorldIOException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
