package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.NodeJsFileSystem
import okio.Path

internal actual val systemFileSystem: FileSystem
    get() = NodeJsFileSystem

internal actual fun FileSystem.moveReplacing(
    source: Path,
    target: Path,
) {
    atomicMove(source, target)
}

internal actual fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle = openTruncatedReadWriteUsingResize(path)

internal actual fun FileSystem.openLiveReadOnly(path: Path): FileHandle =
    openReadOnly(path)

internal actual fun syncSystemFilePath(path: Path) {
    val descriptor = try {
        openSync(path.toString(), constants.O_WRONLY)
    } catch (caught: Throwable) {
        throw WorldIOException(
            "Could not open $path for durable sync",
            caught,
        )
    }
    var failure: Throwable? = null
    try {
        fsyncSync(descriptor)
    } catch (caught: Throwable) {
        val syncFailure = WorldIOException(
            "Could not durably sync $path",
            caught,
        )
        failure = syncFailure
        throw syncFailure
    } finally {
        try {
            closeSync(descriptor)
        } catch (caught: Throwable) {
            val closeFailure = WorldIOException(
                "Could not close durable-sync descriptor for $path",
                caught,
            )
            failure?.addSuppressed(closeFailure) ?: throw closeFailure
        }
    }
}
