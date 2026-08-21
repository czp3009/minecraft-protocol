# nbt-serialization

Portable Java Edition binary NBT and stringified NBT (SNBT) formats over the standalone model from
[`nbt`](../nbt/README.md). `NbtFormat` implements `BinaryFormat`; `SnbtFormat` implements `StringFormat`.

`NbtFormat` converts serializable classes to NBT trees and reads or writes binary NBT. It supports the explicit root
forms used by packets and world files (any/named/unnamed tag, or a compound-root document). Its caller-owned
`kotlinx.io` `Source`/`Sink` methods are the canonical binary path:

```kotlin
val unnamedNbt = NbtFormat(
    NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
)

// Encode directly to a caller-owned stream; NbtFormat does not flush or close it.
unnamedNbt.encodeToSink(value, sink)
sink.flush()

// Decode directly from a caller-owned stream without first making a ByteArray.
val decoded = unnamedNbt.decodeFromSource<MyValue>(source)
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

When a generic tree is already in hand, receiver extensions keep the next operations discoverable without moving the
physical format into the logical `nbt` module:

```kotlin
document.writeTo(worldSink)
val decodedDocumentValue = document.decodeNbt<MyValue>(unnamedNbt)
```

The format does not impose policy-sized byte, collection, array, or nesting limits. Stream methods process binary input
and output incrementally; tree and byte-array methods necessarily retain the value they return. The unsigned-short
length of Java modified UTF remains part of the NBT binary format itself.

Classes and `Map<String, T>` values become compounds. Mixed logical lists use the compound-wrapper convention of the
project-selected Minecraft release. Byte, int, and long arrays use their specialized tags, and enums use serial names.
`Char`, non-string map keys, polymorphism, null roots, and null collection values are rejected; null compound properties
are omitted.

`SnbtFormat` uses the same `NbtTag` tree and Kotlin mapping. Its `Source` decoder reads UTF-8 incrementally and requires
one complete value plus optional trailing whitespace. Its `Sink` writer traverses tags directly and never builds the
complete output text, flushes, or closes the stream:

```kotlin
SnbtFormat.encodeTagToSink(tag, sink)
val streamedTag = SnbtFormat.decodeTagFromSource(source)

val text = tag.toSnbtString()
val parsedTag = text.toNbtTag()

val documentText = document.toSnbtString()
val parsedDocument = documentText.toNbtDocument()
```

Generic `encodeToString`/`decodeFromString` and `encodeToSink`/`decodeFromSource` calls reuse the module's existing NBT
tree mapping for serializable Kotlin types. Direct tag streaming avoids a complete intermediate `String`; the returned
NBT tree itself is necessarily retained. Compound keys are sorted by default to match vanilla's compact writer, with
insertion-order output available through `SnbtFormatConfiguration(sortCompoundKeys = false)`.

The selected-release grammar includes heterogeneous lists, typed arrays, binary/hexadecimal/underscored numbers,
case-insensitive booleans, `bool(...)`, `uuid(...)`, trailing commas, and numeric string escapes. Kotlin Multiplatform
does not provide a common Unicode character-name database, so the official `\N{name}` escape requires an optional
`SnbtUnicodeNameResolver`; the writer emits portable numeric/control escapes instead. Empty compound keys, `TAG_End`,
and non-finite floating-point values are rejected because the selected-release parser cannot round-trip them.
