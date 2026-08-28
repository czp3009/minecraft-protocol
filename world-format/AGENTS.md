# world-format

This module owns filesystem-independent world schemas, data-pack formats, Anvil containers and compression, coordinate
conversion, and semantic Chunk/entity values for the repository-selected release.

## Structured world data

- `MinecraftWorldFormat.WORLD_VERSION` is generated from the matching official server's `version.json`; serialized NBT
  fields remain named `DataVersion`. Do not hand-copy this value or depend on `protocol-model` to obtain it.
- Treat persisted `DataVersion` as content. Semantic codecs read and retain it without comparing it to a selected or
  caller-supplied version; applications own compatibility preflight and migration policy.
- `LevelDat`, `PlayerData`, dimension saved data, advancements, and statistics model only the selected release. Audit
  official reader/writer behavior on release updates; do not keep old-schema branches or add an implicit DataFixer.
- Fixed structures use generated serializers. The advancement root keeps its custom map-composite serializer because
  dynamic advancement IDs share the object with `DataVersion`.
- Typed decoding is strict about unknown fields by default. Raw `NbtDocument`/`NbtTag` and `JsonElement` are the
  lossless escape hatches for unmodeled content.
- `DataPackArchive` is raw path-to-bytes input, `DataPack` is parsed content, and `DataPackStack`/
  `ResolvedDataPackStack` own priority resolution. These values remain filesystem- and protocol-independent. Decoders
  are caller-extensible and add no policy-sized content limits.
- `WorldDataPackLoadResult` is the detached partial-selection handoff: it retains persisted pack IDs and feature
  configuration plus packs already supplied by a lower reader. Completing it preserves enabled low-to-high priority,
  reports every unavailable ID together, and adds no vanilla-core, filesystem-discovery, or protocol policy.

## Anvil and compression

- Separate region-container parsing from filesystem access, decompression, and NBT decoding. Stream methods are
  canonical and never close caller-owned endpoints.
- `AnvilRegionFormat` receives already-compressed records, preserves each record's compression registration, and does
  not choose compression. Callers can inspect or repack a region without inflating unchanged payloads.
- Reject intrinsic corruption such as overlap, truncation, overflow, invalid Region compression identifiers, and
  checksum failure, but do not impose policy-sized region, chunk, or decompressed-output limits.
- Keep CUSTOM compression injectable through the public registry. Maintained libraries own raw compression and
  checksums; this module owns Minecraft containers and validation.
- Structural failures use `AnvilFormatException`; stream/backend I/O, NBT, cancellation, and custom-codec failures
  retain their owning categories.

## Semantic values and coordinates

- `MinecraftCoordinates` is the canonical implementation for scalar and typed conversions. Preserve floor semantics for
  negative coordinates and checked region membership.
- `ChunkRange` and `RegionRange` are inclusive rectangular coordinate products. `..` preserves endpoint order, `..<`
  excludes the upper corner on both axes, and `enclosing` is the explicit operation that normalizes unordered corners.
  Name scale-reducing conversions `covering...` when their reverse expands to cell boundaries.
- Positioned `Chunk`, `EntityChunk`, `PoiChunk`, and `BlockEntity` retain or receive their absolute coordinates.
  Absolute helpers validate membership and delegate to local operations.
- Strong Chunk conversion requires caller-supplied block-state, biome, and dimension-layout data. Do not depend on
  protocol or vanilla-default modules. Protocol-aware callers obtain the matching adapters from `protocol-datapack`.
- Palette mutation preserves stable IDs. Encoding uses a non-mutating compact snapshot; `compact()` is the explicit
  mutating operation.
- Receiver-oriented conversion extensions connect compressed records, `NbtDocument`, and semantic Chunk, Entity Chunk,
  and POI Chunk values without reversing the `nbt` dependency.

## Verification

Run `:world-format:jvmTest`. Compression changes also require JS Node, WasmJS Node, and host Native tests; region-wire
changes require `:world-io:jvmTest`.
