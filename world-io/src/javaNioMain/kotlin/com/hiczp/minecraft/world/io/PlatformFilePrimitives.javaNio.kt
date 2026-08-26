package com.hiczp.minecraft.world.io

import okio.FileHandle
import okio.FileSystem
import okio.Path
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ

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

internal actual fun FileSystem.openRandomAccessReadWrite(
    path: Path,
): FileHandle = openReadWrite(path)

internal actual fun FileSystem.createRandomAccessReadWrite(
    path: Path,
): FileHandle = openReadWrite(path, mustCreate = true)

internal actual fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle = openTruncatedReadWriteUsingResize(path)

internal actual fun FileSystem.openLiveReadOnly(path: Path): FileHandle {
    if (this !== FileSystem.SYSTEM) return openReadOnly(path)
    return NioLiveReadOnlyFileHandle(
        path = path,
        fileChannel = FileChannel.open(path.toNioPath(), READ),
    )
}

internal actual fun syncSystemFilePath(path: Path) = Unit

private class NioLiveReadOnlyFileHandle(
    private val path: Path,
    private val fileChannel: FileChannel,
) : FileHandle(readWrite = false) {
    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Int = fileChannel.read(
        ByteBuffer.wrap(array, arrayOffset, byteCount),
        fileOffset,
    )

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Nothing = readOnlyOperation("write")

    override fun protectedFlush(): Nothing = readOnlyOperation("flush")

    override fun protectedResize(size: Long): Nothing =
        readOnlyOperation("resize")

    override fun protectedSize(): Long = fileChannel.size()

    override fun protectedClose() = fileChannel.close()

    private fun readOnlyOperation(operation: String): Nothing =
        throw IllegalStateException(
            "Cannot $operation live read-only file $path",
        )
}
