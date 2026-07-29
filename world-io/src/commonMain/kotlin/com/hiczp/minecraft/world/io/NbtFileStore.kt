package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtBinaryFormat
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.RegionCompression
import com.hiczp.minecraft.world.format.RegionCompressionCodecs
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

/** Reads and atomically writes standalone named-root NBT files. */
class NbtFileStore(
    val fileSystem: FileSystem = SystemFileSystem,
    val nbt: NbtBinaryFormat = NbtBinaryFormat,
    val compressionCodecs: RegionCompressionCodecs =
        RegionCompressionCodecs,
    val configuration: NbtFileStoreConfiguration =
        NbtFileStoreConfiguration(),
) {
    suspend fun read(
        path: Path,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ): NbtDocument {
        val compressed = fileSystem.readByteArray(
            path,
            configuration.maximumCompressedBytes,
        )
        val bytes = compressionCodecs.decompress(
            compression.regionCompression,
            compressed,
            configuration.maximumDecompressedBytes,
        )
        return nbt.decodeDocumentFromByteArray(bytes)
    }

    suspend fun write(
        path: Path,
        document: NbtDocument,
        compression: NbtFileCompression = NbtFileCompression.GZIP,
    ) {
        val bytes = nbt.encodeDocumentToByteArray(document)
        if (bytes.size > configuration.maximumDecompressedBytes) {
            throw WorldIOException(
                "NBT document size ${bytes.size} exceeds configured limit " +
                        configuration.maximumDecompressedBytes,
            )
        }
        val compressed = compressionCodecs.compress(
            compression.regionCompression,
            bytes,
        )
        if (compressed.size > configuration.maximumCompressedBytes) {
            throw WorldIOException(
                "Compressed NBT size ${compressed.size} exceeds configured " +
                        "limit ${configuration.maximumCompressedBytes}",
            )
        }
        fileSystem.writeByteArrayAtomically(path, compressed)
    }
}
