# world-io

`world-io` provides Okio-backed access to Minecraft world directories. Its public APIs follow the logical hierarchies
users work with:

```text
world access -> Region handle -> Chunk -> Section -> block or biome
world access -> Entity Region handle -> Entity Chunk -> Entity -> passengers
```

The module deliberately hides how a Region is split across physical files. Filesystem-independent coordinates,
compression, Anvil containers, NBT composition, and semantic Chunk models belong to
[`world-format`](../world-format/README.md).

## Read a block through the mutable path

Use `MinecraftWorldAccess` when this process owns the world. `open` creates the world directory when necessary and
immediately acquires its vanilla `session.lock`. `MinecraftWorldAccess`, `RegionHandle`, and `EntityRegionHandle`
provide plain suspend `close()` methods and a suspend `use {}` shortcut. Close every Region handle before closing the
world access; the world close waits for outstanding handles and releases `session.lock` last.

The following example starts with only an absolute block coordinate, derives its Region and Chunk coordinates, inspects
stored metadata, decodes the semantic Chunk, and finally reads the block. Every coordinate along the path remains
available to the caller.

```kotlin
suspend fun readBlockStateDescriptor(
    worldPath: Path,
    blockPosition: BlockPosition,
    chunkLayout: ChunkLayout,
    expectedDataVersion: Int,
): BlockStateDescriptor? {
    return MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
        val chunkPosition = blockPosition.chunk
        val regionPosition = blockPosition.region
        minecraftWorldAccess.openRegion(regionPosition).use regionUse@{ regionHandle ->
            if (!regionHandle.hasRegion()) return@regionUse null
            if (!regionHandle.hasChunk(blockPosition)) return@regionUse null

            val regionChunkInfo = regionHandle.readChunkInfo(chunkPosition) ?: return@regionUse null
            val compression = regionChunkInfo.compression
            val compressedByteCount = regionChunkInfo.compressedByteCount
            val timestampEpochSeconds = regionChunkInfo.timestampEpochSeconds

            val chunkDataRegistries = ChunkDataRegistries(
                blockStates = DescriptorBlockStateRegistry(),
                biomes = NamedBiomeRegistry(),
            )
            val chunkNbtContext = ChunkNbtContext(
                layout = chunkLayout,
                registries = chunkDataRegistries,
                expectedDataVersion = expectedDataVersion,
            )
            val chunkNbtCodec = ChunkNbtCodec(chunkNbtContext)
            val chunk = regionHandle.readChunk(blockPosition, chunkNbtCodec) ?: return@regionUse null

            val chunkMetadata = chunk.metadata
            check(chunkMetadata.dataVersion == expectedDataVersion)
            check(chunk.position == chunkPosition)

            val blockStateDescriptor = chunk.block(blockPosition)
            blockStateDescriptor
        }
    }
}
```

The local variables `compression`, `compressedByteCount`, and `timestampEpochSeconds` show the metadata available before
NBT or palettes are decoded. `RegionChunkInfo` also exposes `region`, `localPosition`, and the derived absolute
`position`. Physical sector locations and external-file placement are intentionally absent.

`Chunk.position` retains the absolute Chunk coordinate encoded by its NBT and validated against the selected Region
entry. `Chunk.metadata` contains the other semantic fields such as data version, status, update time, inhabited time,
lighting, heightmaps, ticks, and structures; `Chunk.blockEntities` contains the separately modeled absolute Block Entity
values. Compression, stored size, and timestamp remain properties of the stored Region record rather than the semantic
Chunk.

`openRegion` itself performs no filesystem I/O. It returns a handle even when the Region does not exist. Missing reads
return `false`, `null`, or an empty list, and the first write creates the Region. In code that only needs one Chunk,
calling `readChunkInfo` directly is sufficient; the separate existence checks above are included to demonstrate the
available operations.

## Inspect Region metadata

`hasChunk`, `readChunkCount`, `readLocalChunkPositions`, and `readChunkPositions` inspect only the Region index. They do
not open Chunk streams, read record headers, inspect external content, or decode NBT. The count and detached coordinate
lists therefore remain cheap even when the Chunk records are large:

```kotlin
suspend fun readRegionChunkCount(regionHandle: RegionHandle): Int {
    val chunkCount = regionHandle.readChunkCount()
    val localChunkPositions = regionHandle.readLocalChunkPositions()
    check(chunkCount == localChunkPositions.size)
    return chunkCount
}
```

Use the absolute coordinate list to traverse every Chunk named by one Region index. The local counterpart remains
available when Region-local addressing is more natural:

```kotlin
suspend fun <B : Any, M : Any> visitRegionChunks(
    regionHandle: RegionHandle,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
    visitChunk: (Chunk<B, M>) -> Unit,
) {
    for (chunkPosition in regionHandle.readChunkPositions()) {
        val chunk = regionHandle.readChunk(chunkPosition, chunkNbtCodec) ?: continue
        check(chunk.position == chunkPosition)
        visitChunk(chunk)
    }
}
```

The coordinate list is one header snapshot. A later Chunk read can still return `null` if the record is unavailable or
the Region changes between calls, which is why the traversal handles that result explicitly.

Every Region metadata result is a detached snapshot, including the count, coordinate list, `RegionChunkInfo`, and its
lists. If any coroutine writes, removes, or replaces content in that Region, values obtained before the write may be
stale; call the corresponding read method again when current metadata is required. Keeping the same `RegionHandle` open
does not make earlier results live and does not hold one lock across calls.

`readChunkInfo` goes one level deeper and reads a selected Chunk's stored compression metadata. It accepts either a
Region-local or absolute Chunk position. `readChunkInfos` performs that work for the complete Region and materializes a
detached list in deterministic Region-local order:

```kotlin
suspend fun readRegionChunkInfos(regionHandle: RegionHandle): List<RegionChunkInfo> {
    val regionChunkInfos = regionHandle.readChunkInfos()
    return regionChunkInfos
}
```

All returned lists and metadata remain valid after the handle closes. Use `withReadScope` only when several reads must
share one Region admission and one consistent header snapshot; that advanced form is covered later.

## Read Entities by Chunk

Entities are not stored in `level.dat`. They are indexed by absolute Chunk position in the dimension's Entity Regions.
Open them through `openEntityRegion`; the handle has the same Region metadata, compressed-stream, generic-NBT, and
semantic conversion layers as `RegionHandle`:

```kotlin
suspend fun readEntityChunk(
    worldPath: Path,
    chunkPosition: ChunkPosition,
    expectedDataVersion: Int,
): EntityChunk<NbtCompound>? = MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
    val regionPosition = chunkPosition.region
    minecraftWorldAccess.openEntityRegion(regionPosition).use entityRegionUse@{ entityRegionHandle ->
        if (!entityRegionHandle.hasChunk(chunkPosition)) return@entityRegionUse null

        val regionChunkInfo = entityRegionHandle.readChunkInfo(chunkPosition) ?: return@entityRegionUse null
        check(regionChunkInfo.position == chunkPosition)

        val entityDataRegistry = NbtEntityDataRegistry()
        val entityChunkNbtCodec = EntityChunkNbtCodec(expectedDataVersion, entityDataRegistry)
        val entityChunk = entityRegionHandle.readChunk(chunkPosition, entityChunkNbtCodec)
        entityChunk
    }
}
```

`EntityChunk` is detached and mutable, and its `position` retains the absolute Chunk coordinate stored in its NBT. Its
root list preserves the persisted passenger hierarchy; `allEntities()` visits roots and recursive passengers in load
order. Each `Entity<E>` has absolute position, velocity,
rotation, UUID, persistent type, caller-selected subtype `data`, passenger operations, and
`blockPosition`/`sectionPosition`/`chunkPosition`/`regionPosition` conveniences. The example uses
`NbtEntityDataRegistry`, so type-specific vanilla and mod fields remain losslessly available as `entity.data`.

The lower representations remain reachable from the handle and from each detached value:

```kotlin
suspend fun <E : Any> readEntityChunkThroughEveryLayer(
    entityRegionHandle: EntityRegionHandle,
    chunkPosition: ChunkPosition,
    entityChunkNbtCodec: EntityChunkNbtCodec<E>,
): EntityChunk<E>? {
    val compressedChunk = entityRegionHandle.readCompressedChunk(chunkPosition) ?: return null
    val nbtDocument = compressedChunk.toNbtDocument()
    val entityChunk = nbtDocument.toEntityChunk(entityChunkNbtCodec)
    check(entityChunk.position == chunkPosition)
    return entityChunk
}
```

For a no-copy streaming handoff, use `withCompressedChunkSource` or `readCompressedChunkTo`. Use `withChunkNbtSource`
or `readChunkNbtTo` for the decompressed unnamed-root NBT stream. `readCompressedChunk` materializes only the selected
record; `readChunkNbtDocument` materializes its complete generic tree; `readChunk` produces semantic Entities.

Writes mirror the same layers. `writeChunk` persists an `EntityChunk`, while `writeChunkNbtDocument`,
`writeChunkNbt`, and `writeCompressedChunk` accept progressively lower representations. A write locks the complete
logical Entity Region against other reads and writes through this world access. It updates only the selected Chunk
record and Region index entry; unrelated Entity Chunks are not rewritten. Writing a semantic Entity Chunk with no root
Entities removes that indexed record, matching the official Entity storage path.

The public API treats each Entity Region as one logical resource. All of its reads and writes share the same
Region-granularity coordination. As with map Regions, metadata obtained before any write may be stale and must be read
again when current state matters.

## Load a 21 by 21 Chunk area

The following example accepts the player's continuous world position, calculates its containing Block, Chunk, and
Region, then calculates the 441 absolute Chunk positions in a 21 by 21 square centered on that Chunk. It groups the
positions by Region and loads every available strong `Chunk` into memory.

```kotlin
data class LoadedChunkArea<B : Any, M : Any>(
    val requestedChunkPositions: List<ChunkPosition>,
    val regionPositions: List<RegionPosition>,
    val chunks: Map<ChunkPosition, Chunk<B, M>>,
)

suspend fun <B : Any, M : Any> loadChunksAroundPlayer(
    worldPath: Path,
    playerX: Double,
    playerY: Double,
    playerZ: Double,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): LoadedChunkArea<B, M> {
    val viewRadius = 10
    val playerBlockPosition = MinecraftCoordinates.block(playerX, playerY, playerZ)
    val playerChunkPosition = playerBlockPosition.chunk
    val playerRegionPosition = playerChunkPosition.region
    check(playerBlockPosition.region == playerRegionPosition)
    val requestedChunkPositions = playerChunkPosition.positionsAround(viewRadius).toList()
    check(requestedChunkPositions.size == 21 * 21)

    val chunkPositionsByRegion = requestedChunkPositions.groupBy(ChunkPosition::region)
    val regionPositions = chunkPositionsByRegion.keys.toList()
    val chunks = MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
        buildMap {
            for (regionPosition in regionPositions) {
                val regionChunkPositions = chunkPositionsByRegion.getValue(regionPosition)
                minecraftWorldAccess.openRegion(regionPosition).use regionUse@{ regionHandle ->
                    if (!regionHandle.hasRegion()) return@regionUse

                    for (chunkPosition in regionChunkPositions) {
                        val chunk = regionHandle.readChunk(chunkPosition, chunkNbtCodec) ?: continue
                        put(chunkPosition, chunk)
                    }
                }
            }
        }
    }

    return LoadedChunkArea(
        requestedChunkPositions = requestedChunkPositions,
        regionPositions = regionPositions,
        chunks = chunks,
    )
}
```

`MinecraftCoordinates.block`, `BlockPosition.chunk`, `ChunkPosition.region`, and `positionsAround` delegate to the same
checked coordinate implementation, so the grouping remains correct when any coordinate is negative and cannot silently
wrap at an `Int` edge. A 21 by 21 square can touch one, two, or four Regions. The world lease is opened once, each
Region handle is created once for its complete group, and no `openRegion` call occurs in the per-Chunk loop. Physical
Region storage is opened lazily by the first Chunk read and retained until that Region's `use` block ends. Missing
Regions and missing Chunks are omitted from `chunks`, while `requestedChunkPositions` still contains all 441 requested
positions.

This example intentionally retains every decoded `Chunk` in memory. For a large radius or a long-running scan, process
each Chunk inside the loop instead of adding it to the result Map.

For the complete scalar, absolute/local, reverse, range, Section-block, Region-Chunk, and biome-quart coordinate API,
see the coordinate section in [`world-format`](../world-format/README.md). `MinecraftCoordinates` owns the formulas; the
position types expose fluent convenience methods over that one implementation.

## Navigate Chunk, Section, and palette data

The basic adjacent conversions are available on the coordinate values themselves: Region plus local Chunk to absolute
Chunk, Chunk plus local block to absolute block, and Section plus local block to absolute block. The semantic `Chunk`
also accepts either its natural Chunk-local coordinate or the common absolute `BlockPosition`/`SectionPosition` paths.

```kotlin
fun inspectChunkBlock(
    chunk: Chunk<BlockStateDescriptor, String>,
    blockPosition: BlockPosition,
): BlockStateDescriptor {
    val chunkBlockPosition = chunk.position.local(blockPosition)
    val localBlockStateDescriptor = chunk.block(chunkBlockPosition)
    val absoluteBlockStateDescriptor = chunk.block(blockPosition)
    check(localBlockStateDescriptor == absoluteBlockStateDescriptor)

    val sectionPosition = blockPosition.section
    val chunkSection = chunk.section(blockPosition)
    check(chunkSection == chunk.section(sectionPosition))
    if (chunkSection != null) {
        val localBlockPosition = sectionPosition.local(blockPosition)
        val sectionBlockStateDescriptor = chunkSection.block(localBlockPosition)
        val absoluteSectionBlockStateDescriptor = chunkSection.block(sectionPosition, blockPosition)
        check(sectionBlockStateDescriptor == absoluteSectionBlockStateDescriptor)

        val paletteSnapshot = chunkSection.blockStates.paletteSnapshot()
        val denseBlockStates = chunkSection.toDenseBlockStates()
        check(paletteSnapshot.entryCount == denseBlockStates.size)
    }

    return absoluteBlockStateDescriptor
}
```

The strong `Chunk` has already unpacked palette IDs. `chunk.block(...)` and `chunkSection.block(...)` return the logical
block-state value selected by the palette, not a raw palette index. `paletteSnapshot()` exposes the current values and
logical bit width for diagnostics. `toDenseBlockStates()` and `toDenseBiomes()` explicitly allocate dense lists;
ordinary indexed access does not.

The example uses the open `DescriptorBlockStateRegistry` and `NamedBiomeRegistry`, so values are represented by their
persistent descriptors and names. Applications may instead implement `BlockStateRegistry` and `BiomeRegistry` around
vanilla data, mod data, or a combined catalogue. `world-io` and `world-format` do not depend on
`protocol-vanilla-data`; applications that want it add that module themselves.

## Follow lower-level Chunk representations

`readChunk` is the shortest path when only blocks or biomes matter. When an application needs to retain or inspect each
lower representation, it can start with the exact compressed content and continue from the returned value through IDE
completion:

```kotlin
suspend fun <B : Any, M : Any> readChunkLayers(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): Chunk<B, M>? {
    val compressedChunk = regionHandle.readCompressedChunk(chunkPosition) ?: return null
    val nbtDocument = compressedChunk.toNbtDocument(regionHandle.chunkNbtFormat)
    val chunk = nbtDocument.toChunk(chunkNbtCodec)
    check(chunk.position == chunkPosition)
    return chunk
}
```

The three detached values have distinct responsibilities:

- `CompressedChunk` contains the original compressed payload and compression algorithm, but no Region metadata.
- `NbtDocument` contains the complete generic NBT tree and therefore has no unknown-field problem.
- `Chunk<B, M>` contains selected-release semantic metadata, Sections, and decoded palette-backed block/biome values.

`readChunkNbtDocument` and `readChunk` are direct read shortcuts to the latter two results. They do not first
materialize a `CompressedChunk`; the fluent chain is available when the caller intentionally retains an earlier value.
`readChunkNbtDocument` necessarily materializes the requested tree. The current strong `readChunk` implementation also
projects through that tree before returning the semantic value.

### Detached compressed content

`readCompressedChunk` returns the exact compressed payload together with its algorithm. The result is detached and can
be retained or written elsewhere without keeping a Region lock or file open:

```kotlin
suspend fun readCompressedChunk(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
): CompressedChunk? {
    val compressedChunk = regionHandle.readCompressedChunk(chunkPosition)
    return compressedChunk
}
```

This is useful for backups, replication, checksums, or repacking without decoding NBT. When retaining the complete
payload is unnecessary, stream it instead:

```kotlin
suspend fun copyCompressedChunk(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    compressedSink: Sink,
): RegionChunkInfo? = regionHandle.readCompressedChunkTo(chunkPosition, compressedSink)
```

`readCompressedChunkTo` is a thin adapter over `withCompressedChunkSource`. Use the callback form when a custom parser
or incremental transformation needs the borrowed `Source`. It must be consumed completely inside the callback and must
not escape it; the callback keeps the internal read admission and physical resource alive for exactly that lifetime.

### Universal NBT tree

`NbtDocument` is the generic NBT-tree path. Every legal NBT tag can be represented, so this path does not have an
“unknown field” problem. It is the appropriate choice when modded or future fields must survive a semantic round trip:

```kotlin
suspend fun rewriteChunkNbtDocument(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    compression: Compression,
): NbtDocument? {
    val nbtDocument = regionHandle.readChunkNbtDocument(chunkPosition) ?: return null
    regionHandle.writeChunkNbtDocument(chunkPosition, nbtDocument, compression)
    return nbtDocument
}
```

The strong `ChunkNbtCodec` validates the selected-release fields, layout, version, and registry values, but it does not
retain tags outside its semantic `Chunk` model. Use the document path for lossless arbitrary tags and the strong path
for block/biome-aware editing.

`withChunkNbtSource` exposes the complete decompressed unnamed-root binary NBT stream to a caller-selected parser.
`readChunkNbtTo` copies that stream directly to a caller-owned `Sink`, while `readChunkNbt(deserializer)` is the
ordinary serializer adapter over the same representation.

```kotlin
suspend fun copyChunkNbt(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    nbtSink: Sink,
): RegionChunkInfo? = regionHandle.readChunkNbtTo(chunkPosition, nbtSink)
```

```kotlin
suspend fun <T> readTypedChunkNbt(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    deserializationStrategy: DeserializationStrategy<T>,
): T? {
    val value = regionHandle.readChunkNbt(chunkPosition, deserializationStrategy)
    return value
}
```

## Write a Chunk

The basic write methods mirror the read representations. They are ordinary `RegionHandle` methods; a DSL is not
required.

```kotlin
suspend fun replaceBlock(
    regionHandle: RegionHandle,
    blockPosition: BlockPosition,
    replacementBlockStateDescriptor: BlockStateDescriptor,
    chunkNbtCodec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Chunk<BlockStateDescriptor, String>? {
    val chunk = regionHandle.readChunk(blockPosition, chunkNbtCodec) ?: return null
    chunk.setBlock(blockPosition, replacementBlockStateDescriptor)
    regionHandle.writeChunk(
        chunk = chunk,
        codec = chunkNbtCodec,
        compression = Compression.ZLIB,
    )
    return chunk
}
```

`writeChunk`, serializer-based `writeChunkNbt`, and `writeChunkNbtDocument` encode and retain one compressed Chunk
before entering the exclusive Region commit. Anvil allocation needs the exact compressed length, so these methods
neither encode twice nor keep a second complete uncompressed byte array.

The exclusive commit covers allocation, the selected Chunk payload, Region metadata, and any internally managed external
content as one Region-level critical section. Mutable metadata and Chunk reads use the matching shared boundary, so an
in-process reader waits and observes either the state before the commit or the state after it, never an in-progress
header. Encoding may happen before exclusive access because it has not modified the Region yet.

A single-Chunk write is positional. It writes storage for that Chunk, commits the fixed Region index, and retires the
old allocation; it does not copy, replace, truncate, or rewrite the complete Region, and unrelated Chunks remain in
place. `replaceRegion` is the explicit operation whose contract replaces the complete logical Chunk set.

If a complete compressed value is already available, write it directly:

```kotlin
suspend fun writeCompressedChunk(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    compressedChunkInput: CompressedChunkInput,
) {
    regionHandle.writeCompressedChunk(chunkPosition, compressedChunkInput)
}
```

A producer that already knows the exact compressed length can avoid materializing a `CompressedChunk`:

```kotlin
suspend fun writeKnownLengthCompressedChunk(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    compression: Compression,
    compressedByteCount: Long,
    compressedSource: Source,
) {
    regionHandle.writeCompressedChunk(
        position = chunkPosition,
        compression = compression,
        compressedByteCount = compressedByteCount,
    ) { compressedSink ->
        compressedSource.transferTo(compressedSink)
    }
}
```

The callback must write exactly `compressedByteCount` bytes. The library chooses timestamps and internal storage
placement; callers do not supply physical placement information.

For a custom uncompressed NBT producer, use the raw NBT sink. It is also a callback because the compressor must be
closed before the exact compressed size is known:

```kotlin
suspend fun writeChunkNbtStream(
    regionHandle: RegionHandle,
    chunkPosition: ChunkPosition,
    nbtDocument: NbtDocument,
) {
    regionHandle.writeChunkNbt(chunkPosition, Compression.ZLIB) { nbtSink ->
        regionHandle.chunkNbtFormat.nbt.encodeDocumentToSink(nbtDocument, nbtSink)
    }
}
```

Borrowed sources and sinks are closed by the library and must not escape their callbacks. `removeChunk` removes one
Chunk, `clear` empties the Region, and `flush` completes its pending durable write work.

## Lifecycle shortcuts and batch operations

The explicit `open`/`openRegion` and suspend `close()` methods expose the complete lifecycle directly:

```kotlin
suspend fun readChunkInfoWithExplicitClose(
    worldPath: Path,
    chunkPosition: ChunkPosition,
): RegionChunkInfo? {
    val minecraftWorldAccess = MinecraftWorldAccess.open(worldPath)
    try {
        val regionHandle = minecraftWorldAccess.openRegion(chunkPosition.region)
        return try {
            regionHandle.readChunkInfo(chunkPosition)
        } finally {
            regionHandle.close()
        }
    } finally {
        minecraftWorldAccess.close()
    }
}
```

`use` provides structured, cancellation-safe cleanup for the same lifecycle and is the usual form:

```kotlin
suspend fun readChunkInfoWithStructuredClose(
    worldPath: Path,
    chunkPosition: ChunkPosition,
): RegionChunkInfo? = MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
    minecraftWorldAccess.openRegion(chunkPosition.region).use { regionHandle ->
        regionHandle.readChunkInfo(chunkPosition)
    }
}
```

Each evaluation of `MinecraftWorldAccess.open(...).use {}` acquires and releases one world lease. Likewise,
`openRegion(...).use {}` retains one logical Region resource for that complete block and releases it at the end; its
physical storage is opened lazily by the first operation. Do not put either open/use chain inside a per-Chunk loop. Put
the complete batch inside one `use` block, or use the explicit open/close form above when the world or Region handle
must remain available across a wider application-managed lifetime.

Ordinary handle methods acquire and release internal Region admission per call. The handle pins Region state but does
not retain a read or write lock between calls. Same-Region reads may proceed together, writes serialize, and a waiting
writer blocks later readers. Different Regions progress independently. Operations that need a longer shared lifetime use
bounded callbacks:

- `withReadScope` holds one shared admission and one metadata snapshot for lazy multi-Chunk inspection;
- `replaceRegion { ... }` stages and commits one complete replacement.

The detached list remains the simplest way to inspect Region metadata. Use a read scope only when one snapshot or lazy
streaming matters:

```kotlin
suspend fun sumCompressedRegionBytes(regionHandle: RegionHandle): Long = regionHandle.withReadScope {
    chunkInfos.sumOf { regionChunkInfo -> regionChunkInfo.compressedByteCount }
}
```

The scope, its sequences, and its sources are invalid after the callback returns.

For complete replacement, the ordinary collection overload comes first:

```kotlin
suspend fun replaceRegion(
    regionHandle: RegionHandle,
    regionChunkInputs: Collection<RegionChunkInput>,
) {
    regionHandle.replaceRegion(regionChunkInputs)
}
```

Use the builder overload when inputs should be supplied one at a time under the same replacement operation. Omitted
positions are removed. The commit does not promise cross-file filesystem atomicity.

## Enumerate Regions

Both access modes can list map Regions and Entity Regions in one dimension:

```kotlin
suspend fun listMutableRegions(
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionDirectory: DimensionDirectory,
): List<RegionPosition> = minecraftWorldAccess.listRegionPositions(dimensionDirectory)

fun listLiveRegions(
    liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    dimensionDirectory: DimensionDirectory,
): List<RegionPosition> = liveMinecraftWorldAccess.listRegionPositions(dimensionDirectory)

suspend fun listMutableEntityRegions(
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionDirectory: DimensionDirectory,
): List<RegionPosition> = minecraftWorldAccess.listEntityRegionPositions(dimensionDirectory)

fun listLiveEntityRegions(
    liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    dimensionDirectory: DimensionDirectory,
): List<RegionPosition> = liveMinecraftWorldAccess.listEntityRegionPositions(dimensionDirectory)
```

This is intentionally a detached snapshot: the implementation performs one filesystem directory listing, accepts only
canonical Region names, sorts the positions, and then returns a materialized `List`. It is O (n), may be slow, and may
exhaust memory for an extremely large world. It is not transactionally consistent with concurrent file changes. Missing
Region directories return an empty list.

## Read a live world without locks

Use `LiveMinecraftWorldAccess` to observe a world that may be owned and modified by another process. It does not acquire
`session.lock`, does not create or repair files, and has no close lifecycle.

```kotlin
fun readLiveBlock(
    worldPath: Path,
    blockPosition: BlockPosition,
    chunkNbtCodec: ChunkNbtCodec<BlockStateDescriptor, String>,
): BlockStateDescriptor? {
    val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldPath)
    val chunkPosition = blockPosition.chunk
    val liveRegionHandle = liveMinecraftWorldAccess.openRegion(blockPosition.region)
    val regionChunkInfo = liveRegionHandle.readChunkInfo(chunkPosition) ?: return null
    check(regionChunkInfo.position == chunkPosition)

    val chunk = liveRegionHandle.readChunk(blockPosition, chunkNbtCodec) ?: return null
    val blockStateDescriptor = chunk.block(blockPosition)
    return blockStateDescriptor
}
```

The Entity path is symmetric and remains resource-free at the handle level:

```kotlin
fun readLiveEntityChunk(
    worldPath: Path,
    chunkPosition: ChunkPosition,
    expectedDataVersion: Int,
): EntityChunk<NbtCompound>? {
    val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldPath)
    val liveEntityRegionHandle = liveMinecraftWorldAccess.openEntityRegion(chunkPosition.region)
    val entityDataRegistry = NbtEntityDataRegistry()
    val entityChunkNbtCodec = EntityChunkNbtCodec(expectedDataVersion, entityDataRegistry)
    return liveEntityRegionHandle.readChunk(chunkPosition, entityChunkNbtCodec)
}
```

`LiveRegionHandle` is stateless: it retains only immutable filesystem/path/position/format context. `openRegion`
performs no I/O and always returns a handle, including for a missing Region. Every operation independently opens and
closes the resources it needs.

The mutable Region-level coordination described above does not apply to `LiveRegionHandle`. The live path intentionally
uses no lock, so a different process can change a Region while one live call reads it.

The read methods are the read-only subset of `RegionHandle`: local and absolute existence, header-level Chunk count and
local-position listing, stored metadata, compressed stream or value, raw NBT stream or document, caller-selected
serializer, and strong Chunk conversion. Because another process may write, delete, or replace the Region at any time,
two calls can observe different versions. Stale or torn input and the resulting I/O, Anvil, decompression, or NBT
failures are part of the live contract. Avoid a separate existence check when a following nullable read already answers
the question and the narrower observation window is preferable.

`openEntityRegion` returns the corresponding stateless `LiveEntityRegionHandle`; it offers the same read-only layers and
converts the semantic layer with `EntityChunkNbtCodec`.

## Other world files

The same mutable and live world access classes cover standalone files after the Region/Chunk APIs:

- `readLevelDataDocument`, typed `readLevelData`, and their mutable write counterparts;
- player data by UUID;
- dimension-scoped saved data by identifier;
- statistics and advancements as UTF-8 text, caller-selected JSON serializers, or streams.

Document and text methods return complete detached values. Stream callbacks are the bounded-lifetime path. Mutable
standalone files keep their own official replacement, backup, and durability policies; they do not share the Region
lock.

## Dimensions, failures, and platforms

Region operations default to `DimensionDirectory.Overworld`. Pass `DimensionDirectory.Nether`,
`DimensionDirectory.End`, or a validated `DimensionDirectory.Custom` when opening or listing another dimension. The map
methods target that dimension's `region` directory and the explicitly named Entity methods target its `entities`
directory. The physical storage selector remains internal, so callers cannot accidentally mix the two kinds of Region.

Structural Anvil failures use `AnvilFormatException`, strong Chunk projection failures use `ChunkNbtFormatException`,
strong Entity Chunk projection failures use `EntityChunkNbtFormatException`, and world/filesystem policy failures use
`WorldIOException` or `WorldLockException` as appropriate. Underlying NBT and registered custom-codec failures retain
the exception category of their owning layer.

System filesystem access is available on JVM, Android, configured Native targets, and Kotlin/JS Node. Browser and Wasm
applications use the filesystem-independent modules instead. Neither access mode chooses a dispatcher: blocking
filesystem I/O, compression, and NBT work run in the calling context, so applications move them off a main/UI thread
when needed.
