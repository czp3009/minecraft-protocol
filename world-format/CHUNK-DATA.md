# Chunk data and representation inventory

This inventory describes the current public data model and directional codecs for the repository-selected release.
The official counterparts are `LevelChunk`/`ChunkAccess`, `LevelChunkSection`, `SerializableChunkData`,
`EntityStorage`/`Entity`, and `PoiManager`/`PoiSection`/`PoiRecord`. Minecraft's runtime owners, registries, level
access,
tickers, entity indexes and dirty tracking are deliberately outside these plain data values.

NBT references below name fields in a decompressed Region record. Packet projections belong to
[protocol-world](../protocol-world/README.md); physical packet and Section bytes belong to
[protocol-serialization](../protocol-serialization/README.md). “Absent” means the representation does not carry that
fact. It does not authorize a decoder to reconstruct it from an unrelated default or a later update.

## Common property contract

`DataProperties.entries: MutableMap<String, PropertyValue<*>>` is the only dynamic store. `PropertyType<T>` is an
identity
token, and `PropertyKey<T>` combines that token with a field name. Typed and name-based access return the same value.
`PropertyList.values` contains nested property values; `OptionalValue(null)` represents explicit absence.
Generic NBT arrays use editable primitive arrays with `PropertyTypes.ByteArray`, `IntArray` and `LongArray`;
`PropertyTypes.Nbt` explicitly retains immutable tags. Generic mapping preserves primitive NBT widths.
`PropertyValue.value` is mutable; same-token typed assignment reuses the cell, while named assignment replaces it.

An unregistered field follows the generic mapping at its current owner. A registered reader may choose a semantic
token; its writer must describe the inverse representation or explicitly omit the field. A custom semantic Kotlin
object is not automatically serializable. Reserved structural names cannot also occur in the owner's properties.
No global extra-data bucket is merged back after encoding.

The generic mapping does not interpret arbitrary nested field names as vanilla types. Scope-sensitive mappings are
explicit through `NbtPropertyPath(scope, name)`. Writers traverse current references, report cycles and permit shared
acyclic values. Custom callbacks use the mappings passed to them when encoding children.

## Chunk root and Sections

| In-memory field                                         | NBT                                                            | Full Chunk packet / missing information                                                       |
|---------------------------------------------------------|----------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `Chunk.chunkPosition`                                   | Authoritative `xPos`, `zPos` Ints                              | Packet `x`, `z`                                                                               |
| `chunkContext`                                          | Supplied dimension ID, layout, default state and biome         | Supplied complete directional context; never encoded                                          |
| `sections: Array<ChunkSection?>`, `sectionMinY`         | `sections` List; origin + array index becomes `Y` Byte         | Ordered terrain payload plus boundary light masks; Section Y derives from explicit layout     |
| `blockEntities: MutableMap<BlockPosition, BlockEntity>` | `block_entities` List; map key becomes `x`, `y`, `z`           | Packed local X/Z, Y, raw type ID and explicitly selected update tag                           |
| `heightmaps`                                            | `Heightmaps` Compound                                          | Selected client heightmaps; additional maps/properties supplied by missing-data provider      |
| `lighting.isLightCorrect`                               | `isLightOn` Byte, absent means false                           | Absent; supplied by missing-data provider                                                     |
| `lighting.skyLightSources`                              | Absent                                                         | Absent; caller-owned runtime samples                                                          |
| `blockTicks`, `fluidTicks`                              | Ordered `block_ticks`, `fluid_ticks` Lists                     | Absent; supplied by missing-data provider                                                     |
| `structures`                                            | `structures` Compound                                          | Absent; supplied by missing-data provider                                                     |
| `postProcessing`                                        | `PostProcessing` List                                          | Absent; supplied by missing-data provider                                                     |
| `status: String`                                        | Actual `Status` string, including non-full values              | Absent; supplied by missing-data provider                                                     |
| `inhabitedTime: Long`                                   | `InhabitedTime` Long                                           | Absent; supplied by missing-data provider                                                     |
| `upgradeData`, `blendingData`                           | Optional `UpgradeData`, `blending_data`                        | Absent; supplied by missing-data provider                                                     |
| `properties`                                            | Unclaimed root fields, excluding unsupported generation fields | No generic vanilla root payload; supplied explicitly or handled by a separate custom protocol |
| `ChunkNbtMetadata`                                      | `DataVersion` Int and `LastUpdate` Long                        | Not a Chunk field; explicit NBT encoder input / decoder result                                |

The terrain model targets fully generated Chunks. `status == "minecraft:full"` is observable, not a load policy. The
decoder attempts the completed schema regardless of status, retains supported fields, and does not implement
ProtoChunk generation state. Missing required or malformed structures may fail. Use the raw document path to preserve
unsupported generation data. An empty constructor chooses full status but performs no generation.

| Nested value                 | Stored shape and runtime meaning                                    | Representation boundary                                                                                       |
|------------------------------|---------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `ChunkSection.terrain`       | Nullable `SectionTerrain`; missing terrain reads as domain defaults | A light-only Section need not contain terrain; packet terrain must cover the supplied build range             |
| `SectionTerrain.blockStates` | Mutable `PalettedContainer<BlockState>`, exactly 4096 cells         | NBT local palette of `Name` and `Properties`; packet raw IDs resolve through `PacketCodecContext`             |
| `SectionTerrain.biomes`      | Mutable `PalettedContainer<BiomeId>`, exactly 64 samples            | NBT identifier palette; packet registry palette                                                               |
| `BlockState`                 | Immutable `blockId` and `StateProperties` canonical strings         | Unknown identifiers/property names are ordinary values; packet conversion requires an installed state mapping |
| `StateProperty<T>`           | Explicit canonical-name/typed-value correspondence                  | A typed view over the same strings; not a second state store                                                  |
| `SectionStatistics`          | Nullable non-empty, fluid, ticking-block and ticking-fluid counts   | NBT carries none; packet carries the first two; absent required counts need explicit provider results         |
| `SectionLighting`            | Nullable `blockLight` and `skyLight`, each 4096 values in 0..15     | NBT nibble arrays; network mask/update arrays; absent is unknown, present zero is darkness                    |
| `ChunkSection.properties`    | Open Section-owned data                                             | Preserved by NBT; absent from vanilla Section payload                                                         |
| `PalettedContainer`          | Logical values, mutable through indexed access                      | Internal palette IDs do not define identity; encoding uses an independently editable compact copy             |

Palette and light indexes use X fastest, then Z, then Y. Biomes use four-block quart cells. Section array indexes are
relative to the independently stored absolute `sectionMinY`. Reads within build height do not allocate missing terrain;
writes allocate the needed Section/terrain.
Mutation does not recalculate counts, remove Block Entities, rebuild heights/light, schedule ticks or update POI.

## Block Entities, components and inventories

| Value                | Fields                                                       | NBT and network contract                                                                                                                                                          |
|----------------------|--------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BlockEntity`        | Type ID, `DataComponentMap`, properties                      | Structural `id`, coordinates and `components`; other data at the same compound. Position belongs solely to the parent map. Network update tags are a separate explicit projection |
| `DataComponentMap`   | Mutable component-ID to property-value map                   | Complete current component map; component-specific NBT mapping may be installed                                                                                                   |
| `DataComponentPatch` | Mutable component-ID to `SetValue` or `Removed`              | Missing inherits defaults; `!id: {}` removes in NBT; packet patch preserves all three states                                                                                      |
| `ItemStack`          | Item ID, count, component patch, properties                  | NBT `id`, `count`, `components`, plus open fields. Packet omits generic item properties; decoder receives a provider for them                                                     |
| `ItemSlots`          | Replaceable `Array<ItemStack?>`; indexed read/write and size | Sparse saved `Items` list with unsigned Byte `Slot`; null is an empty slot. Slot count must be supplied because trailing empties are absent from NBT                              |

An inventory's maximum stack size, recipes, slot roles, transfer order and cooldown rules belong to the application or
shared definitions. Plain ItemStacks may temporarily hold intermediate counts. The NBT item representation validates
its count range when encoded; the packet item encoder treats null/non-positive count as empty.

`NbtPropertyReaders.itemSlots(slotCount)` and `NbtPropertyWriters.itemSlots` map the shared slot representation. The
application chooses the field path and slot count. There are no published container/furnace keys, content-specific
readers, merchant types or machine wrappers. Their definitions and algorithms in the computation tests are user code.

Opening a chest synchronizes menu contents using `ClientboundContainerSetContentPacket` and subsequent slot packets.
The ordinary Chunk update tag does not contain its private inventory. `ItemStackPacketEncoder`/`Decoder` can be reused
for menu slots and entity equipment. A client menu and a client Block Entity are distinct pieces of application state.

## Heights, light sources, ticks and structures

| Value                           | Fields and missing meaning                                                                          | NBT                                                                                                                                |
|---------------------------------|-----------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `ChunkHeightmaps`               | Map of open `HeightmapType` keys plus properties                                                    | Each LongArray is a packed map; non-array extension fields remain properties                                                       |
| `Heightmap.values`, `known`     | 256 primitive absolute Y samples plus Boolean presence bits in Z-major column order                 | Relative to minimum Y, with enough bits for height + 1. Null cannot be silently encoded as zero; minimum Y is a known empty column |
| `ChunkSkyLightSources`          | 256 primitive Y samples, Boolean presence and below-world arrays                                    | Runtime-only; null means unavailable                                                                                               |
| `ScheduledTick<T>`              | Type, absolute position, trigger tick, priority, sub-tick order, properties                         | `i`, `x/y/z`, relative Int `t`, priority `p`; order is encoded in the saved list                                                   |
| `SavedTick<T>`                  | Type, position, relative Int delay, priority, properties                                            | Used by upgrade neighbor ticks without an implied scheduling epoch                                                                 |
| `ChunkPostProcessing.positions` | Nullable array of local-position lists with an absolute Section origin                              | Outer list is relative to minimum Section; packed Shorts use X, Y, Z nibbles                                                       |
| `UpgradeData`                   | Sides, Section-origin-indexed `Array<IntArray?>`, neighbor saved ticks, properties                  | `Sides`, `Indices`, `neighbor_block_ticks`, `neighbor_fluid_ticks`, open fields                                                    |
| `BlendingData`                  | Min/max Section, 16 primitive heights with presence bits, nullable biome/density arrays, properties | Bounds/heights saved; biome/density samples are runtime-only and decode as unavailable                                             |
| `ChunkStructures`               | Start map, reference sets, properties                                                               | `starts`, `References`; reference coordinates are packed Longs                                                                     |
| `StructureStart.Invalid`        | Explicit invalid start                                                                              | Official invalid marker                                                                                                            |
| `StructureStart.Valid`          | Origin Chunk, reference count, pieces, properties                                                   | Origin/count/`Children`, with structure ID supplied by parent key                                                                  |
| `StructurePiece`                | Piece type, bounding box, nullable orientation, generation depth, properties                        | `id`, `BB`, `O`, `GD`; concrete piece-specific fields remain open                                                                  |

Tick decoding adds the explicit batch `tickBase` to the saved delay and restores sub-tick order from list order.
Encoding sorts by that order, subtracts the explicit tick base as a Long, then performs official Int narrowing,
including
negative delays and wrapping. `LastUpdate` and wall-clock time do not choose the tick base. Blending column indexing and
vertical
sampling are documented directly on `BlendingData`; the model does not generate those samples.

## Entity Chunk and semantic Entity properties

| Value                           | In-memory fields                                                                          | NBT / network boundary                                                                                                               |
|---------------------------------|-------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `EntityChunk`                   | Position, dimension context, root Entity list, properties                                 | `Position`, `Entities`, root extensions; `DataVersion` is separate metadata. No aggregate Entity Chunk packet                        |
| `Entity`                        | Type, UUID, position, delta movement, rotation, nullable passengers, properties           | `id`, `UUID`, `Pos`, `Motion`, `Rotation`, `Passengers`; remaining fields stay open                                                  |
| Passenger tree                  | Current forward references, no reverse vehicle link or global UUID index                  | NBT recursion detects cycles. Passenger saved X/Z follow the immediate vehicle; Y remains the passenger's Y                          |
| Head yaw                        | Optional Float property                                                                   | Absent from saved base Entity data; explicit provider when missing during packet encoding                                            |
| Shared flags                    | Optional Byte property                                                                    | Caller metadata and persistence mappings select their representation                                                                 |
| `EntityAttributes`              | Attribute-ID to current `AttributeInstance` map                                           | Optional `attributes` semantic mapping; absent instances remain absent                                                               |
| `AttributeSupplier`             | Shared attribute defaults                                                                 | External to the graph and codec's stored data; reads do not insert instances                                                         |
| `AttributeInstance`             | Base value, modifier-ID map, properties                                                   | `base`, `modifiers`; default evaluation/clamping belongs to computation                                                              |
| `AttributeModifier`             | Amount, operation, permanent flag, properties                                             | NBT saves only permanent modifiers. Network carries no persistence flag; decoder receives that policy explicitly                     |
| `EntityEffects` / `EffectState` | Effect-ID map; amplifier, duration, ambient/visible/icon flags, hidden effect, properties | Optional `active_effects` mapping; hidden chains are recursive and cycle-checked. Pairing does not automatically synchronize effects |
| `EntityEquipment`               | Slot-ID map of nullable ItemStacks                                                        | Optional `equipment` mapping; explicit packet equipment mapping uses item codecs                                                     |

The attribute, effect and equipment readers are unbound value readers: no entity type or default attributes are
selected by the library. Brain memories, professions, offers, gossip and other concrete content use dynamic properties
or application-defined types and mappings. Tests demonstrate custom types without adding them to the runtime API.

`EntityPacketEncoder` takes a current Entity and separate connection IDs/relations in `EntityPairingData`. The finite
sequence is spawn, optional metadata, attributes, equipment, then passenger/vehicle/leash relationships. It performs no
registration or world lookup. The decoder creates or updates one Entity and returns unhandled tails and unresolved
relations for the endpoint/application. Metadata indexes and type-specific semantics are explicit callbacks.

`ClientboundAddEntityPacket.data` is a type-specific network value, not a generic Entity property. Explicit spawn-data
mappings convert it to and from semantic state. For example, official `FallingBlockEntity` holds a `BlockState` and
converts it through `Block.getId`/`Block.stateById` at this boundary. Concrete entity bindings remain caller code.

Movement integration, health/effect progression, brain decisions, vehicle reconciliation and cross-Chunk migration are
possible through these fields, but none runs automatically. Changing an Entity position does not move it between root
lists. Unknown Entity types remain canonical identifiers; a network encoder needs the corresponding registry mapping.

## POI Chunk

| Value                 | Fields                                                                                     | NBT / missing meaning                                                                                               |
|-----------------------|--------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `PoiChunk`            | Position, dimension/layout context, nullable Section array and absolute origin, properties | `Sections` and open root fields; NBT has no Chunk position, so decoder context supplies the selected Chunk position |
| `PoiSection`          | Validity, position-keyed records, properties                                               | `Valid`, `Records`, open fields; missing Section, invalid Section and valid empty Section remain distinct           |
| `PoiRecord`           | POI type ID, free tickets, properties                                                      | `type`, `free_tickets`, `pos` from parent map key; no second stored position                                        |
| `PoiType`             | Matching states, maximum tickets, valid range                                              | Caller-supplied shared definition; never confused with a record's current free-ticket count                         |
| `PoiChunkNbtMetadata` | Data version                                                                               | Separate `DataVersion`, not mutable domain state                                                                    |

There is no standard POI Chunk packet. Debug subscriptions and particular gameplay messages are separate projections.
Applications select candidates, decrement or release tickets, maintain validity, and synchronize any chosen display.

## Custom data and validation coverage

Fabric-style Chunk, Block Entity and Entity attachment compounds fit at their original property paths. Custom block
states, biomes and POI/entity types use the same structural containers. The library does not load mod implementations.
Custom network data must have an explicit packet component mapping or a negotiated custom payload; arbitrary disk NBT
has no automatic vanilla network channel. Changes to the structural storage schema require an appropriate codec/raw
representation instead of assuming that properties can replace the schema.

Tests cover reference replacement/removal, dynamic and typed access, cycles, palette identity, absent statistics/light,
metadata/tick boundaries, unknown fields, optional semantic mappings, official world rewrite/reload and official packet
codec samples. `ServerComputationTest` demonstrates furnace and villager/POI calculations; `UserWorldSimulationTest` in
world-io exercises chest/hopper computation, actual MCA bytes, packet bytes, client Chunk construction and separate menu
updates. These scenarios validate their stated paths; they are not an implementation of every gameplay algorithm or a
promise that a received Chunk can reconstruct untransmitted server state.

## Storage decisions and official counterparts

The model is a computation-facing data graph. Encoders read current reachable references on every call; they never
cache a mutable Chunk's encoded result. All mutable data is unsynchronized. Direct changes can temporarily leave
statistics, arrays, positions or related values inconsistent; applications restore the invariants needed by the next
operation. Only palette internals, immutable state values and explicit detached snapshots enforce coupled storage.

| Data / official counterpart                                     | Chosen storage and reason for the difference                                                                                                                                                                                                                               |
|-----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ChunkAccess.sections`                                          | Nullable Section array with an explicit origin. This library permits absent terrain and light-only Sections, including boundary/outlying light slots, without importing a Level owner.                                                                                     |
| `PoiManager` / `SectionStorage`                                 | Per-Chunk nullable Section array. Official storage indexes Sections across many Chunks; that global owner and its lifecycle belong to the application. `PoiSection.records` remains a standard position map for sparse direct access.                                      |
| `PalettedContainer`, `BitStorage`                               | Uniform value or packed `LongArray`, standard reverse HashMap and explicit compaction/copy operations. Official registry/direct modes cannot define the raw-ID-free domain; disk/network palettes are projected separately. Values require stable equality and hash codes. |
| `StateDefinition` / `StateHolder`                               | Caller-scoped shared immutable states and lazy transition maps. Official finite schemas precompute legal combinations; this library also admits custom property names/values without installing a schema.                                                                  |
| `DataLayer`                                                     | Uniform light or mutable packed nibble `ByteArray`, matching the official storage granularity.                                                                                                                                                                             |
| `Heightmap`, `ChunkSkyLightSources`                             | Primitive samples and Boolean presence/boundary arrays. Official packed heights assume complete runtime knowledge; this domain can represent individual unknown samples and expose efficient direct computation without a dimension-coupled bit container.                 |
| `ChunkAccess.postProcessing`, `UpgradeData`, `BlendingData`     | Section-indexed arrays, primitive upgrade/density/height samples, ordinary variable-length position/biome lists. Sparse null entries allocate no child collection.                                                                                                         |
| Block Entities, attributes, effects, component maps, structures | Standard maps for sparse identity lookup. No repeated linear list search or secondary owner-maintained index. Official specialized maps are not reproduced.                                                                                                                |
| Equipment and heightmap kinds                                   | Standard maps keyed by open identifiers, unlike official closed EnumMaps; applications and mods can add kinds without changing a library enum or dense ordinal mapping. These maps are small.                                                                              |
| Inventories / `NonNullList<ItemStack>`                          | Replaceable nullable slot array. Null represents empty without shared mutable EMPTY state; slot roles and size changes remain application concerns.                                                                                                                        |
| `ItemStack.copy`                                                | Explicit detached copy of built-in mutable trees, because this domain's component/property values are editable. Custom types provide copy callbacks. Immutable state/NBT values are shared; repeated mutable occurrences are copied independently.                         |
| `Entity`                                                        | Reference identity and iterative passenger traversal. Official identity uses a runtime entity ID; that connection/runtime-local identity is not a domain field. UUID remains mutable data, not hash identity or a hidden index.                                            |
| Scheduled ticks / official tick containers                      | Ordinary ordered lists of tick data. Priority queues, ticking indexes and scheduling ownership are gameplay/runtime mechanisms outside this library.                                                                                                                       |
| Dynamic properties                                              | One open map with mutable typed cells and primitive arrays. Content-specific fields remain application bindings instead of a hierarchy of concrete game classes.                                                                                                           |

Palette writes use expected constant-time value lookup; packed-width growth repacks cells when necessary.
Compaction is linear in cell count plus palette size and replaces retained palette/index capacity. Ordinary writes
and `fill` keep historical entries intentionally; callers can compact before creating a detached background-save copy.
Encoders never impose that policy or compact the live value. `fill` and uniform copies need no per-cell ID array.
Open generic numeric properties may still box values despite reusing their outer cells. Shared immutable layout
bounds/ranges are precomputed; mutable graph state and numerous coordinate values do not acquire derived caches.
`BlockState.with` reuses cached transitions after the first encountered change. Encoding block-state IDs uses
connection-context indexes, and direct-palette decoding resolves each encountered raw ID once per palette.
`copy()` preserves history, cell IDs and packed width in independent mutable storage; `compactCopy()` directly builds
an editable compact container without a historical clone or dense temporary cell-ID array. Both share elements.
`paletteInfo()` is detached diagnostics, while `paletteIndex()` exposes read-only local IDs, invalidated by compaction.
These are algorithm/storage properties, not a measured end-to-end throughput claim. Portable regression tests cover
palette lookup work, packed-width transitions, deep passenger traversal, detached copying and alias mutation through
both NBT and packet conversions.
