package com.hiczp.minecraft.world.io

import okio.Closeable
import okio.IOException
import okio.Path

internal interface WorldDirectoryLock : Closeable {
    val isValid: Boolean
}

internal expect fun acquireWorldDirectoryLock(path: Path): WorldDirectoryLock

internal expect fun isWorldDirectoryLocked(path: Path): Boolean

internal const val WORLD_LOCK_ALREADY_LOCKED_REASON =
    "already locked (possibly by other Minecraft instance?)"

internal fun worldAlreadyLockedException(
    absolutePath: String,
): WorldLockException = WorldLockException(
    "$absolutePath: $WORLD_LOCK_ALREADY_LOCKED_REASON",
)

/** Native/Node counterpart of FileChannel's overlapping-lock runtime error. */
internal fun worldOverlappingLockException(): IllegalStateException =
    IllegalStateException()

class WorldLockException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
