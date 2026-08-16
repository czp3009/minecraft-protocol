# world-format

Filesystem-independent Minecraft world storage formats. The legacy `.mcr` Region format and its conversion to Anvil are
intentionally out of scope.

Two independently usable format families share one compression registry:

- **Anvil region containers** model absolute, region, and local chunk coordinates; parse and pack region headers and
  sectors; preserve compressed payloads; and represent external `.mcc` chunks explicitly.
- **Compression streams** wrap bytes behind the GZIP, ZLIB, NONE, and LZ4 registrations, plus caller-registered custom
  codecs. The same registry decodes a chunk payload inside a region file and a standalone compressed NBT document such
  as `level.dat`.

NBT values come from [`nbt`](../nbt/README.md); document bytes are delegated to
[`nbt-serialization`](../nbt-serialization/README.md).

## Compressed unnamed-root NBT data

`level.dat`, player data, and saved-data files are all one complete unnamed-root compound NBT stream behind a
compression wrapper — GZIP for `level.dat`, GZIP or NONE for saved data. This module owns the wrappers; compose them
with `NbtFormat` to read and write the data itself, independently of any filesystem:

```kotlin
// Read one complete stream, for example the bytes of a level.dat
val document = CompressionCodecs.decompressingSource(
    compression = Compression.GZIP,
    source = source,
    maximumOutputBytes = 256 * 1_048_576,
).buffered().use {
    NbtFormat.decodeDocumentFromSource(it)
}

// Write one complete stream back
CompressionCodecs.compressingSink(Compression.GZIP, sink).buffered().use {
    NbtFormat.encodeDocumentToSink(document, it)
}
```

The decorators never close the caller-owned `source` or `sink`; closing a compressing decorator is still required
because it emits the stream terminator. For region-chunk payloads specifically, `RegionChunkNbtFormat` composes the same
registry into `RegionChunk` values with per-chunk compression and timestamps.

## Anvil region containers

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
val encoded = RegionFileFormat.encodeToByteArray(
  region.copy(chunks = region.chunks + (local to updated)),
)

// encoded.bytes is the complete .mca image
// encoded.externalChunks values are the c.<x>.<z>.mcc payloads for external chunks
```

Each `RegionChunk` carries its own compression registration, so one `RegionFile` may preserve mixed registrations
without inflating any payload. For in-place updates that never rewrite the whole file, the same container is also
exposed as incremental primitives — `RegionHeader`, `RegionLocation`, `RegionSectorAllocator`, and
`EncodedRegionChunkRecord` — which is the layer [`world-io`](../world-io/README.md) builds its positional region stores
on. Wire compression IDs belong to the record header (`RegionChunkRecordHeader.compressionId`), not to the `Compression`
values used by standalone files.

## Details

Entry points expose only `kotlinx.io` sources and sinks and never close caller-owned endpoints; byte-array and
`RegionChunk` methods wrap the streaming paths. The module does not open paths or impose a typed, version-specific chunk
schema; filesystem-level defaults belong to [`world-io`](../world-io/README.md).

Structural Anvil or compression-container errors throw `RegionFormatException`, while stream access and
compression-backend failures surface as `kotlinx.io.IOException`. NBT grammar and serialization failures retain their
`nbt-serialization` exception types.
