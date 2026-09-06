# nbt-serialization

Portable Java Edition binary NBT and stringified NBT (SNBT) formats over the standalone model from
[`nbt`](../nbt/README.md). `NbtFormat` implements `BinaryFormat`; `SnbtFormat` implements `StringFormat`.

## Binary NBT

`NbtFormat` converts serializable classes to NBT trees and reads or writes binary NBT. Generic serialization uses the
configured `NbtRootEncoding`; explicit methods cover any-tag, named-tag, unnamed-tag, and compound-document roots. Its
caller-owned `kotlinx.io.Source`/`Sink` methods are the canonical binary path. A `kotlinx.io.Buffer` implements both,
so this example has no external stream setup. The application defines the serializable value:

```kotlin
@Serializable
data class MyValue(val counter: Int)

val myValue = MyValue(counter = 7)
val unnamedNbtFormat = NbtFormat(
    NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
)
val binaryBuffer = Buffer()
unnamedNbtFormat.encodeToSink(myValue, binaryBuffer)
val decodedMyValue = unnamedNbtFormat.decodeFromSource<MyValue>(binaryBuffer)
check(decodedMyValue == myValue)
```

The reified tree and stream extensions use the executing format's serializer module. Explicit-strategy overloads keep
the same arguments and append the strategy, for example
`unnamedNbtFormat.encodeToSink(myValue, binaryBuffer, MyValue.serializer())` or
`unnamedNbtFormat.decodeFromSource(binaryBuffer, MyValue.serializer())`. The inherited `BinaryFormat` byte-array and
`StringFormat` string methods keep kotlinx.serialization's strategy-first signatures.

For real streams, the caller opens, flushes and closes its endpoints. The format consumes or writes one value. Explicit
tag and document operations use the same streaming boundary:

```kotlin
val nbtTag = NbtInt(7)
val packetBuffer = Buffer()
NbtFormat.encodeAnyTagToSink(nbtTag, packetBuffer)
check(NbtFormat.decodeAnyTagFromSource(packetBuffer) == nbtTag)

val nbtDocument = NbtDocument(NbtCompound(mapOf("counter" to nbtTag)))
val worldBuffer = Buffer()
NbtFormat.encodeDocumentToSink(nbtDocument, worldBuffer)
check(NbtFormat.decodeDocumentFromSource(worldBuffer) == nbtDocument)
```

When a tree is already in hand, receiver extensions keep conversion discoverable. `decodeNbt` below reads the document
just constructed; `writeTo` delegates to document encoding and borrows its sink. Use the byte-array adapter only when a
complete byte value is required:

```kotlin
check(nbtDocument.decodeNbt<MyValue>() == myValue)
check(nbtDocument.decodeNbt(unnamedNbtFormat, MyValue.serializer()) == myValue)
nbtDocument.writeTo(worldBuffer)
val worldNbtBytes = NbtFormat.encodeDocumentToByteArray(nbtDocument)
check(NbtFormat.decodeDocumentFromByteArray(worldNbtBytes) == nbtDocument)
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
complete output text, flushes, or closes the stream. Reuse `nbtTag` and `nbtDocument` from the binary examples with a
fresh text buffer:

```kotlin
val textBuffer = Buffer()
SnbtFormat.encodeTagToSink(nbtTag, textBuffer)
check(SnbtFormat.decodeTagFromSource(textBuffer) == nbtTag)

val snbtString = nbtTag.toSnbtString()
check(snbtString.toNbtTag() == nbtTag)

val documentSnbt = nbtDocument.toSnbtString()
check(documentSnbt.toNbtDocument() == nbtDocument)
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
