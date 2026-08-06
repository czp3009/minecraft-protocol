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
    cause: Throwable? = null,
): WorldLockException = WorldLockException(
    "$absolutePath: $WORLD_LOCK_ALREADY_LOCKED_REASON",
    cause,
)

class WorldLockException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
