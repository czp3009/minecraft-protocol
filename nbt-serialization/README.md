# nbt-serialization

Portable Java Edition binary NBT and a `kotlinx.serialization` `BinaryFormat` over the standalone model from
[`nbt`](../nbt/README.md).

`NbtFormat` converts serializable classes to NBT trees and reads or writes binary NBT. It supports the explicit root
forms used by packets and world files (any/named/unnamed tag, or a compound-root document), caller-owned `kotlinx.io`
`Source`/`Sink` methods, and configurable resource limits:

```kotlin
NbtFormat.encodeToSink(MyValue.serializer(), value, sink)
val decoded = NbtFormat.decodeFromSource(MyValue.serializer(), source)

val tag = NbtFormat.encodeToNbtTag(MyValue.serializer(), value)
NbtFormat.encodeAnyTagToSink(tag, packetSink)

val document = NbtDocument(tag as NbtCompound)
NbtFormat.encodeDocumentToSink(document, worldSink)

// Byte-array methods are in-memory adapters over the stream methods.
val worldBytes = NbtFormat.encodeDocumentToByteArray(document)
```

Classes and `Map<String, T>` values become compounds. Mixed logical lists use the compound-wrapper convention of the
project-selected Minecraft release. Byte, int, and long arrays use their specialized tags, and enums use serial names.
`Char`, non-string map keys, polymorphism, null roots, and null collection values are rejected; null compound properties
are omitted.
