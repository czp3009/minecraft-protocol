package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionCodecs
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
    internal val files: WorldFileAccess,
    val nbt: NbtFormat = minecraftWorldNbtFormat(),
    val compressionCodecs: CompressionCodecs = CompressionCodecs,
) {
    init {
        nbt.requireStandaloneWorldRoot()
    }

    constructor(
        fileSystem: FileSystem = systemFileSystem,
        nbt: NbtFormat = minecraftWorldNbtFormat(),
        compressionCodecs: CompressionCodecs = CompressionCodecs,
    ) : this(
        files = WorldFileAccess.mutable(fileSystem),
        nbt = nbt,
        compressionCodecs = compressionCodecs,
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

    internal val liveReadOnly: Boolean
        get() = files.liveReadOnly

    fun readDocument(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): NbtDocument = read(path, compression) { source ->
        nbt.decodeDocumentFromSource(source)
    }

    fun <T> read(
        path: Path,
        deserializer: DeserializationStrategy<T>,
        compression: Compression = Compression.GZIP,
    ): T = read(path, compression) { source ->
        nbt.decodeFromSource(deserializer, source)
    }

    /** Lends the decompressed file stream for the duration of [block]. */
    fun <T> read(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (KotlinxSource) -> T,
    ): T = files.readFile(path) { source, _ ->
        withOkioIoExceptions("Cannot read NBT file $path") {
            val converted = source.asKotlinxIoRawSource().buffered()
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
        document: NbtDocument,
        compression: Compression = Compression.GZIP,
    ) = write(path, compression) { sink ->
        nbt.encodeDocumentToSink(document, sink)
    }

    fun <T> write(
        path: Path,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.GZIP,
    ) = write(path, compression) { sink ->
        nbt.encodeToSink(serializer, value, sink)
    }

    /** Directly truncates, streams, and durably syncs the final file. */
    fun write(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (KotlinxSink) -> Unit,
    ) {
        files.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        val handle = fileSystem.openTruncatedReadWrite(path)
        useResource(handle, { it.close() }) {
            writeHandle(path, handle, compression, block)
        }
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        document: NbtDocument,
        compression: Compression = Compression.GZIP,
    ): Path = writeSyncedTemporary(directory, compression) { sink ->
        nbt.encodeDocumentToSink(document, sink)
    }

    internal fun writeSyncedTemporary(
        directory: Path,
        compression: Compression = Compression.GZIP,
        block: (KotlinxSink) -> Unit,
    ): Path {
        files.requireWritable()
        val temporary = fileSystem.openUniqueTemporaryHandle(directory)
        try {
            useResource(temporary.handle, { it.close() }) { handle ->
                writeHandle(
                    temporary.path,
                    handle,
                    compression,
                    block,
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
        compression: Compression,
        block: (KotlinxSink) -> Unit,
    ) {
        val countingFileSink = CountingSink(
            handle.sink(),
            closeDelegate = true,
        )
        val fileSink = countingFileSink.buffer()
        useResource(fileSink, { it.close() }) {
            encode(compression, fileSink, block)
            fileSink.flush()
        }
        handle.resize(countingFileSink.bytesWritten)
        handle.flushDurably(fileSystem, path)
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
    require(configuration.rootEncoding == NbtRootEncoding.UNNAMED) {
        "Standalone world NBT requires NbtRootEncoding.UNNAMED"
    }
}

/** Creates an NBT format with the unnamed-root framing used by standalone world files. */
fun minecraftWorldNbtFormat(
    serializersModule: SerializersModule = EmptySerializersModule(),
): NbtFormat = NbtFormat(
    NbtFormatConfiguration(
        serializersModule = serializersModule,
        rootEncoding = NbtRootEncoding.UNNAMED,
    ),
)
