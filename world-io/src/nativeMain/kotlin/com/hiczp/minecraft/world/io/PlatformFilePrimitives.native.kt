package com.hiczp.minecraft.world.io

import okio.FileSystem
import okio.Path

internal actual fun FileSystem.moveReplacing(
    source: Path,
    target: Path,
) {
    atomicMove(source, target)
}
