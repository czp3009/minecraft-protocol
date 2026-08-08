package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.use

internal actual fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle {
    /*
     * Okio 3.18.1's MinGW WindowsFileHandle treats SetFilePointer returning
     * zero as a failure. Zero is the successful position when truncating to
     * the beginning of a file, so resize(0) throws with a stale Win32 error.
     * Truncate through Okio's streaming path before opening the random-access
     * handle. JVM, Node, and POSIX retain the ordinary single-handle resize.
     */
    sink(path).use {}
    return openReadWrite(path, mustExist = true)
}

internal actual fun syncSystemFilePath(path: Path) = Unit
