package com.hiczp.minecraft.world.io

import okio.Path

internal actual fun acquireWorldDirectoryLock(
    path: Path,
): WorldDirectoryLock {
    val canonicalPath = canonicalLockPath(path)
    if (!IN_PROCESS_LOCK_PATHS.add(canonicalPath)) {
        throw worldAlreadyLockedException(canonicalPath)
    }

    var descriptor: Number? = null
    var lockAcquired = false
    try {
        val openedDescriptor = try {
            openWorldLock(path, create = true)
        } catch (caught: Throwable) {
            throw caught.toWorldLockException("open", path)
        }
        descriptor = openedDescriptor
        writeWorldLockMarker(openedDescriptor, path)
        syncWorldLock(openedDescriptor, path)
        if (!tryAcquireWorldLock(openedDescriptor, path)) {
            throw worldAlreadyLockedException(canonicalPath)
        }
        lockAcquired = true
        return NodeWorldDirectoryLock(
            descriptor = openedDescriptor,
            path = path,
            canonicalPath = canonicalPath,
        )
    } catch (failure: Throwable) {
        if (lockAcquired) {
            try {
                unlock(checkNotNull(descriptor))
            } catch (caught: Throwable) {
                failure.addSuppressed(
                    caught.toWorldLockException("unlock", path),
                )
            }
        }
        descriptor?.let { openedDescriptor ->
            try {
                closeSync(openedDescriptor)
            } catch (caught: Throwable) {
                failure.addSuppressed(
                    caught.toWorldLockException("close lock file", path),
                )
            }
        }
        IN_PROCESS_LOCK_PATHS.remove(canonicalPath)
        throw failure
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean {
    val descriptor = try {
        openWorldLock(path, create = false)
    } catch (failure: Throwable) {
        return when (failure.nodeErrorCode) {
            NODE_NO_SUCH_FILE -> false
            NODE_ACCESS_DENIED, NODE_OPERATION_NOT_PERMITTED -> true
            else -> throw failure.toWorldLockException("open", path)
        }
    }

    var failure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var lockAcquired = false
    try {
        if (canonicalLockPath(path) in IN_PROCESS_LOCK_PATHS) return true
        lockAcquired = tryAcquireWorldLock(descriptor, path)
        if (!lockAcquired) return true
        try {
            unlock(descriptor)
            lockAcquired = false
        } catch (caught: Throwable) {
            throw caught.toWorldLockException("unlock", path)
        }
        return false
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        if (lockAcquired) {
            try {
                unlock(descriptor)
            } catch (caught: Throwable) {
                val unlockFailure = caught.toWorldLockException(
                    "unlock",
                    path,
                )
                val current = failure
                if (current == null) cleanupFailure = unlockFailure
                else current.addSuppressed(unlockFailure)
            }
        }
        try {
            closeSync(descriptor)
        } catch (caught: Throwable) {
            val closeFailure = caught.toWorldLockException(
                "close lock file",
                path,
            )
            val current = failure ?: cleanupFailure
            if (current == null) cleanupFailure = closeFailure
            else current.addSuppressed(closeFailure)
        }
        if (failure == null) cleanupFailure?.let { throw it }
    }
}

private class NodeWorldDirectoryLock(
    descriptor: Number,
    private val path: Path,
    private val canonicalPath: String,
) : WorldDirectoryLock {
    private var descriptor: Number? = descriptor

    override val isValid: Boolean
        get() = descriptor != null

    override fun close() {
        val openDescriptor = descriptor ?: return
        descriptor = null

        var failure: Throwable? = null
        try {
            unlock(openDescriptor)
        } catch (caught: Throwable) {
            failure = caught.toWorldLockException("unlock", path)
        }
        try {
            closeSync(openDescriptor)
        } catch (caught: Throwable) {
            val closeFailure = caught.toWorldLockException(
                "close lock file",
                path,
            )
            val current = failure
            if (current == null) failure = closeFailure
            else current.addSuppressed(closeFailure)
        }
        IN_PROCESS_LOCK_PATHS.remove(canonicalPath)
        failure?.let { throw it }
    }
}

private fun openWorldLock(path: Path, create: Boolean): Number {
    val flags = constants.O_WRONLY.toInt() or
            if (create) constants.O_CREAT.toInt() else 0
    return openSync(path.toString(), flags)
}

private fun writeWorldLockMarker(descriptor: Number, path: Path) {
    var written = 0
    while (written < WORLD_LOCK_MARKER.size) {
        val result = try {
            writeSync(
                fd = descriptor,
                buffer = WORLD_LOCK_MARKER,
                offset = written.toDouble(),
                length = (WORLD_LOCK_MARKER.size - written).toDouble(),
                position = written.toDouble(),
            ).toInt()
        } catch (caught: Throwable) {
            throw caught.toWorldLockException("write marker to", path)
        }
        if (result <= 0) {
            throw WorldLockException(
                "Could not write marker to world lock $path",
            )
        }
        written += result
    }
}

private fun syncWorldLock(descriptor: Number, path: Path) {
    try {
        fsyncSync(descriptor)
    } catch (caught: Throwable) {
        throw caught.toWorldLockException("durably sync", path)
    }
}

private fun tryAcquireWorldLock(descriptor: Number, path: Path): Boolean =
    try {
        tryLock(descriptor)
    } catch (caught: Throwable) {
        when (caught.nodeErrorCode) {
            NODE_ACCESS_DENIED,
            NODE_LOCK_UNAVAILABLE,
            NODE_WOULD_BLOCK,
                -> false

            else -> throw caught.toWorldLockException("lock", path)
        }
    }

private fun canonicalLockPath(path: Path): String {
    val parent = path.parent
        ?: throw WorldLockException("World lock has no parent: $path")
    return (systemFileSystem.canonicalize(parent) / path.name).toString()
}

private val Throwable.nodeErrorCode: String?
    get() = asDynamic().code as? String

private fun Throwable.toWorldLockException(
    operation: String,
    path: Path,
): WorldLockException {
    val code = nodeErrorCode
    val codeSuffix = if (code == null) "" else " ($code)"
    return WorldLockException(
        "Could not $operation world lock $path$codeSuffix",
        this,
    )
}

private const val NODE_ACCESS_DENIED = "EACCES"
private const val NODE_LOCK_UNAVAILABLE = "EAGAIN"
private const val NODE_NO_SUCH_FILE = "ENOENT"
private const val NODE_OPERATION_NOT_PERMITTED = "EPERM"
private const val NODE_WOULD_BLOCK = "EWOULDBLOCK"
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
private val IN_PROCESS_LOCK_PATHS = mutableSetOf<String>()
