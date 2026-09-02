package com.hiczp.minecraft.world.io

import okio.*

/**
 * Stateless raw-file operations for caller-supplied exact paths.
 *
 * This store does not resolve paths relative to a world, coordinate logical files, acquire
 * `session.lock`, or participate in a [MinecraftWorldAccess] lifecycle. Callback streams are
 * borrowed only for the duration of the call; unread bytes are discarded before the stream closes.
 */
class RawFileStore internal constructor(
    internal val worldFileAccess: WorldFileAccess,
) {
    constructor(fileSystem: FileSystem = systemFileSystem) : this(WorldFileAccess.mutable(fileSystem))

    val fileSystem: FileSystem
        get() = worldFileAccess.fileSystem

    internal val liveReadOnly: Boolean
        get() = worldFileAccess.liveReadOnly

    fun readBytes(path: Path): ByteArray = read(path) { source -> source.readByteArray() }

    /** Lends the file source for the duration of [block]. */
    fun <T> read(path: Path, block: (BufferedSource) -> T): T =
        worldFileAccess.readFile(path) { bufferedSource, _ -> block(bufferedSource) }

    /** Returns `null` for a missing path and otherwise lends one regular file through one metadata observation. */
    internal fun <T> readRegularFileOrNull(path: Path, block: (BufferedSource) -> T): T? {
        val fileMetadata = fileSystem.metadataOrNull(path) ?: return null
        if (!fileMetadata.isRegularFile) {
            throw WorldIOException("Path is not a regular file: $path")
        }
        val byteCount = fileMetadata.size
            ?: throw WorldIOException("Regular file has no size: $path")
        return worldFileAccess.readFileAtKnownSize(path, byteCount, block)
    }

    fun writeBytes(path: Path, bytes: ByteArray) = write(path) { sink -> sink.write(bytes) }

    /** Directly truncates and writes the final path. */
    fun write(path: Path, block: (BufferedSink) -> Unit) {
        worldFileAccess.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        fileSystem.write(path) {
            block(this)
            flush()
        }
    }

    internal fun writeDurably(path: Path, block: (Sink) -> Unit) {
        worldFileAccess.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        val fileHandle = fileSystem.openTruncatedReadWrite(path)
        useResource(fileHandle, { it.close() }) {
            writeDurably(path, fileHandle, block)
        }
    }

    internal fun writeDurably(
        path: Path,
        fileHandle: FileHandle,
        block: (Sink) -> Unit,
    ) {
        val countingFileSink = CountingSink(fileHandle.sink(), closeDelegate = true)
        val fileSink = countingFileSink.buffer()
        useResource(fileSink, { it.close() }) {
            block(fileSink)
            fileSink.flush()
        }
        fileHandle.resize(countingFileSink.bytesWritten)
        fileHandle.flushDurably(fileSystem, path)
    }
}
