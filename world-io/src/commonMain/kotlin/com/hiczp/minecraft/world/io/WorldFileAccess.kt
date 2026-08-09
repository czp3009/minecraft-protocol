package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.Source

/** Shared filesystem capability behind mutable and live read-only stores. */
internal class WorldFileAccess private constructor(
    val fileSystem: FileSystem,
    val liveReadOnly: Boolean,
) {
    fun openRegionHandle(path: Path): FileHandle =
        if (liveReadOnly) fileSystem.openLiveReadOnly(path)
        else fileSystem.openReadWrite(path)

    fun openSource(path: Path): Source {
        if (!liveReadOnly) return fileSystem.source(path)
        val handle = fileSystem.openLiveReadOnly(path)
        var source: Source? = null
        try {
            val opened = handle.source()
            source = opened
            handle.close()
            return opened
        } catch (failure: Throwable) {
            closeAllPreserving(
                failure,
                { source?.close() },
                handle::close,
            )
            throw failure
        }
    }

    fun requireWritable() {
        check(!liveReadOnly) { "World file access is live read-only" }
    }

    companion object {
        fun mutable(fileSystem: FileSystem): WorldFileAccess =
            WorldFileAccess(fileSystem, liveReadOnly = false)

        fun liveReadOnly(fileSystem: FileSystem): WorldFileAccess =
            WorldFileAccess(fileSystem, liveReadOnly = true)
    }
}
