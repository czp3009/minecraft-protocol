package com.hiczp.minecraft.world.io

import okio.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.io.IOException as KotlinxIOException

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
        source.readByteArray(size)
    }
}

internal fun WorldFileAccess.readFileWithinLimit(
    path: Path,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes >= 0)
    return readFile(path, maximumBytes) { source, size ->
        source.readByteArray(size)
    }
}

internal fun <T> FileSystem.readFile(
    path: Path,
    maximumBytes: Int,
    block: (BufferedSource, Long) -> T,
): T = readFile(
    path = path,
    maximumBytes = maximumBytes,
    openSource = { source(path) },
    block = block,
)

internal fun <T> WorldFileAccess.readFile(
    path: Path,
    maximumBytes: Int,
    block: (BufferedSource, Long) -> T,
): T = fileSystem.readFile(
    path = path,
    maximumBytes = maximumBytes,
    openSource = { openSource(path) },
    block = block,
)

private fun <T> FileSystem.readFile(
    path: Path,
    maximumBytes: Int,
    openSource: () -> Source,
    block: (BufferedSource, Long) -> T,
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

    val limitedSource = openSource()
        .limit(size, throwIfSourceIsLonger = true)
        .buffer()
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

// Keep byte-limit policy on the Okio side of world-io. closeDelegate is
// explicit because a file-handle sink is owned here, while other callers may
// lend a sink whose lifetime must outlive the limiter.
internal class LimitedSink(
    private val delegate: Sink,
    maximumBytes: Int,
    private val closeDelegate: Boolean = false,
) : Sink {
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

    override fun timeout(): Timeout = delegate.timeout()

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
                createRandomAccessReadWrite(path),
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
    val sink: Sink,
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

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/*
 * kotlinx-io-okio translates failures raised while crossing an adapter. A
 * world-format operation can still originate a kotlinx-io failure after that
 * crossing, so public world-io entrypoints normalize exactly that I/O type to
 * Okio. Semantic format/NBT errors and coroutine cancellation are untouched.
 */
internal inline fun <T> withOkioIoExceptions(
    message: String,
    block: () -> T,
): T = try {
    block()
} catch (failure: IOException) {
    // The two libraries use the same java.io.IOException on JVM. Avoid
    // wrapping an exception that already satisfies world-io's Okio contract.
    throw failure
} catch (failure: KotlinxIOException) {
    // They are distinct classes on JS and Native. This branch covers only a
    // downstream kotlinx-io failure that did not cross back through an
    // official adapter. If an Okio failure crossed into kotlinx-io earlier,
    // restore the adapter's preserved cause instead of erasing a more precise
    // public subtype such as WorldIOException.
    val okioCause = failure.cause as? IOException
    if (okioCause != null) throw okioCause
    throw IOException(message, failure)
}

/** A filesystem-policy failure reported through Okio's I/O hierarchy. */
class WorldIOException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
