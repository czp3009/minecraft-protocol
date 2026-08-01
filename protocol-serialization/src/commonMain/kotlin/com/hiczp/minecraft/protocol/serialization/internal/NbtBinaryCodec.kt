package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.nbt.NbtBinaryFormat
import com.hiczp.minecraft.nbt.NbtBinaryFormatConfiguration
import com.hiczp.minecraft.nbt.NbtFormatException
import com.hiczp.minecraft.protocol.model.type.NbtTag
import com.hiczp.minecraft.protocol.serialization.MinecraftFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.io.Buffer

/**
 * Packet NBT is the unnamed binary-NBT representation.
 *
 * The standalone NBT module owns the wire grammar, including Java modified
 * UTF-8. This adapter only bridges it to the packet encoder's internal buffer.
 */
internal class NbtBinaryCodec(
    private val configuration: MinecraftFormatConfiguration,
) {
    fun writeUnnamed(writer: MinecraftWriter, tag: NbtTag) {
        try {
            writer.write(format(Long.MAX_VALUE).encodeTagToByteArray(tag))
        } catch (exception: NbtFormatException) {
            throw MinecraftSerializationException(
                "Cannot encode packet NBT: ${exception.message}",
                exception,
            )
        }
    }

    fun readUnnamed(reader: MinecraftReader): NbtTag {
        val buffer = Buffer()
        buffer.write(reader.remainingBytes())
        val initialSize = buffer.size
        try {
            val tag = format(initialSize).decodeTag(buffer)
            reader.skip((initialSize - buffer.size).toInt())
            return tag
        } catch (exception: NbtFormatException) {
            throw MinecraftSerializationException(
                "Cannot decode packet NBT: ${exception.message}",
                exception,
            )
        }
    }

    private fun format(maximumEncodedBytes: Long): NbtBinaryFormat =
        NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumDepth = configuration.maximumNbtDepth,
                maximumCollectionSize = configuration.maximumCollectionSize,
                maximumByteArraySize = configuration.maximumByteArraySize,
                maximumEncodedBytes = maximumEncodedBytes,
            ),
        )
}
