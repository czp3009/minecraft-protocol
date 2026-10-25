package com.hiczp.minecraft.demo.nbtreader

import com.hiczp.minecraft.nbt.NamedNbtTag
import com.hiczp.minecraft.nbt.serialization.NbtBinaryFormatException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.*

internal enum class InputCompression { AUTO, NONE, GZIP }

internal data class NbtReadResult(val namedNbtTag: NamedNbtTag, val inputCompression: InputCompression)

/** Owns input streams and preserves the binary root name; compression detection uses bytes, not the file extension. */
internal class NbtReader(private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    fun read(path: Path, inputCompression: InputCompression = InputCompression.AUTO): NbtReadResult =
        fileSystem.source(path).buffer().use { input ->
            val detectedCompression = when (inputCompression) {
                InputCompression.AUTO -> {
                    if (input.request(2) && input.buffer[0] == 0x1f.toByte() && input.buffer[1] == 0x8b.toByte()) {
                        InputCompression.GZIP
                    } else {
                        InputCompression.NONE
                    }
                }

                else -> inputCompression
            }
            val decompressed = if (detectedCompression == InputCompression.GZIP) GzipSource(input) else input
            decompressed.asKotlinxIoRawSource().buffered().use { source ->
                val namedNbtTag = NbtFormat.decodeNamedTagFromSource(source)
                if (!source.exhausted()) throw NbtBinaryFormatException("Trailing bytes after the NBT root")
                NbtReadResult(namedNbtTag, detectedCompression)
            }
        }
}
