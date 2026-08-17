# nbt-serialization

Portable Java Edition binary NBT and a `kotlinx.serialization` `BinaryFormat` over the standalone model from
[`nbt`](../nbt/README.md).

`NbtFormat` converts serializable classes to NBT trees and reads or writes binary NBT. It supports the explicit root
forms used by packets and world files (any/named/unnamed tag, or a compound-root document). Its caller-owned
`kotlinx.io` `Source`/`Sink` methods are the canonical binary path:

```kotlin
val unnamedNbt = NbtFormat(
    NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
)

// Encode directly to a caller-owned stream; NbtFormat does not flush or close it.
unnamedNbt.encodeToSink(MyValue.serializer(), value, sink)
sink.flush()

// Decode directly from a caller-owned stream without first making a ByteArray.
val decoded = unnamedNbt.decodeFromSource(MyValue.serializer(), source)
```

The explicit tag and document entry points are streaming too:

```kotlin
NbtFormat.encodeAnyTagToSink(tag, packetSink)
val packetTag = NbtFormat.decodeAnyTagFromSource(packetSource)

NbtFormat.encodeDocumentToSink(document, worldSink)
val worldDocument = NbtFormat.decodeDocumentFromSource(worldSource)

// Use an in-memory adapter only when a complete byte value is actually needed.
val worldBytes = NbtFormat.encodeDocumentToByteArray(document)
```

The format does not impose policy-sized byte, collection, array, or nesting limits. Stream methods process binary input
and output incrementally; tree and byte-array methods necessarily retain the value they return. The unsigned-short
length of Java modified UTF remains part of the NBT binary format itself.

Classes and `Map<String, T>` values become compounds. Mixed logical lists use the compound-wrapper convention of the
project-selected Minecraft release. Byte, int, and long arrays use their specialized tags, and enums use serial names.
`Char`, non-string map keys, polymorphism, null roots, and null collection values are rejected; null compound properties
are omitted.
