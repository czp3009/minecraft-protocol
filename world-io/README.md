# world-io

`world-io` provides Okio-backed access to Minecraft world directories. Its API follows the logical objects applications
work with:

```text
world -> Chunk Region -> Chunk -> Section -> block or biome
world -> Entity Region -> Entity Chunk -> Entity -> passengers
```

Physical `.mca`/`.mcc` details are hidden behind Region handles. Filesystem-independent coordinates, compression, NBT
composition, Anvil containers, and semantic values come from [`world-format`](../world-format/README.md).

Two access modes serve different situations:

| Access                     | Use when                                     | Behavior                                                                              |
|----------------------------|----------------------------------------------|---------------------------------------------------------------------------------------|
| `MinecraftWorldAccess`     | This process owns the world                  | Acquires `session.lock`, supports reads and writes, and has a suspend close lifecycle |
| `LiveMinecraftWorldAccess` | Another process may own and change the world | Takes no lock, never mutates or repairs, and has no close lifecycle                   |

Filesystem support is configured for JVM, Android, supported Native targets, and Kotlin/JS Node. Browser and Wasm
applications should use the filesystem-independent modules.

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
    world: MinecraftWorldAccess,
    position: BlockPosition,
    codec: ChunkNbtCodec<BlockStateDescriptor, String>,
): BlockStateDescriptor? = world.openRegion(position.region).use { region ->
    val chunk = region.readChunk(position, codec) ?: return@use null
    chunk.block(position)
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
    region: RegionHandle,
    position: ChunkPosition,
): RegionChunkInfo? {
    val info = region.readChunkInfo(position) ?: return null
    check(info.position == position)
    return info
}
```

Metadata and returned lists are detached snapshots. A later call may observe a write that occurred after the snapshot.

Use a borrowed source when a custom incremental consumer is more appropriate:

```kotlin
suspend fun <R> readChunkNbtStream(
    region: RegionHandle,
    position: ChunkPosition,
    decode: (RegionChunkInfo, Source) -> R,
): R? = region.withChunkNbtSource(position, decode)
```

Borrowed sources and sinks are valid only inside their callback and must be consumed completely where the method
requires it.

## Write or remove Chunks

The write API mirrors the same representations. To edit a semantic Chunk:

```kotlin
suspend fun replaceBlock(
    region: RegionHandle,
    position: BlockPosition,
    replacement: BlockStateDescriptor,
    codec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Boolean {
    val chunk = region.readChunk(position, codec) ?: return false
    chunk.setBlock(position, replacement)
    region.writeChunk(
        chunk = chunk,
        codec = codec,
        compression = Compression.ZLIB,
    )
    return true
}
```

Other choices are `writeChunkNbtDocument`, serializer-based `writeChunkNbt`, a raw NBT sink callback, and
`writeCompressedChunk` for a payload that is already compressed.

When a producer knows the exact compressed length, it can stream the record without first creating `CompressedChunk`:

```kotlin
suspend fun copyCompressedChunk(
    region: RegionHandle,
    position: ChunkPosition,
    compression: Compression,
    byteCount: Long,
    source: Source,
) {
    region.writeCompressedChunk(
        position = position,
        compression = compression,
        compressedByteCount = byteCount,
    ) { sink ->
        source.transferTo(sink)
    }
}
```

The callback must write exactly `byteCount` bytes. The store chooses the timestamp and whether the record is inline or
external.

`removeChunk(position)` removes one entry, `clear()` empties an existing Region, and `replaceRegion(...)` replaces the
complete logical set. Omitted positions in a complete replacement are removed. Call `flush()` when the application needs
an explicit durability boundary.

## Reuse Region handles for batches

Do not open a world or Region inside every per-Chunk iteration. Group positions by Region and reuse one handle:

```kotlin
suspend fun <B : Any, M : Any> loadChunks(
    world: MinecraftWorldAccess,
    positions: Iterable<ChunkPosition>,
    codec: ChunkNbtCodec<B, M>,
): Map<ChunkPosition, Chunk<B, M>> = buildMap {
    for ((regionPosition, regionPositions) in positions.groupBy(ChunkPosition::region)) {
        world.openRegion(regionPosition).use { region ->
            for (position in regionPositions) {
                region.readChunk(position, codec)?.let { chunk -> put(position, chunk) }
            }
        }
    }
}
```

Ordinary handle calls may be concurrent. Same-Region reads can proceed together, writes serialize, and independent
Regions progress independently.

`withReadScope` is available when several reads need one Region admission and one consistent header snapshot.
`replaceRegion { ... }` is the matching staged complete-replacement scope. Values, sequences, and streams borrowed from
either scope do not escape its callback.

List existing map or Entity Regions with `listRegionPositions()` and `listEntityRegionPositions()`. These return
complete detached directory snapshots and are not transactionally consistent with concurrent external changes.

## Read and write Entities

Entity storage is parallel to Chunk storage and is addressed by the Entity's absolute Chunk position:

```kotlin
suspend fun readEntities(
    world: MinecraftWorldAccess,
    position: ChunkPosition,
    expectedDataVersion: Int,
): EntityChunk<NbtCompound>? {
    val entityChunkNbtCodec = EntityChunkNbtCodec(expectedDataVersion, NbtEntityDataRegistry())
    return world.openEntityRegion(position.region).use { region ->
        region.readChunk(position, entityChunkNbtCodec)
    }
}
```

`EntityRegionHandle` offers the same metadata, compressed stream/value, NBT document, serializer, semantic read/write,
removal, replacement, flush, and lifecycle operations as `RegionHandle`.

Writing an `EntityChunk` with no root Entities removes its indexed record. Runtime transfer of an Entity between loaded
Entity Chunks remains an application decision.

## Read a live world without locking it

Use `LiveMinecraftWorldAccess` to observe a world owned by an official server or another process:

```kotlin
fun readLiveChunk(
    worldPath: Path,
    position: ChunkPosition,
    codec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Chunk<BlockStateDescriptor, String>? {
    val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldPath)
    val liveRegionHandle = liveMinecraftWorldAccess.openRegion(position.region)
    return liveRegionHandle.readChunk(position, codec)
}
```

Live access is read-only and has no `close()`. Each call opens and closes the physical resources it needs. Since another
process may write, delete, or replace files during a call, stale or torn data and the resulting I/O, Anvil, compression,
or NBT failures are part of the contract.

Avoid a separate existence check when a following nullable read already answers the question; the direct read has a
smaller observation window. `openEntityRegion` provides the symmetric Entity path.

## Read world data packs

Both world access modes can inspect and read enabled directory or ZIP packs under `datapacks`. The enabled order comes
from `level.dat`:

```kotlin
suspend fun readApprovedDataPacks(
    world: MinecraftWorldAccess,
    approve: (DataPackInspection) -> Boolean,
): WorldDataPackLoadResult? {
    val dataPackInspections = world.inspectEnabledFileDataPacks()
    if (!dataPackInspections.all(approve)) return null
    return world.readEnabledDataPacks()
}
```

Inspection exposes paths and declared sizes before file contents are loaded. On-disk data packs are immutable inputs for
the lifetime of their reader use, so `WorldDataPackReader` adds no data-pack read lock or mutation coordinator. The
reader imposes no file-count or size policy.

`WorldDataPackLoadResult.dataPackStack` contains loaded `file/...` entries in low-to-high priority order. Non-file
references such as `vanilla` remain in `unresolvedDataPackReferences` for a higher layer to supply; the original ordered
selection remains in `enabledDataPackReferences`. Use [`protocol-datapack`](../protocol-datapack/README.md) or
[`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md) to project the stack into Configuration data.

`WorldDataPackReader` also exposes `inspectDataPack`, `readDataPack`, `readDataPackArchive`, and `readDataPackFile` for
an explicitly selected directory or ZIP without opening a mutable world lease.

## Other world files

`MinecraftWorldAccess` provides typed, generic-document/text, and stream operations for:

- `level.dat`;
- player NBT by UUID;
- dimension-scoped saved data by identifier;
- player statistics JSON;
- player advancements JSON.

For example, the built-in selected-release model can be read directly:

```kotlin
suspend fun readLevelData(world: MinecraftWorldAccess): LevelDat =
    world.readLevelData()
```

The live access class exposes the corresponding read-only operations. Use `NbtDocument` or text/stream entry points when
arbitrary modded or future fields must be retained.

## Dimensions, execution, and failures

Region methods default to `DimensionDirectory.Overworld`. Pass `DimensionDirectory.Nether`, `DimensionDirectory.End`, or
a validated custom dimension directory for another dimension. `LegacyOverworld`, `LegacyNether`, and `LegacyEnd` are
explicit opt-ins for the older root/`DIM-1`/`DIM1` directory layout; the ordinary built-in values use the
repository-selected release's namespaced dimension paths.

Neither access mode selects a dispatcher. Filesystem access, compression, and NBT work run in the caller's context, so
move them away from a UI/main thread where required.

Structural Anvil, strong Chunk, strong Entity Chunk, NBT, custom codec, and underlying filesystem failures retain their
owning exception categories. `WorldLockException` reports confirmed world-lease conflicts; `WorldIOException` reports
world/filesystem policy failures.
