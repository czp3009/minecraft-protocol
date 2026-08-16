# world-format

Filesystem-independent Minecraft Anvil `.mca` region support. The legacy `.mcr` Region format and its conversion to
Anvil are intentionally out of scope.

The module models absolute, region, and local chunk coordinates; parses and packs region headers and sectors; preserves
compressed payloads; represents external `.mcc` chunks explicitly; and composes compression with compound-document NBT
when requested. Each `RegionChunk` carries its own compression registration, so one `RegionFile` may preserve mixed
registrations without inflating any payload. All vanilla compression registrations (GZIP, ZLIB, NONE, LZ4) are
supported, and custom compression can be supplied through `RegionCompressionCodecs`. NBT values come from
[`nbt`](../nbt/README.md); document bytes are delegated to [`nbt-serialization`](../nbt-serialization/README.md).

```kotlin
val region = RegionFileFormat.decodeFromSource(source)
val chunk = region[ChunkPosition(x, z).local]
val document = chunk?.let { RegionChunkNbtFormat().decode(it) }

RegionFileFormat.encodeToSink(region, sink)
RegionChunkNbtFormat().encodeToSink(updatedDocument, RegionCompression.LZ4, chunkSink)
```

Entry points expose only `kotlinx.io` sources and sinks and never close caller-owned endpoints; byte-array and
`RegionChunk` methods wrap the streaming paths. The module does not open paths or impose a typed, version-specific chunk
schema; filesystem-level defaults belong to [`world-io`](../world-io/README.md).

Structural Anvil or compression-container errors throw `RegionFormatException`, while stream access and
compression-backend failures surface as `kotlinx.io.IOException`. NBT grammar and serialization failures retain their
`nbt-serialization` exception types.
