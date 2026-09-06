# world-format

`world-format` provides Minecraft world data and filesystem-independent formats for the repository-selected release.
It includes mutable Chunk, Entity Chunk and POI Chunk graphs, coordinates, standalone world-file schemas, data packs,
NBT conversion, compression, and Anvil containers. Physical formats borrow `kotlinx.io.Source` and `Sink`; this module
never opens a path or acquires a world lock. Use [world-io](../world-io/README.md) for world directories and
[protocol-world](../protocol-world/README.md) for world-value and packet conversion.

## Mutable world data

`Chunk` holds terrain Sections, Block Entities, heightmaps, light, scheduled ticks, structures, post-processing, status,
inhabited time, upgrade/blending data and open properties. `EntityChunk` separately holds root Entities and their
passenger trees. `PoiChunk` holds Sections of POI records. These are ordinary data graphs that application code can
use throughout server or client computation.

Full constructors retain the supplied references. Empty constructors allocate empty containers and do no generation
or simulation. Every mutable field and collection can be replaced; a property's typed and dynamic access paths reach
the same value. `copy()` is shallow. Removing an entry changes the current graph; references previously taken by the
application remain ordinary usable references. Encoding visits the graph that is currently reachable from the root.

Each root receives its own domain context. `ChunkContext` contains dimension identity/layout and the default canonical
block state and biome. `EntityChunkContext` contains dimension identity; `PoiChunkContext` contains dimension identity
and Chunk layout. Contexts contain no codecs, network raw IDs, world owner or lifecycle state.

Construct `ChunkPosition(x, z)` from absolute Chunk coordinates. Construct
`ChunkContext(dimensionId, dimensionTypeLayout, defaultBlockState, defaultBiome)` using the world's dimension definition
and explicit defaults such as `BlockState(BlockId("minecraft:air"))` and `BiomeId("minecraft:plains")`.
`DimensionTypeLayout.fromNbt(...)` reads the layout fields from a dimension-type definition. With the Configuration
modules
installed, [the vanilla context example](../protocol-configuration-vanilla/README.md#contexts-for-a-vanilla-overworld)
or the negotiated `MinecraftDimensionContext.chunkContext(...)` supplies this same domain context.

```kotlin
fun emptyChunkColumn(
    chunkPosition: ChunkPosition,
    chunkContext: ChunkContext,
): Triple<Chunk, EntityChunk, PoiChunk> = Triple(
    Chunk(chunkPosition, chunkContext),
    EntityChunk(chunkPosition, EntityChunkContext(chunkContext.dimensionId)),
    PoiChunk(chunkPosition, PoiChunkContext(chunkContext.dimensionId, chunkContext.dimensionTypeLayout.chunkLayout)),
)
```

The terrain model addresses fully generated Chunks. A disk decoder reads that model regardless of persisted status and
exposes the actual string through `status`; `isFullyGenerated` checks `status == "minecraft:full"`. Applications decide
whether to use a non-full result. Generation-only fields are not retained or completed, and missing required structures
can still cause a format error. Changing status does not generate terrain. Use the raw NBT path when unfinished
generation data must survive losslessly.

## Read and modify terrain

Section map keys are absolute Section Y. A terrain Section contains 4096 block cells and 64 biome samples. Block states
hold canonical `BlockId` and immutable `StateProperties`; biome cells hold `BiomeId`. Palette indexes and synchronized
registry raw IDs do not participate in their identity.

`getBlockState`/`setBlockState` and `getBiome`/`setBiome` accept absolute `BlockPosition` or `ChunkBlockPosition`.
Inside the construction height, a missing Section or terrain reads as the context's defaults without materializing
data. Writing materializes the required terrain. Boundary light-only Sections need no terrain. Use the `Chunk` from
`emptyChunkColumn(...).first`, a `BlockPosition(x, y, z)` within that Chunk, and a replacement such as
`BlockState(BlockId("minecraft:stone"))`:

```kotlin
fun replaceBlock(chunk: Chunk, blockPosition: BlockPosition, blockState: BlockState) {
    chunk.setBlockState(blockPosition, blockState)
    chunk.blockEntities.remove(blockPosition)
}
```

The two changes above are explicitly requested by the application. A block setter does not remove Block Entities,
update neighboring blocks, advance ticks, change POI, recompute statistics or rebuild light/heightmaps. Applications
perform those computations and write their results into the exposed fields.

Section statistics distinguish missing counts from known zero. Heightmaps contain absolute first-available Y values;
null is unknown and the dimension's minimum Y is a known empty column. Light layers distinguish absent data from a
present all-zero layer. Ordinary mutation does not invalidate any of these values automatically.

## Dynamic properties and typed views

`DataProperties.entries` is the single mutable property store. `PropertyKey<T>` combines a name and an identity-checked
`PropertyType<T>`. Name-based access exposes `PropertyValue<*>`; typed access checks its token before returning the
same stored value. A token's diagnostic name is not proof of type equality.

An application-defined wrapper holds the property reference and adds its own operations. For example, this `Chest`
belongs to the application; the library does not define its field name, size or inventory rules. `ItemStack` holds
item identity, count, a component patch and properties. `ItemSlots(size)` allocates slots whose null entries mean empty.
The wrapper takes the `DataProperties` reference exposed by `chunk.getBlockEntity(blockPosition)?.properties`. Its
`Items` property must already hold an `ItemSlots` value; constructing the wrapper does not decode or initialize it.

```kotlin
class Chest(val dataProperties: DataProperties) {
    companion object {
        val itemsKey = PropertyKey("Items", PropertyTypes.ItemSlots)
    }

    val itemSlots: ItemSlots
        get() = dataProperties.require(itemsKey)

    // This application's rule: put a whole stack into an empty slot.
    fun put(slot: Int, itemStack: ItemStack) {
        val itemSlots = itemSlots
        check(itemSlots[slot] == null) { "The destination slot must be empty" }
        itemSlots[slot] = itemStack
    }

    fun take(slot: Int): ItemStack? {
        val itemSlots = itemSlots
        return itemSlots[slot].also { itemSlots[slot] = null }
    }
}
```

Nested `DataProperties`, `PropertyList`, semantic values and explicitly retained NBT values use the same store.
Missing entries are not automatically interpreted as empty or zero; initialization belongs to the application.
`OptionalValue(null)` can express an explicit known absence where needed.

`StateProperties` is immutable and stores canonical name/value strings once. `StateProperty<T>` supplies an explicit
legal name/value correspondence for typed access; `BlockState.with` returns a new state. `DataComponentMap` is a current
component map, while `DataComponentPatch` distinguishes an inherited entry, explicit removal and explicit replacement.

## Decode and encode NBT

### Plain API

Construct `ChunkNbtDecoder` with a complete `ChunkNbtDecoderContext`, then reuse it over the dimension or batch whose
facts remain stable. Its `decode(source)` method consumes one decompressed NBT record and returns
`ChunkNbtDecodeResult(chunk, chunkNbtMetadata)`. `decodeDocument` is an alternative tree entry point over the same
semantic implementation; a document is not an intermediate in the stream path.

The following decoder maps the field used by the application-defined chest view above. Its slot count is supplied
by that application because the sparse saved list does not carry it. Reuse the `chunkContext` constructed earlier;
`tickBase` is the application's current game-time base. For encoding, take
`chunkContext.dimensionTypeLayout.chunkLayout` and construct `ChunkNbtMetadata(dataVersion, lastUpdate)` from the values
you want to persist, or reuse metadata returned by a prior decode:

```kotlin
fun createChestChunkDecoder(chunkContext: ChunkContext, tickBase: Long): ChunkNbtDecoder = ChunkNbtDecoder(
    ChunkNbtDecoderContext(
        chunkContext = chunkContext,
        nbtFormat = NbtFormat,
        nbtPropertyReadMappings = NbtPropertyReadMappings(
            fields = mapOf(
                NbtPropertyPath(
                    NbtPropertyScope("block_entity", "minecraft:chest"),
                    Chest.itemsKey.name,
                ) to NbtPropertyReaders.itemSlots(27),
            ),
        ),
        tickBase = tickBase,
    ),
)

fun createChunkEncoder(
    chunkLayout: ChunkLayout,
    tickBase: Long,
    chunkNbtMetadata: ChunkNbtMetadata,
): ChunkNbtEncoder = ChunkNbtEncoder(
    ChunkNbtEncoderContext(
        chunkLayout = chunkLayout,
        nbtFormat = NbtFormat,
        nbtPropertyWriteMappings = NbtPropertyWriteMappings(types = NbtPropertyWriters.types),
        tickBase = tickBase,
        chunkNbtMetadata = chunkNbtMetadata,
    ),
)
```

`NbtFormat` above comes from `com.hiczp.minecraft.nbt.serialization`. Use `NbtPropertyReadMappings()` to leave all open
fields generic. Unregistered open fields still use generic properties when other paths have explicit mappings.
`NbtPropertyReaders` and `NbtPropertyWriters` provide optional value mappings for shared structures such as ItemStacks,
component patches and attributes. Applications bind those readers to their own `NbtPropertyPath` values and install the
chosen writers in `NbtPropertyWriteMappings.types`. `readProperties`/`writeProperties` also support open fields inside
user-defined values.

For a document obtained through `regionHandle.readChunkNbtDocument(chunkPosition)` in
[world-io](../world-io/README.md#inspect-region-metadata-and-lower-level-values), the explicit conversion is:

```kotlin
fun rewriteChunkDocument(
    nbtDocument: NbtDocument,
    chunkNbtDecoder: ChunkNbtDecoder,
    chunkNbtEncoder: ChunkNbtEncoder,
): NbtDocument {
    val decoded = chunkNbtDecoder.decodeDocument(nbtDocument)
    return chunkNbtEncoder.encodeDocument(decoded.chunk)
}
```

Construct the two codecs using `createChestChunkDecoder(...)` and `createChunkEncoder(...)` above. For decompressed
streams, the corresponding plain calls are `chunkNbtDecoder.decode(source)` and `chunkNbtEncoder.encode(chunk, sink)`.

### Convenience API

Replace the body of `rewriteChunkDocument` with this equivalent expression using the same inputs:

```kotlin
return nbtDocument.toChunk(chunkNbtDecoder).chunk.toNbtDocument(chunkNbtEncoder)
```

Both extensions also accept their complete directional context for a one-off call. A `CompressedChunk`, obtained from
`regionHandle.readCompressedChunk(chunkPosition)`, has the same `toChunk` overloads; `chunk.toCompressedChunk(...)`
adds compression around the NBT encoder. Reuse prebuilt codecs when configuration stays stable. Shortcuts never read
encoder configuration from `chunk.chunkContext`.

### Context and field boundaries

`ChunkNbtEncoder` separately receives `ChunkNbtEncoderContext`, including `ChunkLayout`, NBT format,
write mappings, tick base and `ChunkNbtMetadata`. It never derives configuration from the value's replaceable context.
The POI NBT encoder likewise needs only `ChunkLayout`; the Entity Chunk NBT encoder needs no domain context or layout.
The decoder reads `DataVersion` and `LastUpdate` from the record into its metadata result and takes neither as input.
The encoder's metadata supplies both values to write; `status` and `inhabitedTime` belong to the Chunk.
Decoding trusts stored `xPos`/`zPos`, without an extra expected-position comparison.

Scheduled ticks hold absolute trigger time and sub-tick order in memory. NBT stores relative `Int` delays; decoding
adds the supplied tick base, and encoding subtracts it before official integer narrowing. List order restores sub-tick
order. Neither `LastUpdate` nor wall-clock time substitutes for the supplied tick base.

The encoder preserves supported open data at its original owner and rejects structural/property name collisions.
Primitive NBT widths survive the generic mapping. Custom semantic values require a registered writer or an explicit
omission; their serialization is never guessed. Custom writers use the mappings passed to their callback for nested
writes, preserving operation-local cycle detection. Shared acyclic values remain valid.

### Modify the decoded chest

Decode with `createChestChunkDecoder(chunkContext, tickBase)` above (or bind that decoder to a world-io dimension).
Pass the returned `ChunkNbtDecodeResult.chunk` and a `BlockPosition(x, y, z)` containing a chest to this application
function. A missing `Items` field is initialized here; a present field was mapped to `ItemSlots` by the decoder:

```kotlin
fun putDiamond(chunk: Chunk, blockPosition: BlockPosition) {
    val blockEntity = requireNotNull(chunk.getBlockEntity(blockPosition))
    check(blockEntity.blockEntityTypeId == BlockEntityTypeId("minecraft:chest"))
    check(blockEntity.properties["LootTable"] == null) { "This example requires an ordinary chest without pending loot" }
    if (blockEntity.properties[Chest.itemsKey] == null) {
        blockEntity.properties[Chest.itemsKey] = ItemSlots(27)
    }
    val chest = Chest(blockEntity.properties)
    val emptySlot = chest.itemSlots.items.indexOfFirst { it == null }
    check(emptySlot >= 0) { "The chest must have an empty slot" }
    chest.put(emptySlot, ItemStack(ItemId("minecraft:diamond"), 1))
    check(chest.itemSlots === blockEntity.properties["Items"]!!.get(PropertyTypes.ItemSlots))
}
```

The original Chunk now contains the item; there is no commit or wrapper registration. Encoders visit its current
properties without knowing `Chest`. The getter observes replacement of `Items` inside the same property container.
Replacing the entire `blockEntity.properties` leaves an existing wrapper pointing at the old container; construct
another wrapper to use the new reference. The same pattern applies to Entity and POI properties.

Continue with [disk-to-client orchestration](../world-io/README.md#disk-to-memory-to-packets) or
[packet encoder and decoder construction](../protocol-world/README.md#chunk-packet-conversion). The executable
[user computation scenario](../world-io/src/commonTest/kotlin/com/hiczp/minecraft/world/io/UserWorldSimulationTest.kt)
also covers inventory transfers, disk writes, packet bytes and separate client menu state.

## Entities and points of interest

An `Entity` exposes its type, UUID, position, delta movement, rotation, nullable passenger list and properties.
`EntityChunk.allEntities()` traverses current occurrences and reports passenger cycles. The library maintains no reverse
vehicle link, UUID index or cross-Chunk membership. The application moves an Entity between collections when needed.

Reusable semantic values include attribute instances and modifiers, effects and equipment. Applications define
content-specific properties and wrappers using the same dynamic store. Attribute defaults remain in a separately
supplied `AttributeSupplier`; reading a default does not insert an instance. Applications evaluate modifiers and advance
effects through their own code. See [the field and representation inventory](CHUNK-DATA.md) for their persistence
boundaries.

`EntityChunkNbtDecoder`/`EntityChunkNbtEncoder` use independent complete contexts and return/accept
`EntityChunkNbtMetadata`. `PoiChunkNbtDecoder` additionally requires the selected Chunk position in its context because
POI NBT does not carry it. Its result separates `PoiChunkNbtMetadata` from the domain value.

For these plain NBT paths, obtain each document from its Entity/POI Region's `readChunkNbtDocument` in world-io.
Construct `EntityChunkContext(chunkContext.dimensionId)` and
`PoiChunkContext(chunkContext.dimensionId, chunkContext.dimensionTypeLayout.chunkLayout)` from the earlier domain
context. `chunkPosition` is the selected POI record's absolute `ChunkPosition(x, z)`. These examples retain the original
DataVersion and leave open fields dynamic:

```kotlin
fun rewriteEntityDocument(nbtDocument: NbtDocument, entityChunkContext: EntityChunkContext): NbtDocument {
    val entityChunkNbtDecoder = EntityChunkNbtDecoder(
        EntityChunkNbtDecoderContext(entityChunkContext, NbtFormat, NbtPropertyReadMappings()),
    )
    val decoded = entityChunkNbtDecoder.decodeDocument(nbtDocument)
    val entityChunkNbtEncoder = EntityChunkNbtEncoder(
        EntityChunkNbtEncoderContext(NbtFormat, NbtPropertyWriteMappings(), decoded.entityChunkNbtMetadata),
    )
    return entityChunkNbtEncoder.encodeDocument(decoded.entityChunk)
}

fun rewritePoiDocument(
    nbtDocument: NbtDocument,
    poiChunkContext: PoiChunkContext,
    chunkPosition: ChunkPosition,
): NbtDocument {
    val poiChunkNbtDecoder = PoiChunkNbtDecoder(
        PoiChunkNbtDecoderContext(poiChunkContext, chunkPosition, NbtFormat, NbtPropertyReadMappings()),
    )
    val decoded = poiChunkNbtDecoder.decodeDocument(nbtDocument)
    val poiChunkNbtEncoder = PoiChunkNbtEncoder(
        PoiChunkNbtEncoderContext(
            poiChunkContext.chunkLayout, NbtFormat, NbtPropertyWriteMappings(), decoded.poiChunkNbtMetadata,
        ),
    )
    return poiChunkNbtEncoder.encodeDocument(decoded.poiChunk)
}
```

The corresponding convenience calls use the same codecs or complete contexts:

| Plain call in the examples                                  | Convenience call                                           |
|-------------------------------------------------------------|------------------------------------------------------------|
| `entityChunkNbtDecoder.decodeDocument(nbtDocument)`         | `nbtDocument.toEntityChunk(entityChunkNbtDecoder)`         |
| `entityChunkNbtEncoder.encodeDocument(decoded.entityChunk)` | `decoded.entityChunk.toNbtDocument(entityChunkNbtEncoder)` |
| `poiChunkNbtDecoder.decodeDocument(nbtDocument)`            | `nbtDocument.toPoiChunk(poiChunkNbtDecoder)`               |
| `poiChunkNbtEncoder.encodeDocument(decoded.poiChunk)`       | `decoded.poiChunk.toNbtDocument(poiChunkNbtEncoder)`       |

POI Section map keys provide Section Y; `PoiSection.records` keys provide absolute block positions. Each record contains
its type ID, current free-ticket count and properties. `PoiType` holds separately supplied matching states, maximum
tickets and valid range. The model does not claim/release tickets, validate settlements, discover POI from terrain or
subscribe to block changes on the application's behalf.

## Convert coordinates safely

`MinecraftCoordinates` and the position types share one implementation for negative-coordinate floor semantics and
checked parent/child conversions:

```kotlin
fun locatePosition(x: Double, y: Double, z: Double): RegionPosition {
    val blockPosition = MinecraftCoordinates.block(x, y, z)
    val chunkPosition = blockPosition.chunkPosition
    val sectionPosition = blockPosition.sectionPosition
    val regionPosition = chunkPosition.regionPosition

    check(sectionPosition.chunkPosition == chunkPosition)
    check(blockPosition.regionPosition == regionPosition)
    check(regionPosition.chunk(regionPosition.local(chunkPosition)) == chunkPosition)
    return regionPosition
}
```

The main absolute types are `BlockPosition`, `SectionPosition`, `ChunkPosition`, and `RegionPosition`. Relative types
are `LocalBlockPosition`, `ChunkBlockPosition`, and `LocalChunkPosition`. Coverage ranges and lazy position sequences
are available from their owning absolute values.

Scalar helpers such as `blockCoordinate`, `sectionCoordinate`, `chunkCoordinate`, `regionCoordinate`, and their
local/reverse variants are useful when only one axis is available.

`ChunkRange` and `RegionRange` represent lazy rectangular coordinate ranges. The `..` operator includes both corners:

```kotlin
val chunkRange = ChunkPosition(-33, -1)..ChunkPosition(32, 1)
val regionRange = chunkRange.coveringRegionRange

check(chunkRange in regionRange.chunkRange)
```

Use `..<` for an upper-exclusive corner and `ChunkRange.enclosing(first, second)` when the two inclusive corners may
arrive in either order. Both range types support position and range containment with `in`, intersection with
`first intersect second` / `first intersects second`, direct iteration, and Z-then-X position sequences.
`RegionRange.chunkRange` expands complete Regions exactly, while
`ChunkRange.coveringRegionRange` returns the smallest Region-aligned rectangle containing the requested Chunks.

## Read and write compressed NBT

`CompressedNbtFormat` combines a `CompressionRegistry` with the unnamed compound-root NBT used by Chunk records.
Supply caller-owned `kotlinx.io.Source`/`Sink` streams (for example `Buffer` instances, or streams from an I/O adapter).
Choose the source's actual compression and the desired output compression, such as `Compression.ZLIB`:

```kotlin
fun transcodeDocument(
    source: Source,
    sourceCompression: Compression,
    targetCompression: Compression,
    sink: Sink,
): NbtDocument {
    val compressedNbtFormat = CompressedNbtFormat()
    val nbtDocument = compressedNbtFormat.decodeDocumentFromSource(source, sourceCompression)
    compressedNbtFormat.encodeDocumentToSink(nbtDocument, targetCompression, sink)
    return nbtDocument
}
```

Streams remain caller-owned. Use `NbtDocument.toCompressedChunk()` when a detached compressed value with its exact
length is useful:

```kotlin
fun writeCompressed(
    nbtDocument: NbtDocument,
    compression: Compression,
    sink: Sink,
) {
    nbtDocument.toCompressedChunk(compression).writeTo(sink)
}
```

`CompressionRegistry` also exposes raw `compressToSink`/`decompressToSink` operations and accepts caller-registered
CUSTOM codecs.

## Inspect or create an Anvil container

`AnvilRegionFormat` reads and writes complete `.mca` container streams. Because this module has no paths, external
`.mcc` payloads remain separate values for a filesystem owner to resolve.

Decode a detached Region:

```kotlin
fun readRegion(source: Source): AnvilRegion =
    AnvilRegionFormat.decodeFromSource(source)
```

For record-at-a-time inspection without retaining inline payloads, use the callback API:

```kotlin
fun inspectRecords(
    source: Source,
    inspect: (AnvilChunkRecordInfo, Source) -> Unit,
) {
    AnvilRegionFormat.decodeRecordsFromSource(source) { anvilChunkRecordInfo, inlinePayloadSource ->
        inspect(anvilChunkRecordInfo, inlinePayloadSource)
    }
}
```

The callback may consume only the bytes it needs; the format discards the remainder before advancing to the next record.
An external record supplies an empty inline stream because only a filesystem layer can locate its sidecar.

Pass the `AnvilRegion` returned by `readRegion` and a caller-owned output stream. Encoding writes the main Region and
returns the external payloads that the caller must place:

```kotlin
fun writeRegion(
    anvilRegion: AnvilRegion,
    sink: Sink,
): Map<LocalChunkPosition, CompressedChunk> =
    AnvilRegionFormat.encodeRecordsToSink(anvilRegion, sink)
```

Unchanged compressed records can be inspected or repacked without decompression.

## Structured files and data packs

The module includes serializers for the repository-selected `LevelDat`, `PlayerData`, `PlayerAdvancements`, and
`PlayerStatistics` schemas. Saved-data models live in `com.hiczp.minecraft.world.format.data`.
`SavedDataFile<T>` models their shared `DataVersion`/`data` envelope. Root models cover world-generation settings, world
clocks, weather, the wandering trader, stopwatches, the scoreboard, scheduled events, random sequences, game rules,
custom boss events, and map data. Dimension models cover world borders, Chunk tickets, raids, and the Ender Dragon
fight. Registry-dependent or dynamically dispatched subtrees remain raw NBT while their stable enclosing structure is
typed. These models do not migrate historical files. Typed decoding is strict; use `NbtDocument`,
`NbtTag`, or `JsonElement` for open-ended data.

`WorldGenSettingsData.dimensions` is keyed by strong `DimensionId` values. Each `WorldGenDimension.type` is either a
`WorldGenDimensionType.Reference(DimensionTypeId)` or an inline NBT holder, matching the stored holder shape without
reducing it to an untyped string. `DimensionId`, `DimensionTypeId`, `SavedDataId`, `DataPackResourcePath`, and
`DataPackResourceId` validate their components and provide `parse` entry points for external text; namespaced forms
normalize a missing namespace to `minecraft`.

The data-pack API is also filesystem-independent. Construct `DataPackArchive(dataPackId, files)` from
`DataPackId("example")` and file-path/byte pairs, or obtain an archive from `worldAccess.dataPacks.readArchive(...)` in
world-io. Select `DataPackFormatVersion(major, minor)` for overlay resolution; `null` selects the base files only.
Optional `DataPackFileDecoder` callbacks parse custom file forms; the empty list uses the built-in formats:

```kotlin
fun resolveDataPack(
    dataPackArchive: DataPackArchive,
    dataPackFormatVersion: DataPackFormatVersion?,
    dataPackFileDecoders: List<DataPackFileDecoder> = emptyList(),
): ResolvedDataPackStack {
    val dataPack = DataPackFormat(dataPackFileDecoders = dataPackFileDecoders).decode(dataPackArchive)
    return DataPackStack(dataPack).resolve(dataPackFormatVersion)
}
```

Archives, typed files, overlays, filters, enabled feature flags, tags, and merged resources are ordinary values.
Compressed NBT files are decoded from retained in-memory bytes only when `NbtFile.nbtDocument` is requested; this does
not reopen the data-pack container or require a data-pack read lock.
`ResolvedDataPackResource.decodeDataPackTagFile()` exposes string and object tag entries as `DataPackTagFile` values.

`WorldDataPackLoadResult` is the detached handoff for a world selection whose `file/...` packs have been loaded while
core, built-in, or loader-owned packs still need their owning source. It retains strong enabled and disabled
`DataPackId` values, the persisted feature configuration, loaded packs, and unresolved IDs without retaining paths or
open resources. Obtain this result from `minecraftWorldAccess.dataPacks.readEnabled()` in world-io. Build
`applicationDataPacksById` from the application's already decoded packs using `associateBy { it.dataPackId }`. Complete
the stack in the original low-to-high priority order with that caller-owned source:

```kotlin
fun completeWorldDataPackStack(
    worldDataPackLoadResult: WorldDataPackLoadResult,
    applicationDataPacksById: Map<DataPackId, DataPack>,
): DataPackStack = worldDataPackLoadResult.toDataPackStack(applicationDataPacksById::get)
```

Directory and ZIP access is provided by [`world-io`](../world-io/README.md#read-world-data-packs); Configuration
projection is provided by [`protocol-configuration`](../protocol-configuration/README.md).

## Failures

- `AnvilFormatException` reports invalid Region/container structure.
- `CompressionFormatException` reports invalid compression framing or unavailable CUSTOM codecs.
- `DataPackFormatException` reports data-pack file and stack-resolution failures.
- `ChunkNbtFormatException` reports strong Chunk schema, coordinate, layout, or registry failures.
- `EntityChunkNbtFormatException` reports strong Entity Chunk schema, position, identity, or vector failures.
- `PoiChunkNbtFormatException` reports strong POI Chunk schema, position, or ownership failures.
- NBT and stream/backend errors retain the exception type from their owning module.
