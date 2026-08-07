package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.RegionCompression
import com.hiczp.minecraft.world.format.RegionCompressionCodecs
import kotlinx.io.RawSink
import kotlinx.io.buffered
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.use

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
class NbtFileStore(
    val fileSystem: FileSystem = systemFileSystem,
    val nbt: NbtFormat = NbtFormat,
    val compressionCodecs: RegionCompressionCodecs =
        RegionCompressionCodecs,
    val configuration: NbtFileStoreConfiguration =
        NbtFileStoreConfiguration(),
) {
    fun read(
        path: Path,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ): NbtDocument = fileSystem.readFile(
        path,
        configuration.maximumCompressedBytes,
    ) { source, _ ->
        val decompressed = compressionCodecs.decompressingSource(
            compression.regionCompression,
            source,
            configuration.maximumDecompressedBytes,
        ).buffered()
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

    /** Directly truncates, writes, and durably syncs the final file. */
    fun writeDirect(
        path: Path,
        document: NbtDocument,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ) {
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        fileSystem.openReadWrite(path).use { handle ->
            handle.resize(0L)
            writeHandle(path, handle, document, compression)
        }
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        document: NbtDocument,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ): Path {
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

    private fun writeHandle(
        path: Path,
        handle: FileHandle,
        document: NbtDocument,
        compression: NbtFileCompression,
    ) {
        val limitedFileSink = LimitedRawSink(
            KotlinxToOkioRawSink(handle.sink()),
            configuration.maximumCompressedBytes,
            closeDelegate = true,
        )
        limitedFileSink.buffered().use { fileSink ->
            encode(document, compression, fileSink)
            fileSink.flush()
        }
        handle.resize(limitedFileSink.bytesWritten)
        handle.flushDurably(fileSystem, path)
    }

    private fun encode(
        document: NbtDocument,
        compression: NbtFileCompression,
        sink: RawSink,
    ) {
        val bufferedSink = sink.buffered()
        val compressed = compressionCodecs.compressingSink(
            compression.regionCompression,
            bufferedSink,
        ).buffered()
        val limited = LimitedRawSink(
            compressed,
            configuration.maximumDecompressedBytes,
        ).buffered()
        var failure: Throwable? = null
        try {
            nbt.encodeDocumentToSink(document, limited)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(
                failure,
                limited::close,
                compressed::close,
                bufferedSink::flush,
            )
        }
    }
}
