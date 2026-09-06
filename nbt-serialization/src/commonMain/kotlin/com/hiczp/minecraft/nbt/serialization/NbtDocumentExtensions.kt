package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

/** Decodes this generic NBT tree with a caller-selected serializer. */
fun <T> NbtDocument.decodeNbt(
    nbtFormat: NbtFormat = NbtFormat,
    deserializationStrategy: DeserializationStrategy<T>,
): T = nbtFormat.decodeFromNbtTag(root, deserializationStrategy)

/** Decodes this generic NBT tree with the serializer selected from [nbtFormat]. */
inline fun <reified T> NbtDocument.decodeNbt(nbtFormat: NbtFormat = NbtFormat): T =
    decodeNbt(nbtFormat, nbtFormat.serializersModule.serializer())

/** Writes this complete unnamed-root binary NBT document without closing [sink]. */
fun NbtDocument.writeTo(sink: Sink, nbtFormat: NbtFormat = NbtFormat) {
    nbtFormat.encodeDocumentToSink(this, sink)
}
