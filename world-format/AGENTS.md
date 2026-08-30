# world-format

This module owns filesystem-independent world schemas, data-pack formats, Anvil containers and compression, coordinate
conversion, and semantic Chunk/entity values for the repository-selected release.

## Structured world data

- `MinecraftWorldFormat.WORLD_VERSION` is generated from the matching official server's `version.json`; serialized NBT
  fields remain named `DataVersion`. Do not hand-copy this value or depend on `protocol-model` to obtain it.
- Treat persisted `DataVersion` as content. Semantic codecs read and retain it without comparing it to a selected or
  caller-supplied version; applications own compatibility preflight and migration policy.
- `LevelDat`, `PlayerData`, root/dimension saved data, advancements, and statistics model only the selected release.
  Audit official reader/writer behavior on release updates; do not keep old-schema branches or add an implicit
  DataFixer. Keep the common saved-data envelope and all root/dimension saved-data models in
  `com.hiczp.minecraft.world.format.data`; data-pack models remain in their existing `datapack` package.
- Keep each standalone world-file schema in its own type-named source file; do not group models from unrelated storage
  paths merely because they share a serialization library.
- Fixed structures use generated serializers with schema annotations and defaults. Keep NBT value adapters at the model
  property/type boundary when the official array/list representation differs from the domain type's generated shape; use
  file-level `@UseSerializers` when one model file maps every occurrence of a repeated domain type the same way. The
  advancement root keeps its custom map-composite serializer because dynamic advancement IDs share the object with
  `DataVersion`; annotations cannot flatten a map into that root object.
- Typed decoding is strict about unknown fields by default. Raw `NbtDocument`/`NbtTag` and `JsonElement` are the
  lossless escape hatches for unmodeled content.
- `DataPackArchive` is raw path-to-bytes input, `DataPack` is parsed content, and `DataPackStack`/
  `ResolvedDataPackStack` own priority resolution. These values remain filesystem- and protocol-independent. Decoders
  are caller-extensible.
- `WorldDataPackLoadResult` is the detached partial-selection handoff: it retains persisted pack IDs and feature
  configuration plus packs already supplied by a lower reader. Completing it preserves enabled low-to-high priority,
  reports every unavailable ID together, and adds no vanilla-core, filesystem-discovery, or protocol policy.
- Keep semantic namespaced identities such as `DimensionId`, `DimensionTypeId`, and `SavedDataId` in this module.
  External text enters through their parsing/serialization boundaries; filesystem validation remains in `world-io`.
- `WorldGenSettingsData` retains dimension keys and the reference-or-inline dimension-type holder shape strongly.
  `DimensionTypeLayout` is the shared decoder for layout fields in registry and inline NBT; protocol consumers must not
  duplicate that field extraction.

## Anvil and compression

- Separate region-container parsing from filesystem access, decompression, and NBT decoding. Stream methods are
  canonical and never close caller-owned endpoints.
- `AnvilRegionFormat` receives already-compressed records, preserves each record's compression registration, and does
  not choose compression. Callers can inspect or repack a region without inflating unchanged payloads.
- Reject intrinsic corruption such as overlap, truncation, overflow, invalid Region compression identifiers, and
  checksum failure.
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
- `ChunkDataRegistries` is the reusable mapping stage and `ChunkCodecContext` binds it to one `ChunkLayout`. Keep both
  available to custom codecs without adding protocol identity or filesystem state.
- Keep heightmaps and boundary lighting in common `ChunkMetadata`. Keep fields that exist only in persistent Chunk NBT
  in optional `ChunkStorageMetadata`; packet-derived Chunks must not invent those fields, and persistent encoding must
  reject a Chunk that has none.
- Palette mutation preserves stable IDs. Encoding uses a non-mutating compact snapshot; `compact()` is the explicit
  mutating operation.
- Receiver-oriented conversion extensions connect compressed records, `NbtDocument`, and semantic Chunk, Entity Chunk,
  and POI Chunk values without reversing the `nbt` dependency.

## Verification

Run `:world-format:jvmTest`. Compression changes also require JS Node, WasmJS Node, and host Native tests; region-wire
changes require `:world-io:jvmTest`.
