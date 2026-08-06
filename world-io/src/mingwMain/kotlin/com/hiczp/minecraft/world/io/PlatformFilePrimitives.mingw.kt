package com.hiczp.minecraft.world.io

import okio.FileSystem
import okio.Path

internal actual val systemFileSystem: FileSystem
    get() = FileSystem.SYSTEM

internal actual fun syncSystemFilePath(path: Path) = Unit
