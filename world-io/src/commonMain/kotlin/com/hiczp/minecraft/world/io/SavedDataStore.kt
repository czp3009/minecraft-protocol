package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.Compression
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

sealed interface SavedDataScope {
    data object WorldRoot : SavedDataScope

    data class Dimension(
        val dimensionDirectory: DimensionDirectory,
    ) : SavedDataScope
}

/** Stateless path, compression-detection, and direct-write policy for saved data. */
class SavedDataStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val savedDataScope: SavedDataScope,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    fun readDocument(identifier: String): NbtDocument? =
        read(identifier, nbtFileStore.nbtFormat::decodeDocumentFromOkio)

    fun <T> read(identifier: String, deserializationStrategy: DeserializationStrategy<T>): T? =
        read(identifier) { source ->
            nbtFileStore.nbtFormat.decodeFromOkio(deserializationStrategy, source)
        }

    inline fun <reified T> read(identifier: String): T? =
        read(identifier, nbtFileStore.nbtFormat.serializersModule.serializer())

    /** Detects compression and lends the complete decompressed stream through one physical file open. */
    fun <T> read(identifier: String, block: (BufferedSource) -> T): T? {
        val path = minecraftWorldPaths.savedData(identifier, savedDataScope)
        return nbtFileStore.readDetectingCompressionOrNull(path, ::detectCompression, block)
    }

    fun writeDocument(identifier: String, nbtDocument: NbtDocument) {
        nbtFileStore.writeDocument(minecraftWorldPaths.savedData(identifier, savedDataScope), nbtDocument)
    }

    fun <T> write(identifier: String, serializationStrategy: SerializationStrategy<T>, value: T) =
        nbtFileStore.write(minecraftWorldPaths.savedData(identifier, savedDataScope), serializationStrategy, value)

    inline fun <reified T> write(identifier: String, value: T) =
        write(identifier, nbtFileStore.nbtFormat.serializersModule.serializer(), value)

    fun write(identifier: String, block: (BufferedSink) -> Unit) {
        nbtFileStore.write(minecraftWorldPaths.savedData(identifier, savedDataScope), block = block)
    }

    private fun detectCompression(source: BufferedSource): Compression =
        if (
            source.request(2L) &&
            source.buffer[0L] == GZIP_MAGIC_FIRST &&
            source.buffer[1L] == GZIP_MAGIC_SECOND
        ) {
            Compression.GZIP
        } else {
            Compression.NONE
        }
}

private const val GZIP_MAGIC_FIRST: Byte = 0x1F
private const val GZIP_MAGIC_SECOND: Byte = 0x8B.toByte()
