# nbt-serialization

Portable Java Edition binary NBT and a `kotlinx.serialization` `BinaryFormat`
over the standalone model from `nbt`.

`nbt-serialization` is independently consumable outside the protocol and world stacks. Its public dependency metadata
contains only `nbt`, `kotlinx-serialization-core`, and `kotlinx-io-core`; protocol, filesystem, compression, and test
fixture modules are not runtime dependencies.

`NbtFormat` provides tree conversion, explicit any/named/unnamed/document binary roots, caller-owned `kotlinx.io.Source`
and `Sink` methods, Java modified UTF, and configurable resource limits. Compression and filesystem policy are composed
by the world modules.

Generic `BinaryFormat` operations use the configured root framing; explicit methods prevent packet and world forms from
being confused:

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

Classes and `Map<String, T>` values become compounds. Mixed logical lists use the compound-wrapper convention from the
selected official Minecraft release. Byte, int, and long arrays use their specialized tags. Enums use serial names;
`Char`, non-string map keys, polymorphism, null roots, and null collection values are rejected. Null compound properties
are omitted.

All public writers are strict. The library intentionally has no equivalent of vanilla's emergency
`writeUnnamedTagWithFallback`, which silently replaces a modified-UTF string that is too long with an empty string.
