package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.SavedDataId
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

sealed interface SavedDataScope {
    data object WorldRoot : SavedDataScope

    data class Dimension(
        val dimensionId: DimensionId,
    ) : SavedDataScope
}

/** Stateless path, compression-detection, and direct-write policy for saved data. */
class SavedDataStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val savedDataScope: SavedDataScope,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    fun readDocument(savedDataId: SavedDataId): NbtDocument? =
        read(savedDataId, nbtFileStore.nbtFormat::decodeDocumentFromOkio)

    fun <T> read(savedDataId: SavedDataId, deserializationStrategy: DeserializationStrategy<T>): T? =
        read(savedDataId) { source ->
            nbtFileStore.nbtFormat.decodeFromOkio(source, deserializationStrategy)
        }

    inline fun <reified T> read(savedDataId: SavedDataId): T? =
        read(savedDataId, nbtFileStore.nbtFormat.serializersModule.serializer())

    /** Detects compression and lends the complete decompressed stream through one physical file open. */
    fun <T> read(savedDataId: SavedDataId, block: (BufferedSource) -> T): T? {
        val path = minecraftWorldPaths.savedData(savedDataId, savedDataScope)
        return nbtFileStore.readDetectingCompressionOrNull(path, ::detectCompression, block)
    }

    fun writeDocument(savedDataId: SavedDataId, nbtDocument: NbtDocument) {
        nbtFileStore.writeDocument(minecraftWorldPaths.savedData(savedDataId, savedDataScope), nbtDocument)
    }

    fun <T> write(savedDataId: SavedDataId, value: T, serializationStrategy: SerializationStrategy<T>) =
        nbtFileStore.write(
            minecraftWorldPaths.savedData(savedDataId, savedDataScope),
            value,
            serializationStrategy = serializationStrategy,
        )

    inline fun <reified T> write(savedDataId: SavedDataId, value: T) =
        write(savedDataId, value, nbtFileStore.nbtFormat.serializersModule.serializer())

    fun write(savedDataId: SavedDataId, block: (BufferedSink) -> Unit) {
        nbtFileStore.write(minecraftWorldPaths.savedData(savedDataId, savedDataScope), block = block)
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
