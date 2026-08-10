package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.Path

internal expect val systemFileSystem: FileSystem

internal fun FileHandle.flushDurably(
    fileSystem: FileSystem,
    path: Path,
) {
    flush()
    if (fileSystem === systemFileSystem) syncSystemFilePath(path)
}

internal expect fun FileSystem.moveReplacing(
    source: Path,
    target: Path,
)

/**
 * Opens a read/write random-access handle.
 *
 * Common code must not replace this with Okio's [FileSystem.openReadWrite].
 * Okio 3.18.1's MinGW system handle uses invalid result handling for
 * synchronous `ReadFile` and `WriteFile`, and its resize implementation
 * misreports valid results. Other platforms and injected filesystems delegate
 * directly to Okio.
 */
internal expect fun FileSystem.openRandomAccessReadWrite(
    path: Path,
): FileHandle

/**
 * Atomically creates a new read/write random-access file.
 *
 * This is separate from Okio's `openReadWrite(..., mustCreate = true)` because
 * MinGW must retain the same exclusive-creation semantics while returning the
 * corrected native handle instead of Okio's broken `WindowsFileHandle`.
 */
internal expect fun FileSystem.createRandomAccessReadWrite(
    path: Path,
): FileHandle

/**
 * Truncates at open and returns a reliable random-access handle.
 *
 * Do not simplify this to `openReadWrite(path).resize(0)`: that exact sequence
 * enters Okio's faulty MinGW resize implementation and produced the misleading
 * `ERROR_SUCCESS` message that motivated this platform boundary.
 */
internal expect fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle

/**
 * Opens a read-only handle that does not deny concurrent file mutations.
 *
 * This intentionally remains a platform primitive rather than calling Okio's
 * [FileSystem.openReadOnly] directly: JVM and MinGW require explicit sharing
 * behavior for official-server writes and replacements, and MinGW must also
 * avoid Okio's broken `WindowsFileHandle`.
 */
internal expect fun FileSystem.openLiveReadOnly(
    path: Path,
): FileHandle

internal fun FileSystem.openTruncatedReadWriteUsingResize(
    path: Path,
): FileHandle {
    val handle = openReadWrite(path)
    try {
        handle.resize(0L)
        return handle
    } catch (failure: Throwable) {
        closeAllPreserving(failure, handle::close)
        throw failure
    }
}

internal expect fun syncSystemFilePath(path: Path)
