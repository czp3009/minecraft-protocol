package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.RegionCompression
import com.hiczp.minecraft.world.format.RegionCompressionCodecs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

/**
 * Reads and atomically writes standalone compound-document NBT files.
 *
 * Writes use a sibling temporary file and remove it if serialization,
 * compression, flushing, closing, or replacement fails. Every failure is
 * propagated to the caller; serialization failures are not translated into
 * filesystem failures.
 */
class NbtFileStore(
    val fileSystem: FileSystem = SystemFileSystem,
    val nbt: NbtFormat = NbtFormat,
    val compressionCodecs: RegionCompressionCodecs =
        RegionCompressionCodecs,
    val configuration: NbtFileStoreConfiguration =
        NbtFileStoreConfiguration(),
) {
    private val writeMutex = Mutex()

    /**
     * Reads and decodes one file.
     *
     * Any filesystem, compression, or serialization exception is propagated
     * to the caller.
     */
    suspend fun read(
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
        var failure: Throwable? = null
        try {
            val document = nbt.decodeDocumentFromSource(decompressed)
            if (!decompressed.exhausted()) {
                throw NbtDecodingException(
                    "Decompressed NBT file has trailing bytes",
                )
            }
            document
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(failure, decompressed::close)
        }
    }

    /**
     * Encodes and atomically replaces one file.
     *
     * Any exception is propagated to the caller. If writing has created a
     * temporary file, this store attempts to remove it before rethrowing.
     */
    suspend fun write(
        path: Path,
        document: NbtDocument,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ) {
        writeMutex.withLock {
            fileSystem.writeAtomically(
                path,
                configuration.maximumCompressedBytes,
            ) { sink ->
                val compressed = compressionCodecs.compressingSink(
                    compression.regionCompression,
                    sink,
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
                    )
                }
            }
        }
    }
}
