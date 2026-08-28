package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.*
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionRegistry
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSink
import kotlinx.io.okio.asOkioSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import okio.*

/**
 * Stateless standalone unnamed-root NBT operations for caller-supplied exact paths.
 *
 * Files, callback streams, and I/O failures use Okio. The only `kotlinx.io` boundary is the
 * invocation of the NBT and compression implementations owned by lower modules; the official
 * `kotlinx-io-okio` adapters translate failures while crossing that boundary.
 */
class NbtFileStore internal constructor(
    val rawFileStore: RawFileStore,
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
    ) : this(RawFileStore(fileSystem), nbtFormat, compressionCodecs)

    internal constructor(
        worldFileAccess: WorldFileAccess,
        nbtFormat: NbtFormat = minecraftWorldNbtFormat(),
        compressionCodecs: CompressionRegistry = CompressionRegistry,
    ) : this(RawFileStore(worldFileAccess), nbtFormat, compressionCodecs)

    val fileSystem: FileSystem
        get() = rawFileStore.fileSystem

    internal val liveReadOnly: Boolean
        get() = rawFileStore.liveReadOnly

    fun readDocument(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): NbtDocument = read(path, compression, nbtFormat::decodeDocumentFromOkio)

    fun <T> read(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        compression: Compression = Compression.GZIP,
    ): T = read(path, compression) { source ->
        nbtFormat.decodeFromOkio(deserializationStrategy, source)
    }

    inline fun <reified T> read(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): T = read(path, nbtFormat.serializersModule.serializer(), compression)

    /** Lends the complete decompressed NBT stream for the duration of [block]. */
    fun <T> read(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSource) -> T,
    ): T = rawFileStore.read(path) { compressedSource ->
        read(compressedSource, compression, block)
    }

    internal fun <T> readDetectingCompressionOrNull(
        path: Path,
        compressionDetector: (BufferedSource) -> Compression,
        block: (BufferedSource) -> T,
    ): T? = rawFileStore.readRegularFileOrNull(path) { compressedSource ->
        read(compressedSource, compressionDetector(compressedSource), block)
    }

    /** Reads a standalone world file whose official physical root contract is TAG_Compound. */
    internal fun <T> readCompoundDocument(path: Path, block: (BufferedSource) -> T): T = read(path) { source ->
        if (!source.request(1L)) throw NbtBinaryFormatException("NBT document is missing its root tag")
        val rootType = source.buffer[0L].toInt() and 0xFF
        if (rootType != NBT_COMPOUND_TAG_TYPE) {
            throw NbtBinaryFormatException("NBT document root must be TAG_Compound, got tag type $rootType")
        }
        block(source)
    }

    private fun <T> read(
        compressedSource: BufferedSource,
        compression: Compression,
        block: (BufferedSource) -> T,
    ): T {
        val kotlinxCompressedSource = compressedSource.asKotlinxIoRawSource().buffered()
        val decompressedSource = withOkioIoFailures {
            compressionCodecs.decompressingSource(compression, kotlinxCompressedSource)
        }.asOkioSource().buffer()
        return useResource(decompressedSource, { it.close() }) { source ->
            val value = block(source)
            if (!source.exhausted()) {
                throw NbtDecodingException("Decompressed NBT file has trailing bytes")
            }
            value
        }
    }

    /** Directly truncates, writes, and durably syncs the final file. */
    fun writeDocument(
        path: Path,
        nbtDocument: NbtDocument,
        compression: Compression = Compression.GZIP,
    ) = write(path, compression) { sink ->
        nbtFormat.encodeDocumentToOkio(nbtDocument, sink)
    }

    fun <T> write(
        path: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.GZIP,
    ) = write(path, compression) { sink ->
        nbtFormat.encodeToOkio(serializationStrategy, value, sink)
    }

    inline fun <reified T> write(
        path: Path,
        value: T,
        compression: Compression = Compression.GZIP,
    ) = write(path, nbtFormat.serializersModule.serializer(), value, compression)

    /** Directly truncates, streams, and durably syncs the final file. */
    fun write(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSink) -> Unit,
    ) {
        rawFileStore.writeDurably(path) { sink -> encode(compression, sink, block) }
    }

    internal fun writeSyncedTemporaryDocument(
        directory: Path,
        nbtDocument: NbtDocument,
        compression: Compression = Compression.GZIP,
    ): Path = writeSyncedTemporary(directory, compression) { sink ->
        nbtFormat.encodeDocumentToOkio(nbtDocument, sink)
    }

    internal fun <T> writeSyncedTemporary(
        directory: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.GZIP,
    ): Path = writeSyncedTemporary(directory, compression) { sink ->
        nbtFormat.encodeToOkio(serializationStrategy, value, sink)
    }

    internal inline fun <reified T> writeSyncedTemporary(
        directory: Path,
        value: T,
        compression: Compression = Compression.GZIP,
    ): Path = writeSyncedTemporary(directory, nbtFormat.serializersModule.serializer(), value, compression)

    internal fun writeSyncedTemporary(
        directory: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSink) -> Unit,
    ): Path {
        rawFileStore.worldFileAccess.requireWritable()
        val temporaryFileHandle = fileSystem.openUniqueTemporaryHandle(directory)
        try {
            useResource(temporaryFileHandle.fileHandle, { it.close() }) { fileHandle ->
                rawFileStore.writeDurably(temporaryFileHandle.path, fileHandle) { sink ->
                    encode(compression, sink, block)
                }
            }
            return temporaryFileHandle.path
        } catch (failure: Throwable) {
            fileSystem.deleteIfExistsPreserving(temporaryFileHandle.path, failure)
            throw failure
        }
    }

    private fun encode(
        compression: Compression,
        sink: Sink,
        block: (BufferedSink) -> Unit,
    ) {
        val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
        val compressedSink = withOkioIoFailures {
            compressionCodecs.compressingSink(compression, kotlinxSink)
        }.asOkioSink().buffer()
        useResource(compressedSink, { it.close() }) { bufferedSink ->
            block(bufferedSink)
        }
        // Compression decorators do not close their caller-owned endpoint. Emit their remaining
        // bytes without adding another physical flush; RawFileStore owns the durability boundary.
        withOkioIoFailures { kotlinxSink.emit() }
    }
}

private const val NBT_COMPOUND_TAG_TYPE = 10

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
