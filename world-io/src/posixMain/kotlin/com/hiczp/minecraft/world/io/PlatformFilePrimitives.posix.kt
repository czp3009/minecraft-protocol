package com.hiczp.minecraft.world.io

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

internal actual val systemFileSystem: FileSystem
    get() = FileSystem.SYSTEM

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncSystemFilePath(path: Path) {
    val descriptor = open(path.toString(), O_WRONLY)
    if (descriptor == -1) {
        throw IOException("Could not open $path for durable sync")
    }
    var failure: Throwable? = null
    try {
        if (fsync(descriptor) != 0) {
            throw IOException("Could not durably sync $path")
        }
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        if (close(descriptor) != 0) {
            val closeFailure = IOException(
                "Could not close durable-sync descriptor for $path",
            )
            if (failure == null) throw closeFailure
            failure.addSuppressed(closeFailure)
        }
    }
}
