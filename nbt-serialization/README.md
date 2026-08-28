# nbt-serialization

Portable Java Edition binary NBT and stringified NBT (SNBT) formats over the standalone model from
[`nbt`](../nbt/README.md). `NbtFormat` implements `BinaryFormat`; `SnbtFormat` implements `StringFormat`.

## Binary NBT

`NbtFormat` converts serializable classes to NBT trees and reads or writes binary NBT. Generic serialization uses the
configured `NbtRootEncoding`; explicit methods cover any-tag, named-tag, unnamed-tag, and compound-document roots. Its
caller-owned `kotlinx.io` `Source`/`Sink` methods are the canonical binary path. Here `myValue` is a caller-provided
serializable `MyValue`, while `sink` and `source` are the caller-owned binary endpoints:

```kotlin
val unnamedNbtFormat = NbtFormat(
    NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
)

// Encode directly to a caller-owned stream; NbtFormat does not flush or close it.
unnamedNbtFormat.encodeToSink(myValue, sink)
sink.flush()

// Decode directly from a caller-owned stream without first making a ByteArray.
val decodedMyValue = unnamedNbtFormat.decodeFromSource<MyValue>(source)
```

The explicit tag and document entry points are streaming too. In this block, `nbtTag` is a caller-constructed `NbtTag`
and `nbtDocument` is a caller-constructed `NbtDocument`. Each `...Source` or `...Sink` is the endpoint owned by the
packet or world layer named in that variable:

```kotlin
NbtFormat.encodeAnyTagToSink(nbtTag, packetSink)
val packetNbtTag = NbtFormat.decodeAnyTagFromSource(packetSource)

NbtFormat.encodeDocumentToSink(nbtDocument, worldSink)
val worldNbtDocument = NbtFormat.decodeDocumentFromSource(worldSource)

// Use an in-memory adapter only when a complete byte value is actually needed.
val worldNbtBytes = NbtFormat.encodeDocumentToByteArray(nbtDocument)
```

When a generic tree is already in hand, receiver extensions keep the next operations discoverable without moving the
physical format into the logical `nbt` module. The `nbtDocument` and `worldSink` values come from the preceding
examples; tree decoding does not depend on binary root framing:

```kotlin
nbtDocument.writeTo(worldSink)
val decodedMyValue = nbtDocument.decodeNbt<MyValue>()
```

The format does not impose policy-sized byte, collection, array, or nesting limits. Stream methods process binary input
and output incrementally; tree and byte-array methods necessarily retain the value they return. The unsigned-short
length of Java modified UTF remains part of the NBT binary format itself.

`NbtBinaryFormatException` identifies intrinsic binary corruption such as unknown tag IDs, truncation, invalid lengths,
malformed modified UTF, or an invalid document root. It is an `NbtDecodingException`; serializer/model mapping failures
remain the broader type so filesystem recovery code can distinguish bad bytes from an incompatible requested schema
without prebuilding an NBT tree.

## Kotlin value mapping

Classes and `Map<String, T>` values become compounds. Mixed logical lists use the compound-wrapper convention of the
repository-selected Minecraft release. Byte, int, and long arrays use their specialized tags, and enums use serial
names.
`Char`, non-string map keys, polymorphism, null roots, and null collection values are rejected; null compound properties
are omitted.

## SNBT

`SnbtFormat` uses the same `NbtTag` tree and Kotlin mapping. Its `Source` decoder reads UTF-8 incrementally and requires
one complete value plus optional trailing whitespace. Its `Sink` writer traverses tags directly and never builds the
complete output text, flushes, or closes the stream. The `nbtTag`, `nbtDocument`, `sink`, and `source` names refer to
the caller-owned values and endpoints described in the binary examples above:

```kotlin
SnbtFormat.encodeTagToSink(nbtTag, sink)
val streamedNbtTag = SnbtFormat.decodeTagFromSource(source)

val snbtString = nbtTag.toSnbtString()
val parsedNbtTag = snbtString.toNbtTag()

val documentSnbt = nbtDocument.toSnbtString()
val parsedNbtDocument = documentSnbt.toNbtDocument()
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
