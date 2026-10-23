package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.type.BitSet
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormatConfiguration
import kotlinx.io.Sink

internal object ByteBitSetCodec {
    fun write(sink: Sink, bitSet: BitSet) {
        val words = bitSet.words
        val byteCount = if (words.isEmpty()) 0 else (words.size - 1) * Long.SIZE_BYTES +
                (Long.SIZE_BITS - words.last().countLeadingZeroBits() + 7) / 8
        sink.writeVarInt(byteCount)
        repeat(byteCount) { index ->
            sink.writeByte((words[index / Long.SIZE_BYTES] ushr (index % Long.SIZE_BYTES * 8)).toByte())
        }
    }

    fun read(
        minecraftReader: MinecraftReader,
        minecraftPacketPayloadFormatConfiguration: MinecraftPacketPayloadFormatConfiguration,
    ): BitSet {
        val byteCount = minecraftReader.readVarInt(minecraftPacketPayloadFormatConfiguration.rejectNonMinimalVarNumbers)
        val bytes = minecraftReader.readBytes(byteCount)
        val words = LongArray(byteCount / Long.SIZE_BYTES + if (byteCount % Long.SIZE_BYTES == 0) 0 else 1)
        bytes.forEachIndexed { index, byte ->
            val wordIndex = index / Long.SIZE_BYTES
            words[wordIndex] = words[wordIndex] or ((byte.toLong() and 0xff) shl (index % Long.SIZE_BYTES * 8))
        }
        return BitSet(words)
    }
}
