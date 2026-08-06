# world-format

Filesystem-independent Minecraft Anvil `.mca` region support. The legacy `.mcr` Region format and its conversion to
Anvil are intentionally out of scope.

The module models absolute, region, and local chunk coordinates; parses and packs region headers and sectors; preserves
compressed payloads; represents external `.mcc` chunks explicitly; and composes compression with compound-document NBT
when requested. NBT values come from `nbt`, while document bytes are delegated to `nbt-serialization`.

All vanilla region compression registrations are supported. LZ4 uses the legacy lz4-java block stream used by the
official server, not the standard LZ4 frame format. Custom compression can be supplied through
`RegionCompressionCodecs`.

```kotlin
val region = RegionFileFormat.decodeFromSource(source)
val chunk = region[ChunkPosition(x, z).local]
val document = chunk?.let { RegionChunkNbtFormat().decode(it) }

RegionFileFormat.encodeToSink(region, sink)
RegionChunkNbtFormat().encodeToSink(updatedDocument, RegionCompression.ZLIB, chunkSink)
```

These stream methods never close caller-owned endpoints. Byte-array and `RegionChunk` methods wrap the streaming paths;
compressed arrays remain in `RegionChunk` because they are the model's preserved value. The module does not open paths
or impose a typed, version-specific chunk schema.
