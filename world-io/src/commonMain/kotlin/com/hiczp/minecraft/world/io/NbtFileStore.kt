package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.RegionCompression
import com.hiczp.minecraft.world.format.RegionCompressionCodecs
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.*
import kotlinx.io.IOException as KotlinxIOException

enum class NbtFileCompression(
    internal val regionCompression: RegionCompression,
) {
    NONE(RegionCompression.NONE),
    GZIP(RegionCompression.GZIP),
    ZLIB(RegionCompression.ZLIB),
}

data class NbtFileStoreConfiguration(
    val maximumCompressedBytes: Int = 256 * 1_048_576,
    val maximumDecompressedBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumCompressedBytes >= 0)
        require(maximumDecompressedBytes >= 0)
    }
}

/** Physical unnamed-root NBT streams over Okio files. */
class NbtFileStore internal constructor(
    internal val files: WorldFileAccess,
    val nbt: NbtFormat = NbtFormat,
    val compressionCodecs: RegionCompressionCodecs =
        RegionCompressionCodecs,
    val configuration: NbtFileStoreConfiguration =
        NbtFileStoreConfiguration(),
) {
    constructor(
        fileSystem: FileSystem = systemFileSystem,
        nbt: NbtFormat = NbtFormat,
        compressionCodecs: RegionCompressionCodecs =
            RegionCompressionCodecs,
        configuration: NbtFileStoreConfiguration =
            NbtFileStoreConfiguration(),
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
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ): NbtDocument = files.readFile(
        path,
        configuration.maximumCompressedBytes,
    ) { source, _ ->
        val decompressed = compressionCodecs.decompressingSource(
            compression.regionCompression,
            source,
            configuration.maximumDecompressedBytes,
        ).asKotlinxIoRawSource().buffered()
        withOkioIoExceptions {
            decompressed.use { opened ->
                val document = nbt.decodeDocumentFromSource(opened)
                if (!opened.exhausted()) {
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
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ) {
        files.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        fileSystem.openTruncatedReadWrite(path).use { handle ->
            writeHandle(path, handle, document, compression)
        }
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        document: NbtDocument,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ): Path {
        files.requireWritable()
        val temporary = fileSystem.openUniqueTemporaryHandle(directory)
        try {
            temporary.handle.use { handle ->
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
        compression: NbtFileCompression,
    ) {
        val limitedFileSink = LimitedSink(
            handle.sink(),
            configuration.maximumCompressedBytes,
            closeDelegate = true,
        )
        limitedFileSink.buffer().use { fileSink ->
            encode(document, compression, fileSink)
            fileSink.flush()
        }
        handle.resize(limitedFileSink.bytesWritten)
        handle.flushDurably(fileSystem, path)
    }

    private fun encode(
        document: NbtDocument,
        compression: NbtFileCompression,
        sink: Sink,
    ) {
        val compressed = compressionCodecs.compressingSink(
            compression.regionCompression,
            sink,
        )
        val limited = LimitedSink(
            compressed,
            configuration.maximumDecompressedBytes,
            closeDelegate = true,
        ).asKotlinxIoRawSink().buffered()
        withOkioIoExceptions {
            limited.use { nbtSink ->
                nbt.encodeDocumentToSink(document, nbtSink)
            }
        }
    }
}

/*
 * kotlinx-io-okio already converts failures raised while crossing each stream
 * adapter. NbtFormat itself nevertheless has a kotlinx-io API and can let that
 * converted type escape after the stream call returns. Normalize only that
 * outer API boundary so world-io callers always receive Okio IOException.
 */
private inline fun <T> withOkioIoExceptions(block: () -> T): T = try {
    block()
} catch (failure: KotlinxIOException) {
    throw WorldIOException(failure.message ?: "I/O operation failed", failure)
}
