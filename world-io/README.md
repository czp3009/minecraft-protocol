# world-io

`world-io` provides Okio-backed access to Minecraft world directories. Its API follows the logical objects applications
work with:

```text
world -> level.dat
world -> dimensions -> dimension -> Chunk Region -> Chunk -> Section -> block or biome
                                -> Entity Region -> Entity Chunk -> Entity -> passengers
                                -> POI Region -> POI Chunk -> POI Section -> POI record
                                -> data -> namespaced saved data
world -> players -> player data, statistics, and advancements
world -> data -> namespaced saved data
world -> dataPacks -> directory or ZIP pack -> data-pack file
```

Physical `.mca`/`.mcc` details are hidden behind Region handles. Filesystem-independent coordinates, compression, NBT
composition, Anvil containers, and semantic values come from [`world-format`](../world-format/README.md).

Two access modes serve different situations:

| Access                     | Use when                                     | Behavior                                                                              |
|----------------------------|----------------------------------------------|---------------------------------------------------------------------------------------|
| `MinecraftWorldAccess`     | This process owns the world                  | Acquires `session.lock`, supports reads and writes, and has a suspend close lifecycle |
| `LiveMinecraftWorldAccess` | Another process may own and change the world | Takes no lock and never mutates; the world access itself has no close lifecycle       |

Filesystem support is configured for JVM, Android, supported Native targets, and Kotlin/JS Node. Browser and Wasm
applications should use the filesystem-independent modules.

All filesystem types exposed by this module are Okio types: `Path`, `FileSystem`, `FileHandle`, `BufferedSource`, and
`BufferedSink`. Filesystem failures visible through `world-io` are in Okio's `IOException` hierarchy; NBT, compression,
Anvil, and serialization failures retain their own semantic exception categories.

## Quick start

Construct an Okio path with `"world".toPath()` (`okio.Path.Companion.toPath`) and supply the player's UUID string (the
player-data filename without its extension). Opening the world owns its lease; `use` closes it after the read:

```kotlin
suspend fun readPlayerStatistics(worldPath: Path, playerUuid: String): PlayerStatistics? =
    MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
        minecraftWorldAccess.players.readStatistics(playerUuid)
    }
```

Inside the same world lifetime, `readLevelData()` reads `LevelDat`; the `players` child also provides `listUuids()`,
`readData(uuid)` and `readAdvancements(uuid)`. `dataPacks.readEnabled()` returns the selected file packs, and
`dimensions.overworld.data.readChunkTicketsData()` reads the overworld ticket data. These standalone reads need no
format or serializer arguments. Later examples borrow an open world from this same `open(...).use { ... }` pattern.

## Read computational world values from disk

Construct a `ChunkNbtDecoder` with its domain context, NBT format, mappings and tick base. The
[world-format factory example](../world-format/README.md#decode-and-encode-nbt) constructs `ChunkNbtDecoder` from the
world's layout, explicit property mappings and tick base. Choose `DimensionId.Overworld` or
`DimensionId.parse("example:moon")`, and construct the absolute `ChunkPosition(x, z)` to read.
Entity and POI records use their own directional codecs.

### Plain API: use the codec with a Region handle

The world is the still-open value from the quick start. Opening the containing Region and passing the constructed
decoder explicitly makes the file/semantic boundary visible:

```kotlin
suspend fun readStoredChunk(
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionId: DimensionId,
    chunkPosition: ChunkPosition,
    chunkNbtDecoder: ChunkNbtDecoder,
): ChunkNbtDecodeResult? = minecraftWorldAccess.dimensions[dimensionId]
    .openRegion(chunkPosition.regionPosition).use { regionHandle ->
        regionHandle.readChunk(chunkPosition, chunkNbtDecoder)
    }
```

### Convenience API: bind a dimension reader

Replace the body of `readStoredChunk` with the following expression using the same inputs. The view handles Region
opening/closing and keeps the decoder for the batch where its layout, mappings and tick base remain stable:

```kotlin
val dimensionChunkReads = minecraftWorldAccess.dimensions[dimensionId].chunks(chunkNbtDecoder)
return dimensionChunkReads.readChunk(chunkPosition)
```

`.chunks(chunkNbtDecoderContext)` also constructs the codec from a complete context. In a repeated read loop, retain the
`DimensionChunkReads` outside the loop. Neither form adds defaults to the codec or owns the world's lease.

The result exposes `chunk` and `chunkNbtMetadata` separately. `EntityChunkNbtDecodeResult` and `PoiChunkNbtDecodeResult`
follow the same pattern. Domain values contain canonical IDs, shared mutable properties and ordinary references, so
application code can compute directly on the returned structures. No subtype registry or lifecycle binding is needed.

World pack loading and its separate Configuration/dimension projection are described
in [Read world data packs](#read-world-data-packs).

`status` retains the stored value; the application decides whether to use a non-full Chunk. Unsupported generation data
is outside the semantic model. Raw documents remain available when that information must be preserved.

## Disk to memory to packets

### Plain API

An application using world-io and protocol-world can compose their plain codecs directly. The following function uses
the stateless store for the standard overworld. Its caller owns access coordination as
described [below](#choose-an-api-layer).
The supplied `update` is application code; for the chest example, pass `::putDiamond` from
[the property-wrapper example](../world-format/README.md#modify-the-decoded-chest).

Construct `worldDirectory` with `"world".toPath()` and use a domain `BlockPosition(x, y, z)` in that world. The callback
receives the decoded server Chunk and that position. For each row, construct the named context, then pass it to its
matching `ChunkNbtDecoder`, `ChunkPacketEncoder` or `ChunkPacketDecoder` constructor:

Construct the three codecs before calling this function:

| Boundary        | Configuration and example                                                                                                                                                     |
|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Disk to Chunk   | [`ChunkNbtDecoderContext`](../world-format/README.md#decode-and-encode-nbt): domain context, NBT format, property readers and tick base                                       |
| Chunk to packet | [`ChunkPacketEncoderContext`](../protocol-world/README.md#chunk-packet-conversion): layout/light/defaults, registry mappings, update-tag projection and missing required data |
| Packet to Chunk | [`ChunkPacketDecoderContext`](../protocol-world/README.md#chunk-packet-conversion): domain context, registry mappings, property readers and missing domain data               |

```kotlin
fun diskToClientChunk(
    worldDirectory: Path,
    blockPosition: BlockPosition,
    chunkNbtDecoder: ChunkNbtDecoder,
    chunkPacketEncoder: ChunkPacketEncoder,
    chunkPacketDecoder: ChunkPacketDecoder,
    update: (Chunk, BlockPosition) -> Unit,
): Chunk? {
    val regionFileStore = RegionFileStore(MinecraftWorldPaths(worldDirectory))
    val decoded = regionFileStore.readChunk(blockPosition.chunkPosition, chunkNbtDecoder) ?: return null
    val chunk = decoded.chunk
    if (!chunk.isFullyGenerated) return null // This application's loading decision.

    update(chunk, blockPosition)
    val packet = chunkPacketEncoder.encode(chunk)
    return chunkPacketDecoder.decode(packet)
}
```

`Path` is an Okio path, domain/NBT types come from `world-format`, and the packet codecs come from `protocol-world`.
The store locates the MCA record and decompresses it into the supplied decoder. The two final calls simulate server
encoding and client decoding in one process; real endpoints use their own negotiated contexts and pass the received
`ClientboundLevelChunkWithLightPacket` to the decoder. Framing and socket transport are separate operations.

### Convenience API

Replace the final two lines of `diskToClientChunk` with the equivalent extension chain, using its existing `chunk`
and prebuilt codecs:

```kotlin
return chunk.toClientboundLevelChunkWithLightPacket(chunkPacketEncoder).toChunk(chunkPacketDecoder)
```

To construct the packet codecs more briefly, [server](../protocol-server/README.md#convert-semantic-chunks-to-packets)
and [client](../protocol-client/README.md#decode-chunk-packets) negotiation results provide `chunkPacketEncoder(...)`
and `chunkPacketDecoder(...)`. They use their retained dimension/registries and accept only the missing application
choices. The filesystem API takes no connection or packet context.

`decoded.chunkNbtMetadata` contains the file's `DataVersion` and `LastUpdate`; neither is a decoder argument. To persist
the modified server Chunk, separately [write it](#write-or-remove-chunks) using a `ChunkNbtEncoder` whose context
supplies
the desired metadata. The returned client Chunk contains only transmitted fields and explicitly supplied local data;
[chest inventory follows menu packets](../protocol-world/README.md#block-entities-and-inventory-packets).

## Choose an API layer

The public stores are stateless building blocks. `RawFileStore`, `NbtFileStore`, and `Utf8JsonFileStore` operate on an
exact caller-supplied path. `LevelDataStore`, `PlayerDataStore`, `SavedDataStore`, `PlayerStatisticsStore`, and
`PlayerAdvancementsStore` add Minecraft path and replacement policy. `RegionFileStore` performs one-shot, uncoordinated
`.mca`/`.mcc` operations and closes every resource before returning.

These stores do not acquire `session.lock`, coordinate concurrent calls, or join a world close lifecycle. Use them when
the caller owns those responsibilities. `MinecraftWorldAccess` composes the same stores with a world lease, logical
resource coordination, and reusable lazy Region handles. `LiveMinecraftWorldAccess` composes their read paths with
live-open semantics but adds no lock, coordinator, registry, or world close state.

Callback-bound sources and sinks are the canonical byte paths. Typed NBT/JSON and complete document/element helpers
attach their parser or serializer directly to that stream; they do not first construct a complete byte array, string, or
intermediate NBT/JSON tree. Complete-value helpers necessarily retain the value they return. Each one-shot store call
owns its own open/close lifetime, so two separate caller operations may open one file twice; a single semantic call
reuses its source for tasks such as saved-data compression detection and decoding.

Every typed NBT and JSON store operation has both an explicit serialization-strategy overload and a reified overload.
The reified overload resolves through the exact `NbtFormat` or `Json` instance's `serializersModule`, including
contextual serializers. The explicit strategy is the final parameter after the arguments shared with the reified
overload. `NbtFileStore` and `Utf8JsonFileStore` capture those format instances at construction instead of accepting a
format on every operation. World access supplies `standaloneNbtFormat` and `standaloneJson` once through its immutable
configuration; data-pack parsing retains its separate `DataPackFormat`. JSON tree operations use the distinct
`readJsonElement` and `writeJsonElement` names.

Standalone world files use unnamed-root NBT. For a `nbtFormat` constructed with
`NbtFormat(NbtFormatConfiguration(...))`,
the plain form is
`NbtFormat(nbtFormat.nbtFormatConfiguration.copy(nbtRootEncoding = NbtRootEncoding.UNNAMED))`. The `forWorldFiles()`
extension performs that configuration step while preserving the serializer module and other mapping settings:

```kotlin
val nbtFormat = NbtFormat(NbtFormatConfiguration(ignoreUnknownKeys = true))
val nbtFileStore = NbtFileStore(nbtFormat = nbtFormat.forWorldFiles())
```

Use this store's `readDocument`/`writeDocument` or typed operations for exact paths. The same configured format can be
passed as `standaloneNbtFormat` to `MinecraftWorldAccessConfiguration` or `LiveMinecraftWorldAccessConfiguration`.
The NBT types come from `nbt-serialization`; default world stores already use the required framing.

Stateless does not mean read-only: a directly constructed `LevelDataStore` may promote `level.dat_old`, and a
`PlayerDataStore` may preserve corrupt evidence. Such policy operations do not acquire a logical lock on the caller's
behalf. The live facade supplies the same stores with a read-only physical capability, which disables those mutations.

## Open an owned world

Open one world lease and reuse it for the complete operation. Each Region handle is also a suspend resource.

`MinecraftWorldAccess.open()` creates the root when necessary and acquires its `session.lock`. Close Region handles
before the world; the nested `use` form does this automatically and preserves cleanup under cancellation.

`openRegion()` itself does not require the Region to exist. Missing reads return `false`, `null`, or an empty list,
while the first write creates storage.

Use the same Okio path and NBT decoder setup as above; construct `BlockPosition(x, y, z)` for the target block.
To start from that absolute position:

```kotlin
suspend fun readBlock(
    worldPath: Path,
    blockPosition: BlockPosition,
    chunkNbtDecoder: ChunkNbtDecoder,
): BlockState? = MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
    minecraftWorldAccess.dimensions.overworld.openRegion(blockPosition.regionPosition).use regionUse@{ regionHandle ->
        val result = regionHandle.readChunk(blockPosition.chunkPosition, chunkNbtDecoder) ?: return@regionUse null
        result.chunk.getBlockState(blockPosition)
    }
}
```

Constructing `ChunkNbtDecoder` and navigating the returned semantic Chunk are covered in
[`world-format`](../world-format/README.md#decode-and-encode-nbt).

## Inspect Region metadata and lower-level values

Region handles expose several layers, so applications need not decode more than they use:

- `hasChunk`, `readChunkCount`, and position lists inspect the Region index;
- `readChunkInfo` adds compression, stored size, and timestamp metadata;
- `readCompressedChunk` returns the exact detached compressed payload;
- `withCompressedChunkSource` streams that payload without retaining another copy;
- `readChunkNbtDocument` returns a generic NBT tree;
- `readChunkNbt` decodes with a caller-selected serializer;
- `readChunk` projects a selected-release semantic Chunk.

```kotlin
suspend fun inspectChunk(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
): RegionChunkInfo? {
    val regionChunkInfo = regionHandle.readChunkInfo(chunkPosition) ?: return null
    check(regionChunkInfo.chunkPosition == chunkPosition)
    return regionChunkInfo
}
```

Metadata and returned lists are detached snapshots. A later call may observe a write that occurred after the snapshot.

Use a borrowed source when a custom incremental consumer is more appropriate:

```kotlin
suspend fun <R> readChunkNbtStream(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    decode: (RegionChunkInfo, BufferedSource) -> R,
): R? = regionHandle.withChunkNbtSource(chunkPosition, decode)
```

Borrowed sources and sinks are valid only inside their callback. Read APIs discard unread source bytes while completing
the underlying bounded or compressed stream; fixed-length write APIs still require the callback to emit the declared
byte count because allocation and framing depend on it.

## Write or remove Chunks

The write API mirrors the same representations. Borrow the `RegionHandle` from `openRegion(...).use` above and
choose a replacement such as `BlockState(BlockId("minecraft:stone"))`. In addition to the read decoder, construct
`ChunkNbtEncoder(ChunkNbtEncoderContext(...))` using the layout, write mappings, tick base and
`ChunkNbtMetadata(dataVersion, lastUpdate)` shown in [world-format](../world-format/README.md#decode-and-encode-nbt):

```kotlin
suspend fun replaceBlock(
    regionHandle: RegionHandle,
    blockPosition: BlockPosition,
    replacement: BlockState,
    chunkNbtDecoder: ChunkNbtDecoder,
    chunkNbtEncoder: ChunkNbtEncoder,
): Boolean {
    val result = regionHandle.readChunk(blockPosition.chunkPosition, chunkNbtDecoder) ?: return false
    val chunk = result.chunk
    chunk.setBlockState(blockPosition, replacement)
    regionHandle.writeChunk(
        chunk = chunk,
        chunkNbtEncoder = chunkNbtEncoder,
        compression = Compression.ZLIB,
    )
    return true
}
```

Other choices are `writeChunkNbtDocument`, serializer-based `writeChunkNbt`, a raw NBT sink callback, and
`writeCompressedChunk` for a payload that is already compressed.

Anvil allocation needs the exact compressed byte count before it can reserve and frame a record. Consequently, document,
serializer, and raw-NBT Region writes retain one final compressed payload before committing it, but they stream the
uncompressed NBT directly into compression and do not retain a second complete uncompressed representation. When the
producer already knows the compressed length, the overload below streams straight to the allocated record without first
creating `CompressedChunk`. Here `source` is a caller-owned Okio `BufferedSource`, for example a file opened with
`FileSystem.SYSTEM.source(path).buffer()`. Supply its compressed payload's exact `byteCount` and actual `Compression`
value (such as `Compression.ZLIB`); do not include the MCA record header:

```kotlin
suspend fun copyCompressedChunk(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    compression: Compression,
    byteCount: Long,
    source: BufferedSource,
) {
    regionHandle.writeCompressedChunk(
        chunkPosition = chunkPosition,
        compression = compression,
        compressedByteCount = byteCount,
    ) { sink ->
        source.readAll(sink)
    }
}
```

The callback must write exactly `byteCount` bytes. The store chooses the timestamp and whether the record is inline or
external.

`removeChunk(chunkPosition)` removes one entry, `clear()` empties an existing Region, and `replaceRegion(...)` replaces
the complete logical set. Omitted positions in a complete replacement are removed. Call `flush()` when the application
needs an explicit durability boundary.

## Reuse Region handles for batches

Take `regionPosition` from `chunkPosition.regionPosition`, or construct `RegionPosition(x, z)`. Open one Region handle
and use one read scope to decode all of its existing Chunks. The callback receiver is a `DecodedChunkRegionReadScope`;
its `chunkPositions` comes from one Region Header read and `readChunk` uses the decoder bound to that scope:

```kotlin
suspend fun readRegionChunks(
    minecraftWorldAccess: MinecraftWorldAccess,
    regionPosition: RegionPosition,
    chunkNbtDecoder: ChunkNbtDecoder,
): List<ChunkNbtDecodeResult> = minecraftWorldAccess.dimensions.overworld.openRegion(regionPosition).use { regionHandle ->
    regionHandle.withReadScope(chunkNbtDecoder) {
        this.chunkPositions.mapNotNull { readChunk(it) }.toList()
    }
}
```

Use the original `withReadScope { readChunk(position, codec) }` form when different codecs must be
mixed within one Header scope. Entity Regions provide the corresponding codec-bound
`DecodedEntityRegionReadScope`.

Ordinary handle calls may be concurrent. Same-Region reads can proceed together, writes serialize, and independent
Regions progress independently. Chunk, Entity, and POI Region directories also have distinct coordination identities, so
their writes do not serialize with one another or with an unrelated `level.dat` write.

Opening a mutable Region handle pins one logical Region without opening a file. Its first operation that needs Region
content lazily opens the `.mca` and retains both that file and its maintained Header state until the final user releases
it. Concurrent handles opened through the same world access for that logical Region share the physical open, and the
last handle or admitted operation closes it. `withReadScope` is therefore a batch admission, not another file cache:
its callback holds one shared-read admission so coordinated writes cannot interleave between the Chunk reads.

Ordinary and live Chunk Region handles expose `RegionReadScope`; Entity and POI Region handles expose
`EntityRegionReadScope` and `PoiRegionReadScope`. All three inherit metadata, compressed payload, decompressed NBT
stream/document, and serializer reads from `AnvilRegionReadScope`. Their `readChunk` methods return only the semantic
type owned by that handle. Values, sequences, and streams borrowed from a scope do not escape its callback.
`replaceRegion { ... }` is the mutable handle's matching staged complete-replacement scope.

On a selected dimension, list existing Chunk, Entity, or POI Regions with `listRegionPositions()`,
`listEntityRegionPositions()`, and `listPoiRegionPositions()`. These return complete detached directory snapshots and
are not transactionally consistent with concurrent external changes.

## Read and write Entities

`openEntityRegion` and the dimension's `entities(entityChunkNbtDecoder)` view reuse a prebuilt decoder. Individual
unbound reads accept a decoder or its complete context. Results expose `entityChunk` and `entityChunkNbtMetadata`.
Writes take the current `EntityChunk` and that operation's `EntityChunkNbtEncoder` or complete context.

The graph contains root Entities and forward passenger references with canonical type IDs and shared properties.
An empty root list removes a saved record only when root properties are also empty; open root data must survive.
Raw compressed/NBT paths have the same names and behavior as terrain Regions.

## Read and write Points of Interest

POI NBT does not contain a Chunk position. A `PoiChunkNbtDecoderContext` therefore includes the selected Region slot's
absolute `chunkPosition`, along with domain context, NBT format and mappings. Supply that complete decoder/context for
each read; the returned `PoiChunk` uses its position without an extra expected-position comparison.

`openPoiRegion` exposes the corresponding semantic and raw operations. Writes take `PoiChunkNbtEncoder` or its complete
context and return no hidden mutation to the POI graph. Section validity, current free tickets and shared type
definitions remain the application's data and computation responsibilities.

## Read a live world without locking it

Use `LiveMinecraftWorldAccess` to observe a world owned by an official server or another process:

```kotlin
fun readLiveChunk(
    worldPath: Path,
    chunkPosition: ChunkPosition,
    chunkNbtDecoder: ChunkNbtDecoder,
): ChunkNbtDecodeResult? {
    val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldPath)
    return liveMinecraftWorldAccess.dimensions.overworld
        .openRegion(chunkPosition.regionPosition)
        .use { liveRegionHandle -> liveRegionHandle.readChunk(chunkPosition, chunkNbtDecoder) }
}
```

`LiveMinecraftWorldAccess` itself owns no shared Region resources and has no `close()`. Each returned
`LiveRegionHandle`, `LiveEntityRegionHandle`, or `LivePoiRegionHandle` is instead a synchronous resource: it
independently opens and retains the `.mca` file found at handle creation, then releases it on `close()` or `use`.
Separate handles do not share a file object, registry, reference count, or lifecycle. Ordinary operations on one handle
reread its Region header; an external `.mcc` sidecar is opened and closed only by the Chunk operation that needs it.

A handle created while its Region path is missing owns no `.mca` resource and returns the usual false, null, or empty
read results; open another handle for a later filesystem observation. Calls on one live handle may run concurrently, but
`close()` does not wait for them, so finish all calls and borrowed callbacks before closing the handle.

Live handles offer the same `withReadScope(chunkNbtDecoder)` batch form shown for mutable handles; it reuses one
header read within the callback.

The cached header is an optimization, not a snapshot promise. Another process may write, delete, replace, or reuse the
referenced files and sectors at any time. Stale or torn combinations and the resulting I/O, Anvil, compression, or NBT
failures are part of the live contract and are propagated to the caller.

Avoid a separate existence check when a following nullable read already answers the question; the direct read has a
smaller observation window. `openEntityRegion` and `openPoiRegion` provide the symmetric Entity and POI paths.

## Read world data packs

Both world access modes expose a `dataPacks` child with the same read-only operations; only the mutable side is
`suspend`. They inspect and read directory or ZIP packs under `datapacks`. The no-argument enabled-pack operations
obtain the complete selection and feature configuration from `level.dat`. The application's `approve` callback
receives each `DataPackInspection` returned by `inspectEnabledFiles()` and decides whether to proceed:

```kotlin
suspend fun readApprovedDataPacks(
    minecraftWorldAccess: MinecraftWorldAccess,
    approve: (DataPackInspection) -> Boolean,
): WorldDataPackLoadResult? {
    val dataPackInspections = minecraftWorldAccess.dataPacks.inspectEnabledFiles()
    if (!dataPackInspections.all(approve)) return null
    return minecraftWorldAccess.dataPacks.readEnabled()
}
```

For a specific pack, construct `DataPackId("file/example.zip")` or `DataPackId("file/example")` for a directory. The
same child exposes its parsed, raw archive, inspection, and borrowed-file forms:

```kotlin
suspend fun readPackMetadata(
    minecraftWorldAccess: MinecraftWorldAccess,
    dataPackId: DataPackId,
): DataPackFileBytes = minecraftWorldAccess.dataPacks.readFile(
    dataPackId,
    DataPackFilePath("pack.mcmeta"),
)
```

`dataPacks.read(dataPackId)` returns the parsed `DataPack`, while `readArchive(dataPackId)` returns the complete raw
`DataPackArchive`; both also accept the result of `inspect` to reuse that inspected file set. A single-pack `DataPackId`
must use the persisted `file/<container-name>` form; the child resolves it inside the world's `datapacks` directory.
`readFile` likewise accepts either the ID for the shortest path or an inspection when the caller first checks sizes.

Inspection exposes paths and declared sizes before file contents are loaded. On-disk data packs are immutable inputs for
the lifetime of their reader use, so `WorldDataPackReader` adds no data-pack read lock or mutation coordinator. The
reader imposes no file-count or size policy.

`WorldDataPackLoadResult` is detached from the filesystem. It retains the complete enabled and disabled `DataPackId`
lists, persisted enabled and removed feature IDs, loaded `file/...` packs, and the enabled IDs that still require a
core, built-in, or loader source. `toDataPackStack` fills those IDs without changing the persisted low-to-high priority
order and reports all missing IDs together. The overloads that accept `List<DataPackId>` skip `level.dat` and therefore
carry no disabled-pack or feature configuration.

Use [datapack-vanilla](../datapack-vanilla/README.md) to complete selected official packs, then
[protocol-configuration-vanilla](../protocol-configuration-vanilla/README.md) to project the stack into Configuration.
The vanilla-neutral stages remain in
[`world-format`](../world-format/README.md#structured-files-and-data-packs) and
[`protocol-configuration`](../protocol-configuration/README.md).

The lower-level `WorldDataPackReader` exposes matching `DataPackId` overloads as well as
`inspectDataPack`/`readDataPack`/`readDataPackArchive` overloads for an explicitly supplied directory or ZIP path. Use
it when the caller owns filesystem and lifetime policy instead of opening a world facade.

Directory entries and Okio ZIP files use the borrowed-source path directly. Kotlin/JS Node is the platform exception:
Okio has no ZIP filesystem there and the maintained `adm-zip` API exposes a decompressed entry only as a complete byte
value, so a selected ZIP entry is materialized once before its borrowed Okio source is presented. Archive-returning
methods necessarily retain each `DataPackFileBytes` value they return on every platform.

## Access an exact file without semantic coordination

Both world facades expose `directFiles` with matching raw, NBT, and structured JSON reads. The mutable version also
exposes writes and makes every call participate in the world's close barrier; the live version is synchronous and
read-only. For example:

```kotlin
suspend fun readUncoordinatedNbt(
    minecraftWorldAccess: MinecraftWorldAccess,
    path: Path,
): NbtDocument = minecraftWorldAccess.directFiles.readNbtDocument(path)
```

The same child provides `readNbt<Model>`/`readJson<Model>`, explicit-strategy and callback forms using the configured
formats. Mutable direct access also provides writes.

The path is used exactly as supplied. It is not resolved below the world root, canonicalized into a logical key, or
checked against `session.lock`, metadata files, Regions, or paths outside the world. Direct calls do not coordinate with
each other or with semantic methods. In particular, changing an `.mca` or `.mcc` behind an open mutable Region handle
can invalidate its retained Header/allocation state. The caller owns every such race; use semantic APIs when coordinated
behavior is required.

## Standalone world, player, and dimension files

`MinecraftWorldAccess` keeps `level.dat` on the world facade. Its `data` child owns namespaced saved data under the root
`data` directory, while its `players` child owns the standard UUID-keyed player files:

- `players/data/<uuid>.dat`, including the selected-release `PlayerData` model;
- `players/stats/<uuid>.json`;
- `players/advancements/<uuid>.json`.

`players.listUuids()` returns a sorted detached snapshot derived only from the current and previous files under
`players/data`; statistics and advancements do not add UUIDs to that list. The mutable operation is `suspend` and joins
the world close lifecycle, while the corresponding live operation is synchronous.

Player APIs provide built-in models and the shared serializer/reified, tree and callback forms described above. Every
read form returns `null` for a missing UUID-keyed file.

A custom schema uses the same operation name with either an explicit serializer or a type argument, such as
`players.readStatistics<ModStatistics>(playerUuid)`. The `dimensions` child owns every dimension-scoped file. Select a
built-in dimension through `overworld`, `nether`, or `end`, or select another namespaced dimension with `DimensionId`:

```kotlin
val overworld = minecraftWorldAccess.dimensions.overworld
val moon = minecraftWorldAccess.dimensions[
    DimensionId(path = "moon", namespace = "example"),
]
```

The root and every selected dimension expose the same `data.read`/`write` operations. This application defines its own
file schema; the returned value is absent when that file does not exist:

```kotlin
@Serializable
data class ModState(val counter: Int)

suspend fun readRootModState(
    minecraftWorldAccess: MinecraftWorldAccess,
): ModState? = minecraftWorldAccess.data.read<ModState>(
    SavedDataId(path = "state", namespace = "example"),
)
```

Root vanilla files deliberately use this same generic API instead of one convenience method per file. Their models are
in `com.hiczp.minecraft.world.format.data`; the default namespace is `minecraft`:

| `SavedDataId.path`   | Strong payload type    |
|----------------------|------------------------|
| `world_gen_settings` | `WorldGenSettingsData` |
| `world_clocks`       | `WorldClocksData`      |
| `weather`            | `WeatherData`          |
| `wandering_trader`   | `WanderingTraderData`  |
| `stopwatches`        | `StopwatchesData`      |
| `scoreboard`         | `ScoreboardData`       |
| `scheduled_events`   | `ScheduledEventsData`  |
| `random_sequences`   | `RandomSequencesData`  |
| `game_rules`         | `GameRulesData`        |
| `custom_boss_events` | `CustomBossEventsData` |
| `maps/last_id`       | `MapIndexData`         |
| `maps/<id>`          | `MapData`              |

Saved-data reads include the `SavedDataFile<T>` envelope. For example:

```kotlin
suspend fun setKeepInventory(minecraftWorldAccess: MinecraftWorldAccess, enabled: Boolean) {
    val savedDataId = SavedDataId("game_rules")
    val savedDataFile = minecraftWorldAccess.data.read<SavedDataFile<GameRulesData>>(savedDataId) ?: return
    minecraftWorldAccess.data.write(
        savedDataId,
        savedDataFile.copy(data = savedDataFile.data.copy(keepInventory = enabled)),
    )
}
```

User-defined saved data can also use a selected dimension's `data` child; the root IDs above stay on `world.data`.
Live access provides the same read family synchronously and without writes.

`data.read`/`data.write` also accept an explicit serializer as their final parameter. `data.readDocument`/
`data.writeDocument` expose NBT documents, while same-named callback overloads lend a decompressed `BufferedSource` or
`BufferedSink`. Missing reads return `null` in every form. Dimension data additionally provides the built-in strong
`readWorldBorderData`, `readChunkTicketsData`, `readRaidsData`, and `readEnderDragonFightData` operations; mutable
access provides same-named writes.

Use raw documents or streams when arbitrary fields outside a provided standalone schema must be retained.

Level reads fall back to `level.dat_old` and mutable access attempts to promote the usable fallback under exclusive
logical admission. Once the fallback has been parsed, an I/O failure while promoting it does not turn that successful
read into a failure, matching the official continuation result. Player reads try the current and previous files; if
neither is usable they return `null`, also matching the official continuation path. Mutable access additionally
preserves a best-effort durable copy of a corrupt current file; it neither promotes nor creates an extra corrupt copy of
`.dat_old`. A later player save installs fresh current data using the normal current-to-previous replacement policy.
Intrinsic binary NBT, compression, and filesystem failures make a candidate unusable; a valid NBT document that merely
does not match the caller's serializer fails normally and never triggers fallback, promotion, or corrupt-copy policy.

## Dimensions, execution, and failures

`DimensionId(path, namespace)` maps only to `dimensions/<namespace>/<path>`. Its namespace defaults to `minecraft`, as
does `SavedDataId`; both validate every path component before filesystem access. This module intentionally supports only
the repository-selected namespaced dimension layout. It does not interpret any root-level Region directory or
`DIM-1`/`DIM1` directory as a dimension.

Neither access mode selects a dispatcher. Filesystem access, compression, and NBT work run in the caller's context, so
move them away from a UI/main thread where required.

Structural Anvil, strong Chunk, strong Entity Chunk, NBT, custom codec, and other program-level failures retain their
owning exception categories. `WorldLockException` reports confirmed world-lease conflicts; `WorldIOException` and
underlying filesystem failures remain in Okio's `IOException` hierarchy.
