package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.Path

internal fun FileHandle.flushDurably(
    fileSystem: FileSystem,
    path: Path,
) {
    flush()
    if (fileSystem === systemFileSystem) syncSystemFilePath(path)
}

internal expect val systemFileSystem: FileSystem

internal expect fun syncSystemFilePath(path: Path)
