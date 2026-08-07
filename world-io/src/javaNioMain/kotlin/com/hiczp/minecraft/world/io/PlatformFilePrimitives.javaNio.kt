package com.hiczp.minecraft.world.io

import okio.FileSystem
import okio.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal actual val systemFileSystem: FileSystem
    get() = FileSystem.SYSTEM

internal actual fun FileSystem.moveReplacing(
    source: Path,
    target: Path,
) {
    if (this !== FileSystem.SYSTEM) {
        atomicMove(source, target)
        return
    }
    Files.move(
        source.toNioPath(),
        target.toNioPath(),
        REPLACE_EXISTING,
    )
}

internal actual fun syncSystemFilePath(path: Path) = Unit
