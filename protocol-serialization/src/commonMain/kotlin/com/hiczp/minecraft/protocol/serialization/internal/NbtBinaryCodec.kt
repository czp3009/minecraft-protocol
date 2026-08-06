package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtSerializationException
import com.hiczp.minecraft.protocol.serialization.MinecraftFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.io.Buffer

/**
 * Packet NBT is the no-name any-tag representation used by `NbtIo.writeAnyTag`.
 *
 * The standalone NBT module owns the wire grammar, including Java modified
 * UTF-8. This adapter only bridges it to the packet encoder's internal buffer.
 */
internal class NbtBinaryCodec(
    private val configuration: MinecraftFormatConfiguration,
) {
    fun writeAny(writer: MinecraftWriter, tag: NbtTag) {
        try {
            writer.write(format(Long.MAX_VALUE).encodeAnyTagToByteArray(tag))
        } catch (exception: NbtSerializationException) {
            throw MinecraftSerializationException(
                "Cannot encode packet NBT: ${exception.message}",
                exception,
            )
        }
    }

    fun readAny(reader: MinecraftReader): NbtTag {
        val buffer = Buffer()
        buffer.write(reader.remainingBytes())
        val initialSize = buffer.size
        try {
            val tag = format(initialSize).decodeAnyTag(buffer)
            reader.skip((initialSize - buffer.size).toInt())
            return tag
        } catch (exception: NbtSerializationException) {
            throw MinecraftSerializationException(
                "Cannot decode packet NBT: ${exception.message}",
                exception,
            )
        }
    }

    private fun format(maximumEncodedBytes: Long): NbtFormat =
        NbtFormat(
            NbtFormatConfiguration(
                maximumDepth = configuration.maximumNbtDepth,
                maximumCollectionSize = configuration.maximumCollectionSize,
                maximumByteArraySize = configuration.maximumByteArraySize,
                maximumEncodedBytes = maximumEncodedBytes,
            ),
        )
}
