package com.hiczp.minecraft.test

import kotlinx.io.files.Path
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal fun Path.toNioPath(): java.nio.file.Path =
    java.nio.file.Path.of(toString()).toAbsolutePath().normalize()

internal inline fun <T> Path.withExclusiveJvmFileLock(
    action: () -> T,
): T {
    val nioPath = toNioPath()
    Files.createDirectories(requireNotNull(nioPath.parent))
    return FileChannel.open(
        nioPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    ).use { channel ->
        channel.lock().use { action() }
    }
}
