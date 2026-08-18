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
  methods wrap those paths; compressed byte arrays remain only where they are the value owned by `RegionChunk`.
- Region chunk composition delegates compound-document bytes to `nbt-serialization` and exposes NBT model values from
  `nbt`; it does not duplicate their grammar.
- Chunk-NBT encoders select compression per operation. `RegionFileFormat` receives already-compressed `RegionChunk`
  values, records each chunk's own registration, and never chooses or changes their compression.
- Callers can inspect or repack a region without inflating preserved compressed payloads.
- Sector, version, compression, checksum, and external-chunk behavior match the selected official server.
- Parsing rejects overlaps, truncation, overflow, invalid versions, checksum failures, and intrinsic framing or field
  length violations. It does not impose a policy-sized region, chunk, or decompressed-output ceiling.
- Custom compression stays injectable through the public registry; built-in machinery remains private.
- Raw compression and checksums always come from maintained libraries. Shared code may own the vanilla LZ4Block
  container and framing validation, but never a raw codec or checksum algorithm.
- Compression and Anvil/NBT physical-format APIs expose only `kotlinx.io` streams. Structural container failures use the
  I/O-independent `RegionFormatException`; backend/stream I/O remains `kotlinx.io.IOException`, while NBT, cancellation,
  and registered CUSTOM-codec failures propagate according to their owning layer.
- Published targets are JVM, Android, the configured Native platforms, JS Node/browser, and WasmJS Node/browser.
  Wasm/WASI and the WasmJS D8 runtime are intentionally absent; do not remove any other target.

## Verification

Run `:world-format:jvmTest` first. Compression changes also require `:world-format:jsNodeTest`,
`:world-format:wasmJsNodeTest`, and the host Native test; a region-wire change also requires `:world-io:jvmTest`.
