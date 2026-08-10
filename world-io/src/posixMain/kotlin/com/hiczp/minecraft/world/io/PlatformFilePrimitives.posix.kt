package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.posix.*

internal actual fun FileSystem.openRandomAccessReadWrite(
    path: Path,
): FileHandle = openReadWrite(path)

internal actual fun FileSystem.createRandomAccessReadWrite(
    path: Path,
): FileHandle = openReadWrite(path, mustCreate = true)

internal actual fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle = openTruncatedReadWriteUsingResize(path)

internal actual fun FileSystem.openLiveReadOnly(path: Path): FileHandle =
    openReadOnly(path)

internal actual fun syncSystemFilePath(path: Path) {
    var descriptor: Int
    do {
        descriptor = open(path.toString(), O_WRONLY)
        // UnixNativeDispatcher.open uses OpenJDK's RESTARTABLE macro.
    } while (descriptor == -1 && errno == EINTR)
    if (descriptor == -1) {
        throw IOException("Could not open $path for durable sync")
    }
    var failure: Throwable? = null
    try {
        var result: Int
        do {
            result = fsync(descriptor)
            // FileChannel.force repeats the native operation after EINTR.
        } while (result != 0 && errno == EINTR)
        if (result != 0) {
            throw IOException("Could not durably sync $path")
        }
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        /*
         * Deliberately call close only once. OpenJDK's FileChannel dispatcher
         * does not restart close, and after EINTR the descriptor may already
         * be closed and reused; retrying could close an unrelated file.
         */
        if (close(descriptor) != 0) {
            val closeFailure = IOException(
                "Could not close durable-sync descriptor for $path",
            )
            if (failure == null) throw closeFailure
            failure.addSuppressed(closeFailure)
        }
    }
}
