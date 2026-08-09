package com.hiczp.minecraft.world.io

import okio.IOException
import okio.Path

internal interface WorldDirectoryLock : AutoCloseable {
    val isValid: Boolean
}

/*
 * Actual implementations mirror the official marker-write, durable flush,
 * and non-blocking whole-file lock sequence with each host's lock API. Known
 * contention paths are normalized to WorldLockException; unrelated failures
 * remain in Okio's IOException hierarchy on every public world-io target.
 */
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

/** The official `session.lock` is already held by this process or another world owner. */
class WorldLockException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
