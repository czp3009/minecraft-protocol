# world-format

Filesystem-independent Minecraft world storage formats and selected-release structured-file models. The legacy `.mcr`
Region format and its conversion to Anvil are intentionally out of scope.

The module provides three independently usable capabilities:

- **Anvil region containers** model absolute, region, and local chunk coordinates; parse and pack region headers and
  sectors; preserve compressed payloads; and represent external `.mcc` chunks explicitly.
- **Compression streams** wrap bytes behind the GZIP, ZLIB, NONE, and LZ4 registrations, plus caller-registered custom
  codecs. The same registry decodes a chunk payload inside a region file and a standalone compressed NBT document such
  as `level.dat`.
- **Structured world-file models** describe the repository-selected release's `level.dat`, player advancements, and
  player statistics through ordinary `kotlinx.serialization` serializers.

NBT values come from [`nbt`](../nbt/README.md); document bytes are delegated to
[`nbt-serialization`](../nbt-serialization/README.md).

## Selected-release structured files

`LevelDat`, `PlayerAdvancements`, and `PlayerStatistics` are the recommended strongly typed roots for the three common
standalone files. They model only the repository-selected Minecraft release and do not perform historical migration or
rewrite `DataVersion`.

The models do not create separate formats. Use `NbtFormat` configured for `NbtRootEncoding.UNNAMED` for level data and
`Json` for the JSON models:

```kotlin
val levelNbt = NbtFormat(
  NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
)
val level = levelNbt.decodeFromSource<LevelDat>(nbtSource)
levelNbt.encodeToSink(level, nbtSink)

val advancements = Json.decodeFromSource<PlayerAdvancements>(jsonSource)
Json.encodeToSink(advancements, jsonSink)
```

Dynamic stat and advancement identifiers remain maps. Decoders reject unknown fields by default; use `NbtDocument`,
`NbtTag`, or `JsonElement` when modded or future fields must survive a round trip.

## Compressed unnamed-root NBT data

`level.dat`, player data, and saved-data files are all one complete unnamed-root compound NBT stream behind a
compression wrapper — GZIP for `level.dat`, GZIP or NONE for saved data. This module owns the wrappers; compose them
with `NbtFormat` to read and write the data itself, independently of any filesystem:

```kotlin
// Transfer arbitrary bytes without constructing an intermediate ByteArray.
CompressionCodecs.decompressToSink(Compression.GZIP, compressedSource, plainSink)
CompressionCodecs.compressToSink(Compression.GZIP, plainSource, compressedSink)

// Decode one compressed NBT stream, for example the contents of level.dat.
val document = CompressionCodecs.decompressingSource(
    compression = Compression.GZIP,
    source = source,
).buffered().use {
    NbtFormat.decodeDocumentFromSource(it)
}

// Encode directly through the compression stream.
CompressionCodecs.compressingSink(Compression.GZIP, sink).buffered().use {
    NbtFormat.encodeDocumentToSink(document, it)
}
```

The decorators never close the caller-owned `source` or `sink`; closing a compressing decorator is still required
because it emits the stream terminator. For region-chunk payloads specifically, `RegionChunkNbtFormat` composes the same
registry into `RegionChunk` values with per-chunk compression and timestamps.

Use its stream methods when the compressed payload itself does not need to become a `RegionChunk` byte array:

```kotlin
val chunkNbt = RegionChunkNbtFormat()
val document = chunkNbt.decodeFromSource(compressedChunkSource, Compression.ZLIB)
chunkNbt.encodeToSink(document, Compression.ZLIB, compressedChunkSink)
compressedChunkSink.flush()
```

## Anvil region containers

Coordinate conversion is public, portable, and independent of filesystem access. A chunk exposes both its containing
region and its local position; combining those values restores the original coordinate. A region can also enumerate all
1,024 covered positions in Anvil header-index order:

```kotlin
val chunk = ChunkPosition(-33, 63)
val region = chunk.region
val local = chunk.local
check(chunk in region)
check(region.local(chunk) == local)
check(region.chunk(local) == chunk)

region.chunkPositions().forEach { position ->
  collectChunk(position)
}
```

`RegionPosition.local(chunk)` is the checked absolute-to-local conversion and throws `IllegalArgumentException` when the
Chunk belongs to another Region. `ChunkPosition.local` is convenient when the containing Region is already implied;
`RegionPosition.chunk(local)` performs the inverse conversion correctly for positive and negative coordinates.

`chunkPositions()` describes the region's coordinate coverage, not which chunks are actually present in an `.mca`.

`RegionFileFormat` is the whole-file codec: decode one complete `.mca` image, read or replace chunks without inflating
unrelated payloads, and re-encode. Oversized chunks become external `.mcc` payloads automatically:

```kotlin
val chunkNbt = RegionChunkNbtFormat()

val region = RegionFileFormat.decodeFromSource(source)
val local = ChunkPosition(x, z).local
val document = region[local]?.let { chunkNbt.decode(it) }

val updated = chunkNbt.encode(
  document = editedDocument,
  compression = Compression.ZLIB,
  timestamp = epochSeconds,
)
val updatedRegion = region.copy(
  chunks = region.chunks + (local to updated),
)

// Stream the complete .mca image. The returned values are payloads that belong
// in c.<x>.<z>.mcc sidecars; world-format deliberately does not open paths.
val externalChunks = RegionFileFormat.encodeToSink(updatedRegion, encodedRegionSink)
encodedRegionSink.flush()
```

For a large `.mca`, process compressed inline payloads one at a time without constructing a `RegionFile` tree:

```kotlin
RegionFileFormat.readChunksFromSource(source) { info, compressedPayload ->
  consumeChunk(info.position, info.compression, compressedPayload)
}
```

The callback must consume each lent payload before returning. An external marker has an empty inline payload; filesystem
code resolves its `.mcc` sidecar separately.

Each `RegionChunk` carries its own compression registration, so one `RegionFile` may preserve mixed registrations
without inflating any payload. For in-place updates that never rewrite the whole file, the same container is also
exposed as incremental primitives — `RegionHeader`, `RegionLocation`, `RegionSectorAllocator`, and
`EncodedRegionChunkRecord` — which is the layer [`world-io`](../world-io/README.md) builds its positional region stores
on. Wire compression IDs belong to the record header (`RegionChunkRecordHeader.compressionId`), not to the `Compression`
values used by standalone files.

## Details

Entry points expose only `kotlinx.io` sources and sinks and never close caller-owned endpoints; byte-array and
`RegionChunk` methods wrap the streaming paths. The module does not open paths or impose a typed, version-specific chunk
schema or policy-sized resource ceilings; filesystem-level defaults belong to [`world-io`](../world-io/README.md).
Intrinsic Anvil location fields, sector counts, record lengths, and the modified-UTF field used by NBT remain bounded by
their binary representations.

Structural Anvil or compression-container errors throw `RegionFormatException`, while stream access and
compression-backend failures surface as `kotlinx.io.IOException`. NBT grammar and serialization failures retain their
`nbt-serialization` exception types.
