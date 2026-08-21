# world-format

`world-format` owns Minecraft world values and physical formats that do not require a filesystem. It works with ordinary
values and `kotlinx.io.Source`/`Sink`; it never opens a path, holds a file lock, or depends on a protocol or
vanilla-data module.

Its main capabilities are semantic Chunk conversion, coordinates and palettes, compressed unnamed-root NBT, raw
compression, and low-level Anvil containers. Filesystem-backed logical Region access belongs to
[`world-io`](../world-io/README.md).

## Follow a Chunk from stored bytes to blocks

The detached representations form a discoverable path from stored content to semantic values. Start with a
`CompressedChunk`, decode its complete generic NBT tree, and then project that tree with caller-supplied world data:

```kotlin
fun <B : Any, M : Any> decodeStoredChunk(
    compressedChunk: CompressedChunk,
    chunkPosition: ChunkPosition,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
): Chunk<B, M> {
    val nbtDocument = compressedChunk.toNbtDocument(compressedNbtFormat)
    val chunk = nbtDocument.toChunk(chunkPosition, chunkNbtCodec)
    return chunk
}
```

`CompressedChunk` owns the exact compressed payload and its compression algorithm. `NbtDocument` is the lossless generic
tag tree. `Chunk<B, M>` is the selected-release semantic projection whose Section containers have decoded the packed
palette IDs and resolved palette entries through the codec's registries. The semantic value is still palette-backed;
indexed block access resolves an entry without allocating a dense block list.

The content values are deliberately positionless, so the final conversion receives `chunkPosition` and validates it
against the NBT fields. The conversion functions are extensions in `world-format`: this keeps the lower-level `nbt`
module independent while still exposing `toNbtDocument()`, `toChunk()`, and the direct downward shortcuts through IDE
completion. Format- and codec-oriented methods remain available as the canonical streaming implementation.

## Decode a semantic Chunk

`ChunkNbtCodec` converts decompressed unnamed-root Chunk NBT into a positionless `Chunk<B, M>`. The caller supplies the
dimension layout, expected data version, and bidirectional block-state and biome registries.

`ChunkLayout` has no repository-version default because minimum Y and height belong to a dimension type, not to the
release as a whole. Obtain them from the world/server dimension metadata or provide the corresponding custom layout.

This complete example uses the open descriptor/name registries included by the module:

```kotlin
fun decodeBlockStateDescriptor(
    nbtSource: Source,
    chunkPosition: ChunkPosition,
    blockPosition: BlockPosition,
    chunkLayout: ChunkLayout,
    expectedDataVersion: Int,
): BlockStateDescriptor {
    require(blockPosition.chunk == chunkPosition)

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
    val chunk = chunkNbtCodec.decodeFromSource(nbtSource, chunkPosition)
    val blockStateDescriptor = chunk.block(chunkPosition, blockPosition)
    return blockStateDescriptor
}
```

`DescriptorBlockStateRegistry` accepts every persisted block-state descriptor. `NamedBiomeRegistry` represents biomes by
their persistent names. An application that needs richer runtime values implements `BlockStateRegistry<B>` and
`BiomeRegistry<M>` around vanilla data, mod data, or a combined catalogue. The application may depend on
`protocol-vanilla-data`, but `world-format` does not acquire a reverse dependency on it.

Encoding uses the same explicit position because the semantic Chunk does not store its parent coordinate:

```kotlin
fun <B : Any, M : Any> encodeChunk(
    chunk: Chunk<B, M>,
    chunkPosition: ChunkPosition,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
    nbtSink: Sink,
) {
    chunkNbtCodec.encodeToSink(chunk, chunkPosition, nbtSink)
}
```

`NbtDocument` is the universal NBT-tree path and can represent every legal tag. It has no concept of unknown fields. The
strong `ChunkNbtCodec` is a separate selected-release projection: it validates required fields, versions, layouts, and
registry values, but it does not retain tags outside the semantic `Chunk` model. Use a document when arbitrary modded or
future tags must survive a semantic round trip; use `Chunk` when block, biome, Section, and palette semantics are
required.

## Coordinates, Sections, and palettes

`MinecraftCoordinates` is the complete coordinate-calculation entry point. Continuous entity/player coordinates are
floored to the Block that contains them; every later boundary also uses floor semantics, so negative coordinates remain
correct:

```kotlin
fun locatePlayer(playerX: Double, playerY: Double, playerZ: Double): RegionPosition {
    val blockPosition = MinecraftCoordinates.block(playerX, playerY, playerZ)
    val chunkPosition = MinecraftCoordinates.chunk(blockPosition)
    val sectionPosition = MinecraftCoordinates.section(blockPosition)
    val regionPosition = MinecraftCoordinates.region(chunkPosition)

    check(blockPosition.chunk == chunkPosition)
    check(blockPosition.section == sectionPosition)
    check(blockPosition.region == regionPosition)
    check(sectionPosition.chunk == chunkPosition)
    check(sectionPosition.region == regionPosition)
    return regionPosition
}
```

The object also exposes scalar helpers such as `blockCoordinate`, `chunkCoordinate`, `sectionCoordinate`,
`regionCoordinate`, `blockCoordinateInChunk`, `blockCoordinateInSection`, and `chunkCoordinateInRegion`. Biome lookup
uses the same entry point through `quartCoordinate`, `quartCoordinateInSection`, `blockCoordinateInQuart`, and their
checked reverse conversions. This is useful when only one axis is available. The typed convenience properties delegate
to the same implementation; neither style maintains a second set of formulas. Checked scalar offsets are available as
`offsetBlockCoordinate`, `offsetSectionCoordinate`, `offsetChunkCoordinate`, and `offsetRegionCoordinate`.

Parent-relative coordinates round-trip through checked inverse conversions:

```kotlin
fun verifyCoordinateRoundTrip(blockPosition: BlockPosition) {
    val chunkPosition = blockPosition.chunk
    val sectionPosition = blockPosition.section
    val regionPosition = chunkPosition.region
    val chunkBlockPosition = chunkPosition.local(blockPosition)
    val localBlockPosition = sectionPosition.local(blockPosition)
    val localChunkPosition = regionPosition.local(chunkPosition)

    check(chunkPosition.block(chunkBlockPosition) == blockPosition)
    check(sectionPosition.block(localBlockPosition) == blockPosition)
    check(regionPosition.chunk(localChunkPosition) == chunkPosition)
}
```

The reverse coverage API exposes the exact absolute ranges owned by a Section, Chunk, or Region. A Region can enumerate
all 1024 absolute Chunk positions, or the corresponding relative positions, as lazy sequences. `ChunkLayout.blockYRange`
supplies the vertical Block range for a dimension-specific Chunk layout:

```kotlin
fun inspectRegionCoverage(regionPosition: RegionPosition): Sequence<ChunkPosition> {
    val chunkXRange = regionPosition.chunkXRange
    val chunkZRange = regionPosition.chunkZRange
    val blockXRange = regionPosition.blockXRange
    val blockZRange = regionPosition.blockZRange
    val localChunkPositions = regionPosition.localChunkPositions()
    val chunkPositions = regionPosition.chunkPositions()

    check(chunkXRange.count() == REGION_SIDE)
    check(chunkZRange.count() == REGION_SIDE)
    check(blockXRange.count() == REGION_SIDE * CHUNK_SIDE)
    check(blockZRange.count() == REGION_SIDE * CHUNK_SIDE)
    check(localChunkPositions.count() == REGION_CHUNK_COUNT)
    return chunkPositions
}
```

`SectionPosition.blockPositions()` similarly yields all 4096 absolute Blocks in palette-index order. These sequences
describe coordinate coverage only; which Chunk records actually exist is filesystem metadata exposed by `world-io`.
Reverse conversions reject an owner mismatch and coordinates whose multiplication would overflow `Int`.

Offsets are checked rather than silently wrapping `Int`. `ChunkPosition.positionsAround(radius)` lazily enumerates a
square in Z-then-X order and is the canonical way to form a view around a player:

```kotlin
fun chunksAroundPlayer(playerX: Double, playerY: Double, playerZ: Double): Sequence<ChunkPosition> {
    val playerBlockPosition = MinecraftCoordinates.block(playerX, playerY, playerZ)
    val playerChunkPosition = playerBlockPosition.chunk
    return playerChunkPosition.positionsAround(horizontalRadius = 10)
}
```

`Chunk` provides local and absolute block/biome overloads. `ChunkSection` does the same with an explicit
`SectionPosition` for absolute access:

```kotlin
fun inspectPalette(
    chunk: Chunk<BlockStateDescriptor, String>,
    chunkPosition: ChunkPosition,
    blockPosition: BlockPosition,
): BlockStateDescriptor {
    val blockStateDescriptor = chunk.block(chunkPosition, blockPosition)
    val sectionPosition = blockPosition.section
    val chunkSection = chunk.section(chunkPosition, sectionPosition)
    if (chunkSection != null) {
        val paletteSnapshot = chunkSection.blockStates.paletteSnapshot()
        val denseBlockStates = chunkSection.toDenseBlockStates()
        check(paletteSnapshot.entryCount == denseBlockStates.size)
    }
    return blockStateDescriptor
}
```

Decoding preserves persisted palette order and unused entries. Indexed lookup resolves palette IDs to logical values.
Ordinary mutation reuses or appends stable IDs. Encoding compacts a snapshot without mutating the runtime container.
Call `compactSnapshot()` to inspect the compact palette and remapped IDs without mutation, or `compact()` to apply that
compaction to the in-memory container. `paletteSnapshot()` is a read-only diagnostic; `toDenseBlockStates()` and
`toDenseBiomes()` are explicit allocating adapters.

## Read and write compressed NBT

`CompressedNbtFormat` composes a `CompressionRegistry` with unnamed-root binary NBT. Stream methods are canonical and do
not close caller-owned endpoints.

```kotlin
fun transcodeNbtDocument(
    compressedSource: Source,
    sourceCompression: Compression,
    targetCompression: Compression,
    compressedSink: Sink,
): NbtDocument {
    val compressedNbtFormat = CompressedNbtFormat()
    val nbtDocument = compressedNbtFormat.decodeDocumentFromSource(compressedSource, sourceCompression)
    compressedNbtFormat.encodeDocumentToSink(nbtDocument, targetCompression, compressedSink)
    return nbtDocument
}
```

When a detached compressed value is useful, `toCompressedChunk` returns `CompressedChunk`. Its compression and exact
length travel with the bytes, and `writeTo` avoids making another complete array:

```kotlin
fun encodeCompressedChunk(
    nbtDocument: NbtDocument,
    compression: Compression,
    compressedSink: Sink,
): CompressedChunk {
    val compressedChunk = nbtDocument.toCompressedChunk(compression)
    compressedChunk.writeTo(compressedSink)
    return compressedChunk
}
```

`CompressedChunk` defensively copies constructor bytes. `readFromSource` adopts the bytes it reads, `writeTo(Sink)`
writes the owned payload directly, and `toByteArray()` is the explicit copying adapter.

## Use raw compression

`CompressionRegistry` dispatches GZIP, ZLIB, uncompressed, LZ4Block, and caller-registered custom codecs. Its direct
stream operations are useful when no NBT interpretation is needed:

```kotlin
fun decompressPayload(
    compression: Compression,
    compressedSource: Source,
    plainSink: Sink,
) {
    val compressionRegistry = CompressionRegistry()
    compressionRegistry.decompressToSink(compression, compressedSource, plainSink)
}
```

The registry also exposes the inverse `compressToSink` operation and source/sink decorators for pipeline composition.

## Inspect an Anvil container

`AnvilRegionFormat` is the lower-level physical container API. It consumes a complete `.mca` stream but cannot resolve
filesystem sidecars because paths do not exist in this module. `decodeFromSource` therefore leaves external record
content unresolved:

```kotlin
fun decodeAnvilRegion(regionSource: Source): AnvilRegion {
    val anvilRegion = AnvilRegionFormat.decodeFromSource(regionSource)
    return anvilRegion
}
```

For bounded record-at-a-time inspection, `decodeRecordsFromSource` lends each inline compressed payload in physical
location order:

```kotlin
fun decodeAnvilRecords(
    regionSource: Source,
    consumeRecord: (AnvilChunkRecordInfo, Source) -> Unit,
) {
    AnvilRegionFormat.decodeRecordsFromSource(regionSource) { anvilChunkRecordInfo, compressedSource ->
        consumeRecord(anvilChunkRecordInfo, compressedSource)
    }
}
```

The callback must consume every inline payload completely. An external record supplies an empty inline source; only a
filesystem owner such as `world-io` can locate and read its actual compressed content.

Encoding streams the main container and returns the detached external payloads that the caller must place:

```kotlin
fun encodeAnvilRegion(
    anvilRegion: AnvilRegion,
    regionSink: Sink,
): Map<LocalChunkPosition, CompressedChunk> {
    val externalChunks = AnvilRegionFormat.encodeRecordsToSink(anvilRegion, regionSink)
    return externalChunks
}
```

`encodeToByteArray` is the complete in-memory adapter and returns `EncodedAnvilRegion`. Its array properties are
defensive copies; `writeTo`, `externalChunkPositions`, and `writeExternalChunkTo` operate on its owned data without
first creating another complete copy.

## Selected-release structured files

The module also owns the repository-selected `LevelDat`, `PlayerAdvancements`, and `PlayerStatistics` schemas and
serializers. They do not migrate historical data. Typed decoding is strict; use `NbtDocument`, `NbtTag`, or
`JsonElement` when arbitrary fields must survive.

## Failures

- `AnvilFormatException` reports structural container and record errors.
- `CompressionFormatException` reports invalid compression framing or missing custom compression support.
- `ChunkNbtFormatException` reports strong Chunk schema, coordinate, layout, version, or registry projection errors.
- NBT and stream/backend failures retain the exception type of their owning module.
