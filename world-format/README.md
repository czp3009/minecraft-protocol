# world-format

Filesystem-independent Minecraft Anvil `.mca` region support. The legacy `.mcr` Region format and its conversion to
Anvil are intentionally out of scope.

The module models absolute, region, and local chunk coordinates; parses and packs region headers and sectors; preserves
compressed payloads; represents external `.mcc` chunks explicitly; and composes compression with compound-document NBT
when requested. Each `RegionChunk` carries its own compression registration, so one `RegionFile` may preserve mixed
registrations without inflating any payload. NBT values come from `nbt`, while document bytes are delegated to
`nbt-serialization`.

The matching official dedicated server defaults `region-file-compression` to `deflate`, its name for Anvil compression
ID 2: ZLIB-wrapped DEFLATE. This maps to `RegionCompression.ZLIB`, which is also the default of the in-memory
`RegionChunkNbtFormat.encode` adapter. Setting the official property to `lz4` selects ID 4; callers select the same
format with `RegionCompression.LZ4`. Streaming encode methods continue to require an explicit per-operation selection.

All vanilla region compression registrations are supported. GZIP and ZLIB use Okio behind the official
`kotlinx-io-okio` adapters on JVM, Android, and Native and Kompress's official `kotlinx.io` streaming/codec primitives
on JS and WasmJS. LZ4 uses the official server's legacy lz4-java block
stream, not the standard LZ4 frame format; raw blocks and XXHash32 are delegated to lz4-java, NativeBuilds liblz4 plus
Appmattus cryptohash, or the `lz4-lite` plus `js-xxhash` npm packages. The shared code implements only the LZ4Block
container and validation. Custom compression can be supplied through `RegionCompressionCodecs`.

The module targets JVM, Android, the configured Native platforms, JS Node/browser, and WasmJS Node/browser. It does not
publish Wasm/WASI or a WasmJS D8 runtime because its Web LZ4 backend is an npm module and no extra bundling layer is
maintained solely for D8.

```kotlin
val region = RegionFileFormat.decodeFromSource(source)
val chunk = region[ChunkPosition(x, z).local]
val document = chunk?.let { RegionChunkNbtFormat().decode(it) }

RegionFileFormat.encodeToSink(region, sink)
RegionChunkNbtFormat().encodeToSink(updatedDocument, RegionCompression.LZ4, chunkSink)
```

`RegionCompressionCodec`, `RegionCompressionCodecs`, and the physical Anvil/NBT entry points expose only
`kotlinx.io` sources and sinks. These methods never close caller-owned endpoints. Byte-array and `RegionChunk` methods
wrap the streaming paths; compressed arrays remain in `RegionChunk` because they are the model's preserved value. The
module does not open paths or impose a typed, version-specific chunk schema. `RegionChunkNbtFormat` selects compression
for each encode operation; `RegionFileFormat` only records each already-compressed chunk's registration and never
chooses or changes it. Filesystem-level defaults belong to `world-io`.

## Exception contract

`RegionFormatException` reports structural Anvil or compression-container errors and deliberately does not inherit an
I/O exception. Stream access and compression-backend failures are exposed as `kotlinx.io.IOException`; the Web
implementations translate only Kompress's documented codec exception hierarchy at that platform boundary. NBT grammar
and serialization failures retain their `nbt-serialization` exception types. Standard argument/state exceptions,
coroutine cancellation, and exceptions from a registered CUSTOM codec propagate unchanged. Equivalent failures have a
stable public category across targets, while messages, causes, and stack traces may differ with the platform library.
