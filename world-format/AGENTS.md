# world-format

## Standalone schemas and packs

- `MinecraftWorldFormat.WORLD_VERSION` comes from the official target artifact. Persisted fields stay named
  `DataVersion`; applications own compatibility checks and migration.
- Model only the selected-release `LevelDat`, `PlayerData`, saved-data, advancement and statistics schemas. Keep each
  file schema in its own type-named source file. Shared saved-data envelopes and payloads live in `format.data`.
- Use generated serializers for fixed structures. Apply property/type adapters for official list/array differences;
  use file-level `@UseSerializers` for uniform mappings within a model file. The advancement root requires its custom
  map-composite serializer because dynamic advancement IDs share the object with `DataVersion`.
- Standalone serializers reject unknown fields by default. Raw NBT/JSON paths preserve unmodeled content; this strict
  schema policy does not apply to open Chunk property scopes.
- Pack decoders are extensible. `WorldDataPackLoadResult` is a detached partial selection: preserve enabled low-to-high
  order and already loaded packs, aggregate unavailable IDs, and add no core-pack or discovery policy. Retain an
  already ordered loaded list; normalize only an out-of-order one.
- `WorldGenSettingsData` preserves strong dimension keys and reference-or-inline type holders. `DimensionTypeLayout`
  is the shared layout-field decoder; protocol consumers do not duplicate it.

## Anvil and coordinates

- `AnvilRegionFormat` receives compressed records and preserves their compression registrations. Container parsing,
  compression and semantic NBT decoding are separate operations; unchanged records can be repacked without inflation.
- CUSTOM compression remains injectable. Maintained libraries own raw codecs/checksums; this module owns Minecraft
  containers. Reject overlap, truncation, overflow, invalid identifiers and checksum failure at their format boundary.
- Container structure failures use `AnvilFormatException`; I/O, NBT, cancellation and custom-codec failures retain
  their own categories. Stream operations leave caller endpoints open.
- `MinecraftCoordinates` owns scalar and typed conversions, including floor semantics for negative coordinates.
  `ChunkRange`/`RegionRange` are inclusive rectangular products: `..` preserves endpoint order, `..<` excludes both
  upper axes, and `enclosing` explicitly normalizes unordered corners. Name scale-reducing coverage `covering...`.

## Mutable domain values

- `Chunk`, `EntityChunk` and `PoiChunk` retain absolute Chunk positions. Section Y, Block Entity/POI positions and
  named definition identities belong to enclosing map keys, not duplicate entry fields. Absolute helpers validate
  membership and delegate to local operations.
- `Chunk` models completed data, without `ProtoChunk` progression. NBT decoding attempts the completed schema even
  for a nonterminal `status`, which it exposes for caller decisions. It neither rejects by status nor preserves all
  unfinished generation data; lossless preservation of that input requires raw NBT.
- Full constructors retain supplied mutable references; empty constructors allocate empty data. Deletion changes
  reachability only. Do not add detach tracking, hidden indexes, invalidation or graph ownership.
- `ChunkContext` contains raw-ID-free dimension/layout and default block/biome facts. Its reference on a Chunk is
  replaceable. A decoder attaches its context's same reference; an encoder uses only its explicitly supplied context.
- `DataProperties.entries` is the single dynamic store. Typed keys check token identity and share values with named
  access; mappings and wrappers keep no second copy. `BlockState` and its canonical string properties are immutable.
- Preserve primitive NBT widths and unknown nested fields at each open owner. Structural field names cannot also
  appear in that owner's properties. Custom writers pass the supplied mapping to child writes so operation-local
  cycle detection covers callbacks without installing ownership on the graph.
- Missing counts, individual height samples, light layers, passenger knowledge and materialized attributes differ from
  known empty values. Shared attribute defaults and POI definitions remain separate from current instances.
- Palette mutation preserves stable indices, which are not registry IDs. Encoding uses a non-mutating compact
  snapshot; `compact()` is the explicit mutating operation.

## Domain NBT codecs

- Read `DataVersion` and terrain `LastUpdate` from the input record into separate metadata results. Encoders receive
  the values to write through their contexts; decoder contexts do not accept them. None enters the domain graphs.
- Scheduled ticks hold absolute trigger times in memory. `block_ticks`/`fluid_ticks` persist Int relative delays in
  `t`; decode by adding the explicit context `tickBase`, encode by subtracting it with official integer narrowing, and
  restore sub-tick order from list order. Never substitute `LastUpdate` or wall-clock time for the tick base.
- NBT decoder contexts carry the domain context attached to their result. Terrain/POI encoder contexts carry only
  `ChunkLayout`; the Entity encoder needs no domain context. Each direction owns its NBT format and mappings; terrain
  codecs also need a tick base, and encoding needs persistence metadata. Stream codecs consume decompressed binary NBT
  directly, without a required `NbtDocument` intermediate, and neither close endpoints nor flush sinks. Document entry
  points share the same semantic implementation.
- Packet-derived values can be saved with an explicit NBT encoder and metadata. Do not add source markers or reject
  them as incomplete; caller-supplied missing data is local state, not recovered server state.
- Compressed-record conveniences compose compression and the plain NBT codec. They do not bypass the domain value
  with a direct packet-to-persistence conversion.

## Verification

Run `:world-format:jvmTest`. Compression changes also require JS Node, WasmJS Node and host Native tests; changed
persisted bytes require `:world-io:jvmTest`. Domain API changes also exercise user computation and conversion scenarios
in world-format, protocol-world and world-io; concrete game-content wrappers stay in those tests.
