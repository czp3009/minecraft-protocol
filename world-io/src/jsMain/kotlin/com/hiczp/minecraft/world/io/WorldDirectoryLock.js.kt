package com.hiczp.minecraft.world.io

import okio.IOException
import okio.Path

internal actual fun acquireWorldDirectoryLock(
    path: Path,
): WorldDirectoryLock {
    val descriptor = try {
        openWorldLock(path, create = true)
    } catch (failure: Throwable) {
        throw failure.toWorldLockIoFailure("open", path)
    }

    try {
        writeWorldLockMarker(descriptor, path)
        syncWorldLock(descriptor, path)
        val key = tryAcquireWorldLock(descriptor, path)
            ?: throw worldAlreadyLockedException(
                absoluteWorldLockPath(path),
            )
        return NodeWorldDirectoryLock(descriptor, path, key)
    } catch (failure: IOException) {
        closeAllPreserving(
            failure,
            { closeWorldLock(descriptor, path) },
        )
        throw failure
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean {
    val descriptor = try {
        openWorldLock(path, create = false)
    } catch (failure: Throwable) {
        return when (failure.nodeErrorCode) {
            NODE_ACCESS_DENIED, NODE_OPERATION_NOT_PERMITTED -> true
            NODE_NO_SUCH_FILE -> false
            else -> throw failure.toWorldLockIoFailure("open", path)
        }
    }

    var acquiredKey: NodeFileKey? = null
    var failure: Throwable? = null
    try {
        acquiredKey = tryAcquireWorldLock(descriptor, path)
        return acquiredKey == null
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeAllPreserving(
            failure,
            {
                if (acquiredKey != null) {
                    unlockWorldLock(descriptor, path)
                }
            },
            { closeWorldLock(descriptor, path) },
            { acquiredKey?.let(IN_PROCESS_LOCK_KEYS::remove) },
        )
    }
}

private class NodeWorldDirectoryLock(
    descriptor: Number,
    private val path: Path,
    private val key: NodeFileKey,
) : WorldDirectoryLock {
    private var descriptor: Number? = descriptor

    override val isValid: Boolean
        get() = descriptor != null

    override fun close() {
        val openDescriptor = descriptor ?: return
        descriptor = null

        try {
            unlockWorldLock(openDescriptor, path)
        } finally {
            try {
                closeWorldLock(openDescriptor, path)
            } finally {
                IN_PROCESS_LOCK_KEYS.remove(key)
            }
        }
    }
}

private fun openWorldLock(path: Path, create: Boolean): Number {
    val flags = constants.O_WRONLY.toInt() or
            if (create) constants.O_CREAT.toInt() else 0
    return openSync(path.toString(), flags)
}

private fun writeWorldLockMarker(descriptor: Number, path: Path) {
    try {
        /*
         * Match the official single FileChannel.write call. Node permits a
         * second writer handle on Windows, but LockFileEx makes the byte range
         * mandatory, so writeSync can fail before tryLock. That host I/O error
         * must remain distinct from tryLock returning "not acquired".
         */
        writeSync(
            fd = descriptor,
            buffer = WORLD_LOCK_MARKER,
            offset = 0.0,
            length = WORLD_LOCK_MARKER.size.toDouble(),
            position = 0.0,
        )
    } catch (failure: Throwable) {
        throw failure.toWorldLockIoFailure("write marker to", path)
    }
}

private fun syncWorldLock(descriptor: Number, path: Path) {
    try {
        fsyncSync(descriptor)
    } catch (failure: Throwable) {
        throw failure.toWorldLockIoFailure("durably sync", path)
    }
}

private fun tryAcquireWorldLock(
    descriptor: Number,
    path: Path,
): NodeFileKey? {
    val key = nodeFileKey(descriptor, path)
    if (!IN_PROCESS_LOCK_KEYS.add(key)) {
        throw worldOverlappingLockException()
    }

    val acquired = try {
        tryLock(descriptor)
    } catch (failure: Throwable) {
        when (failure.nodeErrorCode) {
            NODE_ACCESS_DENIED,
            NODE_LOCK_UNAVAILABLE,
            NODE_WOULD_BLOCK,
                -> false

            else -> {
                IN_PROCESS_LOCK_KEYS.remove(key)
                throw failure.toWorldLockIoFailure("lock", path)
            }
        }
    }
    if (!acquired) {
        IN_PROCESS_LOCK_KEYS.remove(key)
        return null
    }
    return key
}

private fun unlockWorldLock(
    descriptor: Number,
    path: Path,
) {
    try {
        unlock(descriptor)
    } catch (failure: Throwable) {
        throw failure.toWorldLockIoFailure("unlock", path)
    }
}

private fun closeWorldLock(descriptor: Number, path: Path) {
    try {
        closeSync(descriptor)
    } catch (failure: Throwable) {
        throw failure.toWorldLockIoFailure("close lock file", path)
    }
}

private fun nodeFileKey(descriptor: Number, path: Path): NodeFileKey {
    val statistics = try {
        fstatSync(descriptor, NODE_BIGINT_STATISTICS_OPTIONS)
    } catch (failure: Throwable) {
        throw failure.toWorldLockIoFailure("inspect", path)
    }
    return NodeFileKey(
        device = statistics.dev.toString(),
        inode = statistics.ino.toString(),
    )
}

private fun absoluteWorldLockPath(path: Path): String {
    val parent = path.parent
        ?: throw WorldIOException("World lock has no parent: $path")
    return (systemFileSystem.canonicalize(parent) / path.name).toString()
}

private val Throwable.nodeErrorCode: String?
    get() = asDynamic().code as? String

private fun Throwable.toWorldLockIoFailure(
    operation: String,
    path: Path,
): WorldIOException {
    val code = nodeErrorCode
    val codeSuffix = if (code == null) "" else " ($code)"
    return WorldIOException(
        "Could not $operation world lock $path$codeSuffix",
        this,
    )
}

private data class NodeFileKey(
    val device: String,
    val inode: String,
)

private const val NODE_ACCESS_DENIED = "EACCES"
private const val NODE_LOCK_UNAVAILABLE = "EAGAIN"
private const val NODE_NO_SUCH_FILE = "ENOENT"
private const val NODE_OPERATION_NOT_PERMITTED = "EPERM"
private const val NODE_WOULD_BLOCK = "EWOULDBLOCK"
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
private val IN_PROCESS_LOCK_KEYS = mutableSetOf<NodeFileKey>()
private val NODE_BIGINT_STATISTICS_OPTIONS: NodeFileStatisticsOptions =
    emptyNodeFileStatisticsOptions().apply {
        bigint = true
    }

private fun emptyNodeFileStatisticsOptions(): NodeFileStatisticsOptions =
    js("({})")
