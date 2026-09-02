package com.hiczp.minecraft.world.io

import okio.*
import kotlin.random.Random

private const val TEMPORARY_RANDOM_RADIX = 36
private const val TEMPORARY_RANDOM_WIDTH = 13
private const val TEMPORARY_PREFIX = ".tmp-"
private const val TEMPORARY_ATTEMPTS = 256

internal fun FileSystem.readFileBytes(path: Path): ByteArray =
    readFile(path) { bufferedSource, size ->
        bufferedSource.readByteArray(size)
    }

internal fun <T> FileSystem.readFile(
    path: Path,
    block: (BufferedSource, Long) -> T,
): T {
    val byteCount = regularFileByteCount(path)
    return readFileAtKnownSize(path, byteCount, { source(path) }) { bufferedSource ->
        block(bufferedSource, byteCount)
    }
}

internal fun <T> WorldFileAccess.readFile(
    path: Path,
    block: (BufferedSource, Long) -> T,
): T {
    val byteCount = fileSystem.regularFileByteCount(path)
    return readFileAtKnownSize(path, byteCount) { bufferedSource ->
        block(bufferedSource, byteCount)
    }
}

/** Borrows one exact file stream after its size has already been observed by the owning format layer. */
internal fun <T> WorldFileAccess.readFileAtKnownSize(
    path: Path,
    byteCount: Long,
    block: (BufferedSource) -> T,
): T = fileSystem.readFileAtKnownSize(path, byteCount, { openSource(path) }, block)

private fun FileSystem.regularFileByteCount(path: Path): Long {
    val fileMetadata = metadataOrNull(path)
        ?: throw WorldIOException("File does not exist: $path")
    if (!fileMetadata.isRegularFile) {
        throw WorldIOException("Path is not a regular file: $path")
    }
    val byteCount = fileMetadata.size
        ?: throw WorldIOException("Regular file has no size: $path")
    return byteCount
}

private fun <T> FileSystem.readFileAtKnownSize(
    path: Path,
    byteCount: Long,
    openSource: () -> Source,
    block: (BufferedSource) -> T,
): T {
    if (byteCount < 0L) throw WorldIOException("File has a negative size: $path")
    val limitedSource = openSource()
        .limit(byteCount, throwIfSourceIsLonger = true)
        .buffer()
    return useResource(limitedSource, { it.close() }) { bufferedSource ->
        val value = block(bufferedSource)
        bufferedSource.readAll(blackholeSink())
        value
    }
}

internal class CountingSink(
    private val delegate: Sink,
    private val closeDelegate: Boolean = false,
) : Sink {
    internal var bytesWritten = 0L
        private set

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L)
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
    val fileHandle: FileHandle,
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
            delete(backup, mustExist = false)
            atomicMove(target, backup)
            metadataOrNull(backup)?.isRegularFile == true
        }
        createdBackup = true
    }
    retryFileOperation("remove destination $target") {
        delete(target, mustExist = false)
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
            delete(displaced, mustExist = false)
            atomicMove(target, displaced)
            metadataOrNull(displaced)?.isRegularFile == true
        }
    }
    retryFileOperation("remove destination $target") {
        delete(target, mustExist = false)
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

internal fun FileSystem.deleteIfExistsPreserving(
    path: Path,
    failure: Throwable,
) {
    try {
        delete(path, mustExist = false)
    } catch (cleanupFailure: Throwable) {
        val primary = combineFailures(failure, cleanupFailure)
        if (primary !== failure) throw primary
    }
}

/** A filesystem-policy failure reported through Okio's I/O hierarchy. */
class WorldIOException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
