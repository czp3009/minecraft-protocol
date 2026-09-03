# world-format

This module owns filesystem-independent world schemas, data-pack formats, Anvil containers and compression, coordinate
conversion, and semantic Chunk/entity values for the repository-selected release.

## Structured world data

- `MinecraftWorldFormat.WORLD_VERSION` is generated from the matching official server's `version.json`; serialized NBT
  fields remain named `DataVersion`. Do not hand-copy this value or depend on `protocol-model` to obtain it.
- Treat persisted `DataVersion` as persistence metadata. Complete standalone-file schemas may own it, but
  representation-independent Chunk, Entity Chunk, and POI Chunk values do not; their NBT decoders return it in the
  corresponding decode result without comparing it to a selected or caller-supplied version. Applications own
  compatibility preflight and migration policy.
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
  configuration plus packs already supplied by a lower reader. Retain an already ordered loaded-pack list by reference;
  normalize only an out-of-order list because enabled low-to-high priority is part of data-pack semantics. Completing it
  reports every unavailable ID together and adds no vanilla-core, filesystem-discovery, or protocol policy.
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
- The computation-facing `Chunk` contract is a fully generated final value; this module does not model `ProtoChunk`
  progression or provide APIs that fill generation-stage data according to status. A disk decoder still exposes a
  persisted nonterminal status so callers can identify, reject, or route that input, but such a value is not a supported
  computation input. Use raw `NbtDocument` when unfinished generation data must be preserved losslessly.
- `ChunkContext` owns the dimension identity/layout and default block-state/biome facts needed by semantic Chunk
  operations. A Chunk exposes its context as a read-only caller convenience; library codecs and stores do not read it.
  Do not depend on protocol or vanilla-default modules to construct this context; protocol-aware callers obtain
  matching adapters from the owning protocol modules.
- Chunk NBT encoding and decoding use separate `ChunkNbtEncoderContext` and `ChunkNbtDecoderContext` values. The decoder
  context contains the `ChunkContext` whose same reference is attached to every decoded Chunk. The encoder receives its
  own `ChunkContext` explicitly and never obtains or cross-validates it through `chunk.context`. Each context also owns
  the `NbtFormat` used by that direction; encoder-only persistence metadata remains in the encoder context.
- Keep `DataVersion` and `LastUpdate` outside the semantic Chunk in `ChunkNbtMetadata`; decoding returns both alongside
  the Chunk, and encoding receives them through `ChunkNbtEncoderContext`. `LastUpdate` is a caller-supplied absolute
  world game-time value, not wall-clock time; do not invent it from a clock, default, callback, or save snapshot.
- Preserve the selected release's scheduled-tick persistence semantics: `block_ticks` and `fluid_ticks` store an `Int`
  relative delay in `t`, and list order participates in restoring sub-tick order. Do not reinterpret that delay as an
  absolute timestamp or derive it from `LastUpdate`.
- Strongly type stable Chunk structure. An explicitly open, mod-extensible content subtree such as Block Entity data
  may remain an `NbtCompound`; do not duplicate structural fields already promoted into typed properties inside it.
- Retain caller-selected subtype generics on `Entity<E>` and `EntityChunk<E>`. Strongly type stable common fields, use
  explicit registries or adapters for vanilla and mod subtype data, and keep `NbtCompound` as the lossless fallback for
  content that cannot be closed over by this library.
- A packet-derived Chunk may be NBT-encoded when the caller supplies the encoder context and persistence metadata. Do
  not add nullable source markers or reject it as incomplete; every field absent from the network value comes from the
  caller-supplied packet decoder context, not a library default. Document that those values are client-local and that
  the resulting save is lossy rather than an equivalent server backup.
- Palette mutation preserves stable IDs. Encoding uses a non-mutating compact snapshot; `compact()` is the explicit
  mutating operation.
- Directional Chunk, Entity Chunk, and POI Chunk codecs canonically decode one decompressed binary NBT `Source` into the
  semantic value or encode that value to a `Sink`; they neither close caller-owned streams nor flush a sink. Their raw
  `NbtDocument` entry points are explicit tree-level branches over the same private semantic implementation, not a
  required intermediate in the ordinary stream path. Only retaining and rewriting the raw document preserves unmodeled
  fields; conversion through a typed semantic value remains intentionally lossy for those fields.
- Receiver-oriented conversion extensions are conveniences over those plain directional codecs. Accept either the
  already constructed encoder/decoder or its complete context, delegate exactly once, and never read a receiver context
  or duplicate the conversion. Compressed-record helpers additionally compose the owning compression format without
  creating a direct packet-to-persistence conversion.

## Verification

Run `:world-format:jvmTest`. Compression changes also require JS Node, WasmJS Node, and host Native tests; region-wire
changes require `:world-io:jvmTest`.
