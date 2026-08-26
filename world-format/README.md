# world-format

`world-format` provides Minecraft world values and physical formats that do not require a filesystem. It works with
Kotlin values and caller-owned `kotlinx.io.Source`/`Sink` streams.

Use it for:

- semantic Chunk, Section, palette, Block Entity, Entity, and Entity Chunk values;
- safe conversion between Block, Section, Chunk, and Region coordinates;
- selected-release `level.dat`, advancement, and statistics models;
- GZIP, ZLIB, uncompressed, Minecraft's legacy LZ4 block stream, and custom compression;
- compressed unnamed-root Chunk NBT;
- low-level `.mca` Anvil containers and external Chunk records;
- filesystem-independent data-pack archives, parsing, overlays, filters, and stack resolution.

The module never opens a path or acquires a world lock. Use [`world-io`](../world-io/README.md) for actual world
directories.

## Decode a semantic Chunk

A stored Chunk can be followed through three useful representations:

```text
CompressedChunk -> NbtDocument -> Chunk<B, M>
```

- `CompressedChunk` owns the exact compressed payload and its compression identifier.
- `NbtDocument` is a lossless generic NBT tree.
- `Chunk<B, M>` exposes selected-release Chunk semantics with resolved block-state and biome values.

If the application already has a codec, conversion is direct:

```kotlin
fun <B : Any, M : Any> decodeStoredChunk(
    compressedChunk: CompressedChunk,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): Chunk<B, M> {
    val nbtDocument = compressedChunk.toNbtDocument()
    return nbtDocument.toChunk(chunkNbtCodec)
}
```

Construct a codec from a dimension-specific layout, the expected data version, and registries for the logical values the
application wants:

```kotlin
fun createDescriptorChunkCodec(
    chunkLayout: ChunkLayout,
    expectedDataVersion: Int,
): ChunkNbtCodec<BlockStateDescriptor, String> {
    val chunkDataRegistries = ChunkDataRegistries(
        blockStates = DescriptorBlockStateRegistry(),
        biomes = NamedBiomeRegistry(),
    )
    return ChunkNbtCodec(
        ChunkNbtContext(
            chunkLayout = chunkLayout,
            chunkDataRegistries = chunkDataRegistries,
            expectedDataVersion = expectedDataVersion,
        ),
    )
}
```

`DescriptorBlockStateRegistry` preserves block names and properties; `NamedBiomeRegistry` uses persistent biome names.
Applications may implement `BlockStateRegistry<B>` and `BiomeRegistry<M>` to resolve directly into their own runtime
objects.

`ChunkLayout` has no release-wide default because height and minimum Y belong to a dimension. Supply it from the world's
dimension metadata or the negotiated protocol data.

When a Region slot is known, use the decode overload that accepts the expected `ChunkPosition`; it verifies that stored
`xPos`/`zPos` matches the slot.

## Read and modify Chunk contents

Semantic Chunks retain their absolute position and provide both local and common absolute-coordinate operations:

```kotlin
fun replaceBlock(
    chunk: Chunk<BlockStateDescriptor, String>,
    blockPosition: BlockPosition,
    replacement: BlockStateDescriptor,
): BlockStateDescriptor {
    require(blockPosition.chunkPosition == chunk.chunkPosition)
    val previous = chunk.block(blockPosition)
    chunk.setBlock(blockPosition, replacement)
    return previous
}
```

`section`, `block`, `biome`, Block Entity, heightmap, tick, and structure operations work on the same positioned value.
Palette-backed indexed reads do not allocate a dense list. Use `toDenseBlockStates()` or `toDenseBiomes()` only when a
complete dense snapshot is actually needed.

Palette mutation preserves stable palette IDs. `compactSnapshot()` returns a non-mutating compact view, while
`compact()` explicitly rewrites the container. Encoding uses the snapshot path and does not mutate the Chunk merely to
serialize it.

Strong Chunk decoding validates required fields, data version, layout, coordinates, and registry resolution, but it does
not retain unknown tags outside the semantic model. Use `NbtDocument` when arbitrary modded or future fields must
survive a round trip.

## Work with stored Entities

Entity Regions use the parallel `EntityChunk` model. `NbtEntityDataRegistry` preserves subtype-specific fields as
`NbtCompound`:

```kotlin
fun decodeEntityChunk(
    compressedChunk: CompressedChunk,
    expectedDataVersion: Int,
): EntityChunk<NbtCompound> {
    val entityChunkNbtCodec = EntityChunkNbtCodec(
        expectedDataVersion = expectedDataVersion,
        entityDataRegistry = NbtEntityDataRegistry(),
    )
    return compressedChunk.toEntityChunk(entityChunkNbtCodec)
}
```

Each `Entity<E>` exposes its type, UUID, position, velocity, `entityRotation`, subtype data, recursive passengers, and
derived Block/Section/Chunk/Region positions. `EntityChunk.rootEntities` preserves the stored passenger tree, while
`allEntities()` traverses roots and passengers in load order.

Applications that want a strong subtype value implement `EntityDataRegistry<E>` to map between persistent NBT and their
runtime type. Moving an Entity updates its value; transferring it between loaded Entity Chunks remains an application
operation.

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

## Read and write compressed NBT

`CompressedNbtFormat` combines a `CompressionRegistry` with the unnamed compound-root NBT used by Chunk records:

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

The callback consumes each inline payload completely. An external record supplies an empty inline stream because only a
filesystem layer can locate its sidecar.

Encoding writes the main Region and returns the external payloads that the caller must place:

```kotlin
fun writeRegion(
    anvilRegion: AnvilRegion,
    sink: Sink,
): Map<LocalChunkPosition, CompressedChunk> =
    AnvilRegionFormat.encodeRecordsToSink(anvilRegion, sink)
```

Unchanged compressed records can be inspected or repacked without decompression.

## Structured files and data packs

The module includes serializers for the repository-selected `LevelDat`, `PlayerAdvancements`, and `PlayerStatistics`
schemas. These models do not migrate historical files. Typed decoding is strict; use `NbtDocument`, `NbtTag`, or
`JsonElement` for open-ended data.

The data-pack API is also filesystem-independent:

```kotlin
fun resolveDataPack(
    dataPackArchive: DataPackArchive,
    dataPackFormatVersion: DataPackFormatVersion?,
    dataPackFileDecoders: List<DataPackFileDecoder>,
): ResolvedDataPackStack {
    val dataPack = DataPackFormat(dataPackFileDecoders = dataPackFileDecoders).decode(dataPackArchive)
    return DataPackStack(dataPack).resolve(dataPackFormatVersion)
}
```

Archives, typed files, overlays, filters, enabled feature flags, tags, and merged resources are ordinary values.
Compressed NBT files are decoded from retained in-memory bytes only when `NbtFile.nbtDocument` is requested; this does
not reopen the data-pack container or require a data-pack read lock.
`ResolvedDataPackResource.decodeDataPackTagFile()` exposes string and object tag entries as `DataPackTagFile` values.
Directory and ZIP access is provided by [`world-io`](../world-io/README.md#read-world-data-packs); Configuration
projection is provided by [`protocol-datapack`](../protocol-datapack/README.md).

## Failures

- `AnvilFormatException` reports invalid Region/container structure.
- `CompressionFormatException` reports invalid compression framing or unavailable CUSTOM codecs.
- `DataPackFormatException` reports data-pack file and stack-resolution failures.
- `ChunkNbtFormatException` reports strong Chunk schema, coordinate, layout, version, or registry failures.
- `EntityChunkNbtFormatException` reports strong Entity Chunk schema, position, identity, or vector failures.
- NBT and stream/backend errors retain the exception type from their owning module.
