package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionRegistry
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import okio.*
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Physical unnamed-root NBT streams over Okio files.
 *
 * Official files use the GZIP and NONE wrappers (`level.dat`, player data, and
 * saved data); ZLIB stays selectable, and any other registered compression is
 * a caller-owned choice rather than an official file policy.
 */
class NbtFileStore internal constructor(
    internal val worldFileAccess: WorldFileAccess,
    val nbtFormat: NbtFormat = minecraftWorldNbtFormat(),
    val compressionCodecs: CompressionRegistry = CompressionRegistry,
) {
    init {
        nbtFormat.requireStandaloneWorldRoot()
    }

    constructor(
        fileSystem: FileSystem = systemFileSystem,
        nbtFormat: NbtFormat = minecraftWorldNbtFormat(),
        compressionCodecs: CompressionRegistry = CompressionRegistry,
    ) : this(
        worldFileAccess = WorldFileAccess.mutable(fileSystem),
        nbtFormat = nbtFormat,
        compressionCodecs = compressionCodecs,
    )

    val fileSystem: FileSystem
        get() = worldFileAccess.fileSystem

    internal val liveReadOnly: Boolean
        get() = worldFileAccess.liveReadOnly

    fun readDocument(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): NbtDocument = read(path, compression) { source ->
        nbtFormat.decodeDocumentFromSource(source)
    }

    fun <T> read(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        compression: Compression = Compression.GZIP,
    ): T = read(path, compression) { source ->
        nbtFormat.decodeFromSource(deserializationStrategy, source)
    }

    /** Lends the decompressed file stream for the duration of [block]. */
    fun <T> read(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (KotlinxSource) -> T,
    ): T = worldFileAccess.readFile(path) { bufferedSource, _ ->
        withOkioIoExceptions("Cannot read NBT file $path") {
            val converted = bufferedSource.asKotlinxIoRawSource().buffered()
            val opened = compressionCodecs.decompressingSource(compression, converted).buffered()
            useResource(opened, { it.close() }) { source ->
                val value = block(source)
                if (!source.exhausted()) {
                    throw NbtDecodingException(
                        "Decompressed NBT file has trailing bytes",
                    )
                }
                value
            }
        }
    }

    /** Directly truncates, writes, and durably syncs the final file. */
    fun writeDocument(
        path: Path,
        nbtDocument: NbtDocument,
        compression: Compression = Compression.GZIP,
    ) = write(path, compression) { sink ->
        nbtFormat.encodeDocumentToSink(nbtDocument, sink)
    }

    fun <T> write(
        path: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.GZIP,
    ) = write(path, compression) { sink ->
        nbtFormat.encodeToSink(serializationStrategy, value, sink)
    }

    /** Directly truncates, streams, and durably syncs the final file. */
    fun write(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (KotlinxSink) -> Unit,
    ) {
        worldFileAccess.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        val fileHandle = fileSystem.openTruncatedReadWrite(path)
        useResource(fileHandle, { it.close() }) {
            writeHandle(path, fileHandle, compression, block)
        }
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        nbtDocument: NbtDocument,
        compression: Compression = Compression.GZIP,
    ): Path = writeSyncedTemporary(directory, compression) { sink ->
        nbtFormat.encodeDocumentToSink(nbtDocument, sink)
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        compression: Compression = Compression.GZIP,
        block: (KotlinxSink) -> Unit,
    ): Path {
        worldFileAccess.requireWritable()
        val temporaryFileHandle = fileSystem.openUniqueTemporaryHandle(directory)
        try {
            useResource(temporaryFileHandle.fileHandle, { it.close() }) { fileHandle ->
                writeHandle(
                    temporaryFileHandle.path,
                    fileHandle,
                    compression,
                    block,
                )
            }
            return temporaryFileHandle.path
        } catch (failure: Throwable) {
            fileSystem.deleteIfExistsPreserving(temporaryFileHandle.path, failure)
            throw failure
        }
    }

    internal fun openSource(path: Path): Source = worldFileAccess.openSource(path)

    private fun writeHandle(
        path: Path,
        fileHandle: FileHandle,
        compression: Compression,
        block: (KotlinxSink) -> Unit,
    ) {
        val countingFileSink = CountingSink(
            fileHandle.sink(),
            closeDelegate = true,
        )
        val fileSink = countingFileSink.buffer()
        useResource(fileSink, { it.close() }) {
            encode(compression, fileSink, block)
            fileSink.flush()
        }
        fileHandle.resize(countingFileSink.bytesWritten)
        fileHandle.flushDurably(fileSystem, path)
    }

    private fun encode(
        compression: Compression,
        sink: Sink,
        block: (KotlinxSink) -> Unit,
    ) {
        withOkioIoExceptions("Cannot write NBT stream") {
            val converted = sink.asKotlinxIoRawSink().buffered()
            val compressed = compressionCodecs.compressingSink(
                compression,
                converted,
            ).buffered()
            useResource(compressed, { it.close() }) { sink ->
                block(sink)
            }
            converted.flush()
        }
    }
}

internal fun NbtFormat.requireStandaloneWorldRoot() {
    require(nbtFormatConfiguration.nbtRootEncoding == NbtRootEncoding.UNNAMED) {
        "Standalone world NBT requires NbtRootEncoding.UNNAMED"
    }
}

/** Creates an NBT format with the unnamed-root framing used by standalone world files. */
fun minecraftWorldNbtFormat(
    serializersModule: SerializersModule = EmptySerializersModule(),
): NbtFormat = NbtFormat(
    NbtFormatConfiguration(
        serializersModule = serializersModule,
        nbtRootEncoding = NbtRootEncoding.UNNAMED,
    ),
)
