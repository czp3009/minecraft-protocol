package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Packet NBT is the no-name any-tag representation used by `NbtIo.writeAnyTag`.
 *
 * The standalone NBT module owns the wire grammar, including Java modified
 * UTF-8. NBT serialization failures propagate through the shared
 * [kotlinx.serialization.SerializationException] hierarchy without wrapping.
 */
internal object NbtBinaryCodec {
    fun writeAny(writer: MinecraftWriter, tag: NbtTag) {
        NbtFormat.encodeAnyTagToSink(tag, writer)
    }

    fun readAny(reader: MinecraftReader): NbtTag {
        val counting = OneByteCountingSource(reader.peekSource())
        val source = counting.buffered()
        val tag = NbtFormat.decodeAnyTagFromSource(source)
        reader.skip(counting.bytesRead)
        return tag
    }
}

/**
 * A one-byte look-ahead adapter prevents the temporary buffered source from
 * consuming bytes after the self-delimiting NBT value. The original packet
 * reader is advanced only after decoding succeeds.
 */
private class OneByteCountingSource(
    private val delegate: Source,
) : RawSource {
    var bytesRead: Int = 0
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0
        val read = delegate.readAtMostTo(sink, 1)
        if (read > 0) bytesRead += read.toInt()
        return read
    }

    override fun close() = Unit
}
