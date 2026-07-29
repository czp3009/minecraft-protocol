# world-format

Filesystem-independent Minecraft Anvil region support.

The module models absolute, region, and local chunk coordinates; parses and packs region headers and sectors; preserves
compressed payloads; represents external `.mcc` chunks explicitly; and composes compression with named-root NBT when
requested.

All vanilla region compression registrations are supported. LZ4 uses the legacy lz4-java block stream used by the
official server, not the standard LZ4 frame format. Custom compression can be supplied through
`RegionCompressionCodecs`.

```kotlin
val region = RegionFileFormat.decode(source)
val chunk = region[ChunkPosition(x, z).local]
val document = chunk?.let { RegionChunkNbtFormat().decode(it) }
```

The module does not open paths or impose a typed, version-specific chunk schema. Run
`.\gradlew.bat :world-format:worldFormatLayerTest` for its focused suite.
