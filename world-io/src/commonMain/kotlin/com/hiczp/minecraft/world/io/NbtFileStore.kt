package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionCodecs
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.*
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSink as KotlinxRawSink
import kotlinx.io.RawSource as KotlinxRawSource

data class NbtFileStoreConfiguration(
    val maximumCompressedBytes: Int = 256 * 1_048_576,
    val maximumDecompressedBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumCompressedBytes >= 0)
        require(maximumDecompressedBytes >= 0)
    }
}

/**
 * Physical unnamed-root NBT streams over Okio files.
 *
 * Official files use the GZIP and NONE wrappers (`level.dat`, player data, and
 * saved data); ZLIB stays selectable, and any other registered compression is
 * a caller-owned choice rather than an official file policy.
 */
class NbtFileStore internal constructor(
    internal val files: WorldFileAccess,
    val nbt: NbtFormat = NbtFormat,
    val compressionCodecs: CompressionCodecs = CompressionCodecs,
    val configuration: NbtFileStoreConfiguration = NbtFileStoreConfiguration(),
) {
    constructor(
        fileSystem: FileSystem = systemFileSystem,
        nbt: NbtFormat = NbtFormat,
        compressionCodecs: CompressionCodecs = CompressionCodecs,
        configuration: NbtFileStoreConfiguration = NbtFileStoreConfiguration(),
    ) : this(
        files = WorldFileAccess.mutable(fileSystem),
        nbt = nbt,
        compressionCodecs = compressionCodecs,
        configuration = configuration,
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

    internal val liveReadOnly: Boolean
        get() = files.liveReadOnly

    fun read(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): NbtDocument = files.readFile(
        path,
        configuration.maximumCompressedBytes,
    ) { source, _ ->
        withOkioIoExceptions("Cannot read NBT file $path") {
            val converted = source.asKotlinxIoRawSource().buffered()
            val decompressed = compressionCodecs.decompressingSource(
                compression,
                converted,
                Int.MAX_VALUE,
            )
            val opened = MaximumBytesRawSource(
                decompressed,
                configuration.maximumDecompressedBytes,
            ).buffered()
            useResource(opened, { it.close() }) { source ->
                val document = nbt.decodeDocumentFromSource(source)
                if (!source.exhausted()) {
                    throw NbtDecodingException(
                        "Decompressed NBT file has trailing bytes",
                    )
                }
                document
            }
        }
    }

    /** Directly truncates, writes, and durably syncs the final file. */
    fun writeDirect(
        path: Path,
        document: NbtDocument,
        compression: Compression = Compression.GZIP,
    ) {
        files.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        val handle = fileSystem.openTruncatedReadWrite(path)
        useResource(handle, { it.close() }) {
            writeHandle(path, handle, document, compression)
        }
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        document: NbtDocument,
        compression: Compression = Compression.GZIP,
    ): Path {
        files.requireWritable()
        val temporary = fileSystem.openUniqueTemporaryHandle(directory)
        try {
            useResource(temporary.handle, { it.close() }) { handle ->
                writeHandle(
                    temporary.path,
                    handle,
                    document,
                    compression,
                )
            }
            return temporary.path
        } catch (failure: Throwable) {
            fileSystem.deleteIfExistsPreserving(temporary.path, failure)
            throw failure
        }
    }

    internal fun openSource(path: Path): Source = files.openSource(path)

    private fun writeHandle(
        path: Path,
        handle: FileHandle,
        document: NbtDocument,
        compression: Compression,
    ) {
        val limitedFileSink = LimitedSink(
            handle.sink(),
            configuration.maximumCompressedBytes,
            closeDelegate = true,
        )
        val fileSink = limitedFileSink.buffer()
        useResource(fileSink, { it.close() }) {
            encode(document, compression, fileSink)
            fileSink.flush()
        }
        handle.resize(limitedFileSink.bytesWritten)
        handle.flushDurably(fileSystem, path)
    }

    private fun encode(
        document: NbtDocument,
        compression: Compression,
        sink: Sink,
    ) {
        withOkioIoExceptions("Cannot write NBT stream") {
            val converted = sink.asKotlinxIoRawSink().buffered()
            val compressed = compressionCodecs.compressingSink(
                compression,
                converted,
            )
            val limited = MaximumBytesRawSink(
                compressed,
                configuration.maximumDecompressedBytes,
            ).buffered()
            useResource(limited, { it.close() }) { sink ->
                nbt.encodeDocumentToSink(document, sink)
            }
            converted.flush()
        }
    }
}

/*
 * NbtFileStore's decompressed limit is a world-io file policy, not an Anvil
 * format limit. Keep this common wrapper here so exceeding it remains an Okio
 * WorldIOException instead of being mislabeled as RegionFormatException.
 * kotlinx.io currently has no equivalent source/sink byte-limit decorator.
 */
private class MaximumBytesRawSink(
    private val delegate: KotlinxRawSink,
    maximumBytes: Int,
) : KotlinxRawSink {
    private val maximumBytes = maximumBytes.toLong()
    private var bytesWritten = 0L

    override fun write(source: KotlinxBuffer, byteCount: Long) {
        if (byteCount < 0 || byteCount > maximumBytes - bytesWritten) {
            throw WorldIOException(
                "Decompressed NBT output exceeds configured limit $maximumBytes",
            )
        }
        delegate.write(source, byteCount)
        bytesWritten += byteCount
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

private class MaximumBytesRawSource(
    private val delegate: KotlinxRawSource,
    maximumBytes: Int,
) : KotlinxRawSource {
    private val maximumBytes = maximumBytes.toLong()
    private var bytesRead = 0L

    override fun readAtMostTo(
        sink: KotlinxBuffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0L
        val remaining = maximumBytes - bytesRead
        val read = delegate.readAtMostTo(
            sink,
            minOf(byteCount, remaining + 1),
        )
        if (read < 0) return -1L
        bytesRead += read
        if (bytesRead > maximumBytes) {
            throw WorldIOException(
                "Decompressed NBT input exceeds configured limit $maximumBytes",
            )
        }
        return read
    }

    override fun close() {
        delegate.close()
    }
}
