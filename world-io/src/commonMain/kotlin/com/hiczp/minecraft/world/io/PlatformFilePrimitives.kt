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

internal expect fun syncSystemFilePath(path: Path)
