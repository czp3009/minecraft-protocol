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

Given an open `MinecraftWorldAccess` and a player UUID, common standalone files need no format or serializer arguments:

```kotlin
val levelData = minecraftWorldAccess.readLevelData()
val playerUuids = minecraftWorldAccess.players.listUuids()
val playerData = minecraftWorldAccess.players.readData(playerUuid)
val playerStatistics = minecraftWorldAccess.players.readStatistics(playerUuid)
val playerAdvancements = minecraftWorldAccess.players.readAdvancements(playerUuid)
val worldDataPackLoadResult = minecraftWorldAccess.dataPacks.readEnabled()
val overworld = minecraftWorldAccess.dimensions.overworld
val chunkTicketsData = overworld.data.readChunkTicketsData()
```

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

Stateless does not mean read-only: a directly constructed `LevelDataStore` may promote `level.dat_old`, and a
`PlayerDataStore` may preserve corrupt evidence. Such policy operations do not acquire a logical lock on the caller's
behalf. The live facade supplies the same stores with a read-only physical capability, which disables those mutations.

## Open an owned world

Open one world lease and reuse it for the complete operation. Each Region handle is also a suspend resource. The
repository's [world quick start](../README.md#read-a-world) shows the basic nested lifetime.

`MinecraftWorldAccess.open()` creates the root when necessary and acquires its `session.lock`. Close Region handles
before the world; the nested `use` form does this automatically and preserves cleanup under cancellation.

`openRegion()` itself does not require the Region to exist. Missing reads return `false`, `null`, or an empty list,
while the first write creates storage.

To start from an absolute Block position:

```kotlin
suspend fun readBlock(
    minecraftWorldAccess: MinecraftWorldAccess,
    blockPosition: BlockPosition,
    chunkNbtCodec: ChunkNbtCodec<BlockStateDescriptor, String>,
): BlockStateDescriptor? = minecraftWorldAccess.dimensions.overworld
    .openRegion(blockPosition.regionPosition)
    .use { regionHandle ->
        val chunk = regionHandle.readChunk(blockPosition, chunkNbtCodec) ?: return@use null
        chunk.block(blockPosition)
    }
```

Constructing `ChunkNbtCodec` and navigating the returned semantic Chunk are covered in
[`world-format`](../world-format/README.md#decode-a-semantic-chunk).

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

Borrowed sources and sinks are valid only inside their callback and must be consumed completely where the method
requires it.

## Write or remove Chunks

The write API mirrors the same representations. To edit a semantic Chunk:

```kotlin
suspend fun replaceBlock(
    regionHandle: RegionHandle,
    blockPosition: BlockPosition,
    replacement: BlockStateDescriptor,
    chunkNbtCodec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Boolean {
    val chunk = regionHandle.readChunk(blockPosition, chunkNbtCodec) ?: return false
    chunk.setBlock(blockPosition, replacement)
    regionHandle.writeChunk(
        chunk = chunk,
        chunkNbtCodec = chunkNbtCodec,
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
creating `CompressedChunk`:

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

Open one Region handle and use one read scope to decode all of its existing Chunks:

```kotlin
suspend fun <B : Any, M : Any> readRegionChunks(
    minecraftWorldAccess: MinecraftWorldAccess,
    regionPosition: RegionPosition,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): List<Chunk<B, M>> = minecraftWorldAccess.dimensions.overworld.openRegion(regionPosition).use { regionHandle ->
    regionHandle.withReadScope {
        this.chunkPositions.mapNotNull { readChunk(it, chunkNbtCodec) }.toList()
    }
}
```

The `this` receiver inside `withReadScope` is a `RegionReadScope`; its `chunkPositions` sequence comes from the Region
Header read for that scope.

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

Entity storage is parallel to Chunk storage and is addressed by the Entity's absolute Chunk position:

```kotlin
suspend fun readEntities(
    minecraftWorldAccess: MinecraftWorldAccess,
    chunkPosition: ChunkPosition,
): EntityChunk<NbtCompound>? {
    val entityChunkNbtCodec = EntityChunkNbtCodec(NbtEntityDataRegistry())
    return minecraftWorldAccess.dimensions.overworld
        .openEntityRegion(chunkPosition.regionPosition).use { entityRegionHandle ->
        entityRegionHandle.readChunk(chunkPosition, entityChunkNbtCodec)
    }
}
```

Region semantic reads carry the stored `DataVersion` into the returned Chunk or Entity Chunk without a compatibility
preflight. `world-io` does not compare it with the repository-selected world version; callers that require a version
policy can inspect an NBT document first or validate the returned value themselves.

`EntityRegionHandle` offers the same metadata, compressed stream/value, NBT document, serializer, semantic read/write,
removal, replacement, flush, and lifecycle operations as `RegionHandle`.

Writing an `EntityChunk` with no root Entities removes its indexed record. Runtime transfer of an Entity between loaded
Entity Chunks remains an application decision.

## Read and write Points of Interest

POI files use the same Anvil container and lifecycle as Chunk and Entity Regions. The high-level POI codec needs no
registry or dimension-layout input, so the handles own it directly:

```kotlin
suspend fun addPoi(
    minecraftWorldAccess: MinecraftWorldAccess,
    poiRecord: PoiRecord,
) {
    minecraftWorldAccess.dimensions.overworld.openPoiRegion(poiRecord.regionPosition).use { poiRegionHandle ->
        val poiChunk = poiRegionHandle.readChunk(poiRecord.chunkPosition)
            ?: PoiChunk(poiRecord.chunkPosition, MinecraftWorldFormat.WORLD_VERSION)
        poiChunk.addRecord(poiRecord)
        poiRegionHandle.writeChunk(poiChunk)
    }
}
```

`PoiRegionHandle` and `LivePoiRegionHandle` expose the same read shapes: metadata, compressed payload, decompressed NBT
stream, document/serializer decoding, semantic `PoiChunk`, and `PoiRegionReadScope`. The mutable handle additionally
exposes the matching writes, removal, replacement, flush, and suspend lifecycle.

## Read a live world without locking it

Use `LiveMinecraftWorldAccess` to observe a world owned by an official server or another process:

```kotlin
fun readLiveChunk(
    worldPath: Path,
    chunkPosition: ChunkPosition,
    chunkNbtCodec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Chunk<BlockStateDescriptor, String>? {
    val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldPath)
    return liveMinecraftWorldAccess.dimensions.overworld
        .openRegion(chunkPosition.regionPosition)
        .use { liveRegionHandle -> liveRegionHandle.readChunk(chunkPosition, chunkNbtCodec) }
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

For several reads from one live Region, `withReadScope` reuses one Header read through semantic decoding:

```kotlin
fun <B : Any, M : Any> readLiveRegionChunks(
    liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    regionPosition: RegionPosition,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): List<Chunk<B, M>> = liveMinecraftWorldAccess.dimensions.overworld
    .openRegion(regionPosition)
    .use { liveRegionHandle ->
        liveRegionHandle.withReadScope {
            this.chunkPositions.mapNotNull { readChunk(it, chunkNbtCodec) }.toList()
        }
    }
```

The cached header is an optimization, not a snapshot promise. Another process may write, delete, replace, or reuse the
referenced files and sectors at any time. Stale or torn combinations and the resulting I/O, Anvil, compression, or NBT
failures are part of the live contract and are propagated to the caller.

Avoid a separate existence check when a following nullable read already answers the question; the direct read has a
smaller observation window. `openEntityRegion` and `openPoiRegion` provide the symmetric Entity and POI paths.

## Read world data packs

Both world access modes expose a `dataPacks` child with the same read-only operations; only the mutable side is
`suspend`. They inspect and read directory or ZIP packs under `datapacks`. The no-argument enabled-pack operations
obtain the complete selection and feature configuration from `level.dat`:

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

For a specific `file/...` pack, the same child exposes its parsed, raw archive, inspection, and borrowed-file forms:

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

Use [`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md) to fill selected release-matched built-ins and
project the complete selection directly into Configuration data. The vanilla-neutral stages remain in
[`world-format`](../world-format/README.md#structured-files-and-data-packs) and
[`protocol-datapack`](../protocol-datapack/README.md).

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

Serializable models can use `directFiles.readNbt<Model>(path)` and `directFiles.readJson<Model>(path)`. Corresponding
overloads accept an explicit deserialization strategy, and mutable direct access also provides both forms for writes.
These operations use the formats captured by the world access configuration; they do not accept per-call format objects.

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

These APIs provide built-in strong models, same-named serializer/reified operations for custom models, NBT documents or
JSON elements, and callback-bound streams. The strong methods do not expose whether the file uses NBT or JSON. They also
do not expose JSON-as-`String` convenience methods; use the structured JSON or raw callback path according to the
representation required by the caller. Because each UUID-keyed player file is optional, every read form returns `null`
when its file is missing.

A custom schema uses the same operation name with either an explicit serializer or a type argument, such as
`players.readStatistics<ModStatistics>(playerUuid)`. The `dimensions` child owns every dimension-scoped file. Select a
built-in dimension through `overworld`, `nether`, or `end`, or select another namespaced dimension with `DimensionId`:

```kotlin
val overworld = minecraftWorldAccess.dimensions.overworld
val moon = minecraftWorldAccess.dimensions[
    DimensionId(path = "moon", namespace = "example"),
]
```

The root and every selected dimension expose the same saved-data operations through their `data` child. Assume
`ModState` is the caller's `@Serializable` model; both locations remain format-independent at the call site:

```kotlin
suspend fun readRootModState(
    minecraftWorldAccess: MinecraftWorldAccess,
): ModState? = minecraftWorldAccess.data.read<ModState>(
    SavedDataId(path = "state", namespace = "example"),
)

suspend fun readOverworldModState(
    minecraftWorldAccess: MinecraftWorldAccess,
): ModState? = minecraftWorldAccess.dimensions.overworld.data.read<ModState>(
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

For example, this reads three different shapes and performs a strongly typed mutable write without exposing NBT at the
call site:

```kotlin
suspend fun editRootSavedData(
    minecraftWorldAccess: MinecraftWorldAccess,
    mapId: Int,
) {
    val gameRulesId = SavedDataId("game_rules")
    val gameRules = checkNotNull(
        minecraftWorldAccess.data.read<SavedDataFile<GameRulesData>>(gameRulesId),
    )
    minecraftWorldAccess.data.write(
        gameRulesId,
        gameRules.copy(data = gameRules.data.copy(keepInventory = true)),
    )

    val worldClocks = minecraftWorldAccess.data.read<SavedDataFile<WorldClocksData>>(
        SavedDataId("world_clocks"),
    )
    val map = minecraftWorldAccess.data.read<SavedDataFile<MapData>>(
        SavedDataId("maps/$mapId"),
    )
}
```

Every file is optional, so `worldClocks` and `map` above are nullable and `checkNotNull` is only an application choice.
The matching live calls use the same `data.read<T>(SavedDataId)` form; live access has no writes.

`data.read`/`data.write` also accept an explicit serializer as their final parameter. `data.readDocument`/
`data.writeDocument` expose NBT documents, while same-named callback overloads lend a decompressed `BufferedSource` or
`BufferedSink`. Missing reads return `null` in every form. Dimension data additionally provides the built-in strong
`readWorldBorderData`, `readChunkTicketsData`, `readRaidsData`, and `readEnderDragonFightData` operations; mutable
access provides same-named writes.

The live access class exposes every corresponding read-only operation, including its own `players` child, with the same
names and parameters. Its only shape differences are the absence of `suspend`, writes, and world-close ownership. Use
`NbtDocument`, `NbtTag`, `JsonElement`, or stream entry points when arbitrary modded or future fields must be retained.

For the repository-selected layout, each dimension can therefore use coordinated and live forms of all four on-disk
families: `region/*.mca`, `entities/*.mca`, `poi/*.mca`, and `data/<namespace>/<path>.dat`. The player directory has the
same paired strong/generic/stream paths for `players/data/*.dat`, plus typed JSON access for advancements and
statistics.

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
