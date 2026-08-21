# world-format

This module owns the selected-release `level.dat`, advancement, and statistics models and serializers, plus
filesystem-independent Anvil coordinates, region headers and sectors, compression dispatch, external chunk
representation, and NBT composition.

## Invariants

- Container parsing remains separate from filesystem access, decompression, and NBT decoding.
- `LevelDat`, `PlayerAdvancements`, and `PlayerStatistics` describe only the repository-selected release. Audit their
  official writer, reader, codec, generated file, nested types, field names/types, nullability, defaults, and dynamic
  boundaries on every release update; do not retain an old-schema branch or add an implicit DataFixer.
- Fixed structures use generated serializers. The advancement root is the sole expected custom serializer because its
  JSON object mixes `DataVersion` with dynamic advancement identifiers; it consumes map composite events directly and
  never materializes a JSON tree.
- Typed decoding is strict about unknown fields by default. Raw `NbtTag`/`NbtDocument` and `JsonElement` remain the
  lossless path for modded, future, or otherwise unmodeled content.
- Region, compression, and chunk-NBT stream methods are canonical and never close caller-owned endpoints. In-memory
  methods wrap those paths; compressed byte arrays remain only where they are the value owned by `CompressedChunk`.
- Detached Chunk representations expose receiver-oriented conversion extensions in this module: compressed content,
  generic `NbtDocument`, and semantic `Chunk` can continue to the other representations through IDE completion. Keep
  those methods as thin adapters over the canonical formats/codecs, and use extensions where adding a member would
  reverse the `nbt` -> `world-format` dependency.
- Region chunk composition delegates compound-document bytes to `nbt-serialization` and exposes NBT model values from
  `nbt`; it does not duplicate their grammar.
- Strong Chunk conversion accepts caller-supplied block-state, biome, and dimension-layout data. This module does not
  depend on protocol or vanilla-data modules; unresolved logical values are Chunk projection errors, not unknown NBT.
- Chunk-NBT encoders select compression per operation. `AnvilRegionFormat` receives already-compressed
  `AnvilChunkRecord` values, records each chunk's own registration, and never chooses or changes their compression.
- Callers can inspect or repack a region without inflating preserved compressed payloads.
- `MinecraftCoordinates` owns every scalar and typed absolute/local conversion, continuous-position flooring, checked
  inverse, coverage range, and coordinate sequence. Convenience properties and functions on `BlockPosition`,
  `SectionPosition`, `ChunkPosition`, and `RegionPosition` delegate to it. Preserve floor semantics for negative
  coordinates and keep higher layers dependent on these canonical conversions rather than duplicating arithmetic.
- Positionless `Chunk` and `ChunkSection` expose both local block/biome operations and absolute overloads that receive
  the owning `ChunkPosition` or `SectionPosition`. Absolute overloads validate and convert through the coordinate types,
  then delegate to the local operation; they never store parent coordinates in the semantic value.
- Palette mutation preserves stable IDs by default. `compactSnapshot()` publicly exposes a non-mutating compact view,
  `compact()` explicitly applies it in place, and encoding uses the snapshot path without mutating the semantic Chunk.
  Cross-format adapters use `PalettedContainer.fromPalette` instead of creating a temporary dense value list.
- Sector, version, compression, checksum, and external-chunk behavior match the selected official server.
- Parsing rejects overlaps, truncation, overflow, invalid versions, checksum failures, and intrinsic framing or field
  length violations. It does not impose a policy-sized region, chunk, or decompressed-output ceiling.
- Custom compression stays injectable through the public registry; built-in machinery remains private.
- Raw compression and checksums always come from maintained libraries. Shared code may own the vanilla LZ4Block
  container and framing validation, but never a raw codec or checksum algorithm.
- Compression and Anvil/NBT physical-format APIs expose only `kotlinx.io` streams. Structural container failures use the
  I/O-independent `AnvilFormatException`; backend/stream I/O remains `kotlinx.io.IOException`, while NBT, cancellation,
  and registered CUSTOM-codec failures propagate according to their owning layer.
- Published targets are JVM, Android, the configured Native platforms, JS Node/browser, and WasmJS Node/browser.
  Wasm/WASI and the WasmJS D8 runtime are intentionally absent; do not remove any other target.

## Verification

Run `:world-format:jvmTest` first. Compression changes also require `:world-format:jsNodeTest`,
`:world-format:wasmJsNodeTest`, and the host Native test; a region-wire change also requires `:world-io:jvmTest`.
