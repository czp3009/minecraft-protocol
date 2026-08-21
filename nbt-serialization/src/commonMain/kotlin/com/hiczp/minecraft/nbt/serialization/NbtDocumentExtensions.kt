package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

/** Decodes this generic NBT tree with a caller-selected serializer. */
fun <T> NbtDocument.decodeNbt(
    deserializer: DeserializationStrategy<T>,
    format: NbtFormat = NbtFormat,
): T = format.decodeFromNbtTag(deserializer, root)

/** Decodes this generic NBT tree with the serializer selected from [format]. */
inline fun <reified T> NbtDocument.decodeNbt(format: NbtFormat = NbtFormat): T =
    decodeNbt(format.serializersModule.serializer(), format)

/** Writes this complete unnamed-root binary NBT document without closing [sink]. */
fun NbtDocument.writeTo(sink: Sink, format: NbtFormat = NbtFormat) {
    format.encodeDocumentToSink(this, sink)
}
