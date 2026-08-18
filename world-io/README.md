# world-io

Okio-based access to Minecraft world directories. The module owns paths, standalone NBT/JSON files, Anvil filesystem
policy, live reads, replacement policy, and `session.lock`; binary formats remain in `nbt-serialization` and
[`world-format`](../world-format/README.md).

Choose the narrowest entry point that owns the behavior you need:

| Entry point                 | Use                                                            |
|-----------------------------|----------------------------------------------------------------|
| `MinecraftWorldAccess`      | Mutate a complete world while holding its `session.lock`       |
| `LiveMinecraftWorldReader`  | Read a world currently owned by another process                |
| `WorldRegionStore`          | Coordinate all `.mca` files in one Region directory            |
| `RegionFileStore`           | Mutate one exact `.mca` file without higher-level coordination |
| `LiveRegionFileReader`      | Read one exact `.mca` file without coordination                |
| Standalone stores and paths | Compose individual world files without a whole-world owner     |

## One API shape

Each data layer offers the same progression from convenient values to physical streams:

| Layer                       | Complete value | Typed value                                   | Streaming callback                     |
|-----------------------------|----------------|-----------------------------------------------|----------------------------------------|
| Region                      | `RegionFile`   | Chunk-by-Chunk through `RegionChunkNbtFormat` | `RegionReadScope` / `RegionWriteScope` |
| Chunk                       | `RegionChunk`  | `readChunkNbt` / `writeChunkNbt`              | compressed `Source` / `Sink`           |
| Standalone NBT              | `NbtDocument`  | serializer or reified overload                | decompressed `Source` / `Sink`         |
| Statistics and advancements | UTF-8 `String` | serializer or reified overload                | UTF-8 `Source` / `Sink`                |

The examples below distinguish three useful modes:

- **Complete I/O** materializes the complete natural value, such as a `RegionFile`, `RegionChunk`, `NbtDocument`, or
  `String`.
- **Partially streaming I/O** processes a Region one Chunk at a time. Typed Anvil writes retain only the compressed
  Chunk currently being encoded because its compressed length must be known before sector allocation.
- **Fully streaming I/O** does not retain a complete payload in memory. Raw Anvil writes therefore accept an
  already-compressed Chunk stream and its exact length; standalone NBT and JSON can stream directly without that length.

All stream callbacks use `kotlinx.io.Source` and `Sink`. The library owns each stream; it is valid only during its
callback and must not escape it. Complete and typed writes are adapters over the corresponding streaming path.

## Whole-world entry point

`MinecraftWorldAccess` covers level data, player data, saved data, statistics, advancements, and terrain/entity/POI
Regions:

```kotlin
val worldRoot = "/srv/minecraft/world".toPath()
val playerUuid = "01234567-89ab-cdef-0123-456789abcdef"
val world = MinecraftWorldAccess.open(worldRoot)
try {
    val level = world.readLevelData<LevelDat>()
    world.writeLevelData(level.copy(data = level.data.copy(levelName = "Edited world")))

    val statistics = world.readStatistics<PlayerStatistics>(playerUuid)
    world.writeStatistics(playerUuid, statistics)

  world.readLevelData { source ->
    consumeDecompressedNbt(source)
  }
  world.readStatistics(playerUuid) { source ->
    consumeUtf8Json(source)
  }
} finally {
    world.close()
}
```

`MinecraftWorldAccess` holds `session.lock` until `close()`. Reads of one logical file may run together; writes are
exclusive, while independent files and Regions can progress concurrently. The library creates no thread pool and does
not choose a dispatcher. Filesystem and codec work runs on the caller's thread.

The remaining examples that call `world` reuse this instance and are intended to run inside the `try` block, before
`world.close()`. Examples that construct another owner manage its lifetime explicitly. Variables are constructed at
their first appearance. Functions named `load…`, `consume…`, or `write…` represent application-provided data producers
or consumers.

## Region layer

A Region is an `.mca` Header plus up to 1024 independent Chunk records. It is deliberately exposed as a structure of
Chunk streams, not as one artificial continuous byte stream.

### Region and Chunk coordinates

`RegionPosition` identifies an `.mca` file by the coordinates in its filename. Each Region covers 32 by 32 absolute
Chunk coordinates; these are Chunk coordinates, not block coordinates:

```kotlin
val region = RegionPosition(x = 2, z = -1) // region/r.2.-1.mca
val localChunk = LocalChunkPosition(x = 6, z = 7)
val absoluteChunk = ChunkPosition(x = 70, z = -25)

check(absoluteChunk.region == region)
check(absoluteChunk.local == localChunk)
check(absoluteChunk in region)
check(region.local(absoluteChunk) == localChunk)
check(region.chunk(localChunk) == absoluteChunk)
```

`ChunkPosition` is absolute within one dimension. `LocalChunkPosition` is always in `0..31` on each axis and is used
only together with an already-selected Region. `RegionPosition.local(absoluteChunk)` verifies that the absolute Chunk
belongs to that Region before converting it; a mismatch throws `IllegalArgumentException`. In the example,
`r.2.-1.mca` covers absolute Chunk X `64..95` and Z `-32..-1`.

Every Chunk operation has both coordinate shapes. At a world or Region-directory layer, the local form also takes the
Region that selects the `.mca` file:

```kotlin
val byAbsolute: RegionChunk? = world.readChunk(absoluteChunk)
val byLocal: RegionChunk? = world.readChunk(region, localChunk)
```

Once a Region is explicitly open, either coordinate is enough. The absolute overload verifies membership:

```kotlin
world.withRegion(region) {
  val byAbsolute = readChunk(absoluteChunk)
  val byLocal = readChunk(localChunk)
}
```

The same pair of overloads is available for complete and streaming `readChunk`/`writeChunk`, existence and clearing,
typed Chunk NBT, document Chunk NBT, `WorldRegionStore`, `RegionFileStore`, and the corresponding live read-only APIs.

### MCA and MCC are one logical Region

Every Region and Chunk API automatically handles external Chunk sidecars associated with the selected `.mca` file.
Callers do not open, copy, replace, or delete `.mcc` files separately:

- reading an external Chunk follows its marker in the `.mca` record and reads `c.<chunkX>.<chunkZ>.mcc` from the same
  Region directory;
- writing selects inline `.mca` storage or external `.mcc` storage from the compressed payload length, creates or
  replaces the sidecar when external storage is required, and removes an obsolete sidecar when the Chunk becomes inline
  or is cleared;
- complete, typed, and streaming Region/Chunk operations all use this same policy, including direct
  `RegionFileStore` and `LiveRegionFileReader` access.

The `.mca` Header and all sidecars it addresses form one logical coordination group in `MinecraftWorldAccess` and
`WorldRegionStore`. Cross-file filesystem atomicity is not promised, but operations through the same coordinated owner
cannot overlap a Region write with reads or other writes to its `.mca` or `.mcc` files.

### Complete Region read and write

`RegionFile` is a detached complete snapshot. Its `chunks` map uses `LocalChunkPosition` because the value deliberately
does not retain the source filename and can be written to another Region:

```kotlin
val targetRegion = RegionPosition(x = 3, z = -1) // region/r.3.-1.mca

val snapshot: RegionFile? = world.readRegion(region)
if (snapshot != null) {
  world.writeRegion(targetRegion, snapshot)
}
```

`writeRegion` is a complete replacement. Positions absent from the supplied `RegionFile` are cleared.

### Fully streaming Region read and write

`RegionReadScope` lends one Header snapshot and opens each compressed Chunk stream only while it is consumed:

```kotlin
world.readRegion(region) {
  for (localPosition in chunkPositions) {
    readChunk(localPosition) { info, source ->
      consumeCompressedChunk(localPosition, info, source)
    }
  }
}
```

`RegionWriteScope` accepts already-compressed streams with known lengths. `PreparedCompressedChunk` below is an example
application type: its callback writes bytes from whatever file, network stream, or generated source the application
owns.

```kotlin
data class PreparedCompressedChunk(
  val position: LocalChunkPosition,
  val compression: Compression,
  val compressedLength: Long,
  val writePayload: (Sink) -> Unit,
)

val preparedCompressedChunks = listOf(
  PreparedCompressedChunk(
    position = LocalChunkPosition(x = 6, z = 7),
    compression = Compression.ZLIB,
    compressedLength = 12_345L,
    writePayload = ::writePreparedChunkPayload,
  ),
)

world.writeRegion(region) {
  for (chunk in preparedCompressedChunks) {
    writeChunk(
      position = chunk.position,
      compression = chunk.compression,
      compressedLength = chunk.compressedLength,
    ) { sink ->
      chunk.writePayload(sink)
    }
  }
}
```

The callback must write exactly `compressedLength` bytes. The operation stages the supplied Chunk records and commits
the `.mca` Header once; omitted positions are cleared. Inline versus `.mcc` storage and timestamps remain automatic.

### Streaming through a Region-directory owner

`WorldRegionStore` exposes the same Region and Chunk streams when the application wants to operate below the whole-world
entry point. It coordinates handles and concurrent operations within one selected Region directory, but does not take
the world's `session.lock`:

```kotlin
val worldPaths = MinecraftWorldPaths(worldRoot)
val regionStore = WorldRegionStore(
  paths = worldPaths,
  storage = RegionStorageDirectory.CHUNKS,
  dimension = DimensionDirectory.Overworld,
)
try {
  regionStore.readRegion(region) {
    for (localPosition in chunkPositions) {
      readChunk(localPosition) { info, source ->
        consumeCompressedChunk(localPosition, info, source)
      }
    }
  }
  regionStore.readChunk(absoluteChunk) { info, source ->
    consumeCompressedChunk(absoluteChunk.local, info, source)
  }
} finally {
  regionStore.close()
}
```

### Partially streaming typed Region I/O

Typed reads decode directly from each compressed stream. A typed batch write compresses one Chunk at a time, stages it,
then releases that payload before moving to the next Chunk; it never retains the complete Region payload:

```kotlin
val chunkFormat = world.configuration.regionChunkNbtFormat
val chunkSerializer = MyChunkData.serializer()
val replacementChunks = mapOf(
  localChunk to loadReplacementChunkData(absoluteChunk),
)

world.readRegion(region) {
  for (localPosition in chunkPositions) {
    readChunk(localPosition) { info, source ->
      val value = chunkFormat.decodeFromSource(chunkSerializer, source, info.compression)
      consumeChunk(value)
    }
  }
}

world.writeRegion(region) {
  for ((localPosition, value) in replacementChunks) {
    val chunk = chunkFormat.encode(chunkSerializer, value, Compression.ZLIB)
    writeChunk(localPosition, chunk)
  }
}
```

This typed `writeRegion` still performs one Header commit and remains a complete replacement. The one-Chunk buffer is
the direct consequence of Anvil placing the compressed length before the payload; the library does not hide that fact
behind a temporary-file pass or by encoding every Chunk twice.

### Retaining one Region handle for incremental work

One-shot calls intentionally release their Region entry after each operation. For a loop that updates or reads selected
Chunks without replacing the Region, make the Region explicit:

```kotlin
world.withRegion(region) {
  readChunk(absoluteChunk) { info, source ->
    consumeCompressedChunk(absoluteChunk.local, info, source)
  }

  for ((localPosition, value) in replacementChunks) {
    writeChunkNbt(localPosition, value, Compression.ZLIB)
  }

  val decoded = readChunkNbt<MyChunkData>(absoluteChunk)
}

val openedRegion: WorldRegion = world.openRegion(region)
try {
  openedRegion.readRegion {
    for (localPosition in chunkPositions) {
      readChunk(localPosition) { info, source ->
        consumeCompressedChunk(localPosition, info, source)
      }
    }
  }
} finally {
  openedRegion.close()
}
```

`WorldRegion` keeps one Region entry and, after first use, one `.mca` handle, Header, and sector allocator alive between
calls. `openRegion` returns the same caller-owned resource when it must cross function boundaries; its suspending
`close()` waits for admitted calls. `withRegion` is the structured default.

Methods on one `WorldRegion` may be called concurrently. Reads share access; writes to different Chunks in the same
Region are legal and serialize at the Region file. Different Regions can run in parallel on a caller-provided
dispatcher. Each incremental `writeChunk` commits that Chunk while preserving all other positions.

Reading, existence checks, and clearing a missing Region do not create a directory or `.mca`; the first write does.
Clearing an existing Region preserves a valid empty `.mca`.

## Chunk layer

Chunk APIs accept both coordinate forms at every layer. World and Region-directory owners use either an absolute
`ChunkPosition` or a `(RegionPosition, LocalChunkPosition)` pair. An explicit Region accepts either its
`LocalChunkPosition` or a validated absolute `ChunkPosition`.

### Complete Chunk read and write

`RegionChunk` contains the compressed payload as a value:

```kotlin
val targetChunk = ChunkPosition(x = 71, z = -25) // local (7, 7) in the same Region

val storedChunk: RegionChunk? = world.readChunk(absoluteChunk)
if (storedChunk != null) {
  world.writeChunk(targetChunk.region, targetChunk.local, storedChunk)
}
```

### Fully streaming Chunk read and write

Raw callbacks expose compressed payload bytes. Reads report their compression, exact length, storage form, and
timestamp; writes select storage form and timestamp automatically:

```kotlin
world.readChunk(region, localChunk) { info, source ->
  consumeCompressedChunk(localChunk, info, source)
}

val preparedChunk = preparedCompressedChunks.single()
world.writeChunk(
  position = absoluteChunk,
  compression = preparedChunk.compression,
  compressedLength = preparedChunk.compressedLength,
) { sink ->
  preparedChunk.writePayload(sink)
}
```

The read callback must consume the complete payload, and the write callback must produce exactly the declared length.

### Typed and document Chunk I/O

Typed reads decode from the compressed stream. Typed and `NbtDocument` writes encode and retain one compressed Chunk to
discover its record length, then delegate to the raw stream writer:

```kotlin
val value: MyChunkData? = world.readChunkNbt(absoluteChunk)
if (value != null) {
  world.writeChunkNbt(absoluteChunk, value, Compression.ZLIB)
}

val document: NbtDocument? = world.readChunkNbtDocument(absoluteChunk)
if (document != null) {
  world.writeChunkNbtDocument(absoluteChunk, document, Compression.LZ4)
}
```

For multiple Chunks in one `.mca`, place these calls inside `withRegion` so the file handle is retained.

## Standalone NBT layer

Level data, player data, and saved data expose complete documents, typed values, and decompressed NBT streams with the
same naming shape.

### Complete and typed NBT

```kotlin
val levelDocument: NbtDocument = world.readLevelDataDocument()
world.writeLevelDataDocument(levelDocument)

val player: PlayerData? = world.readPlayerData(playerUuid)
if (player != null) {
  world.writePlayerData(playerUuid, player)
}

val raids: Raids? = world.readSavedData("raids")
world.writeSavedData("raids", requireNotNull(raids))
```

Typed operations serialize directly against the physical stream; they do not first build an `NbtDocument`.

### Fully streaming NBT

Streaming callbacks see decompressed unnamed-root NBT bytes. The store owns compression and each file family's
replacement or backup policy:

```kotlin
world.readLevelData { source ->
  consumeDecompressedNbt(source)
}

world.writePlayerData(playerUuid) { sink ->
  writeDecompressedPlayerNbt(sink)
}
```

The lower-level `NbtFileStore`, `LevelDataStore`, `PlayerDataStore`, and `SavedDataFileStore` expose the same operations
for caller-selected paths or independently composed world-file policies.

```kotlin
val nbtFiles = NbtFileStore()
val levelDataStore = LevelDataStore(worldPaths, nbtFiles)
val playerDataStore = PlayerDataStore(worldPaths, nbtFiles)
val savedDataStore = SavedDataFileStore(worldPaths, nbtFiles = nbtFiles)

levelDataStore.read { source ->
  consumeDecompressedNbt(source)
}
playerDataStore.read(playerUuid) { source ->
  consumeDecompressedNbt(source)
}
savedDataStore.read("example:renderer/state") { source ->
  consumeDecompressedNbt(source)
}
nbtFiles.read(worldPaths.levelData) { source ->
  consumeDecompressedNbt(source)
}
```

## Statistics and advancements JSON layer

Complete text, typed JSON, and raw UTF-8 streams share the same progression:

```kotlin
val text: String = world.readStatisticsText(playerUuid)
world.writeStatisticsText(playerUuid, text)

val advancements: PlayerAdvancements = world.readAdvancements(playerUuid)
world.writeAdvancements(playerUuid, advancements)

world.readAdvancements(playerUuid) { source ->
  consumeUtf8Json(source)
}

world.writeStatistics(playerUuid) { sink ->
  writeUtf8Statistics(sink)
}
```

Typed JSON operations decode and encode directly on the UTF-8 stream rather than materializing a complete `String`.
`Utf8JsonFileStore` exposes the same text, `JsonElement`, typed, and streaming operations for arbitrary paths.

```kotlin
val jsonFiles = Utf8JsonFileStore()
jsonFiles.read(worldPaths.statistics(playerUuid)) { source ->
  consumeUtf8Json(source)
}
jsonFiles.read(worldPaths.advancement(playerUuid)) { source ->
  consumeUtf8Json(source)
}
```

## Direct exact-Region primitives

Construct the exact overworld terrain Region path from the same coordinates used above:

```kotlin
val exactRegionPath = worldRoot / "region" / "r.${region.x}.${region.z}.mca"
```

`RegionFileStore.open(exactRegionPath)` opens or creates that writable Region and exposes the same complete/streaming
Region-and-Chunk operations. Because the filename identifies `region`, Chunk methods accept either
`localChunk` or the validated `absoluteChunk`; no caller conversion is required. It takes no `session.lock` and has no
shared-read/exclusive-write coordinator. The caller must exclude conflicting reads, writes, and close calls, including
calls through another store covering the same file.

```kotlin
val exactStore = RegionFileStore.open(exactRegionPath)
try {
  val byAbsolute = exactStore.readChunk(absoluteChunk)
  val byLocal = exactStore.readChunk(localChunk)
  exactStore.readChunk(absoluteChunk) { info, source ->
    consumeCompressedChunk(absoluteChunk.local, info, source)
  }
  exactStore.readRegion {
    for (localPosition in chunkPositions) {
      readChunk(localPosition) { info, source ->
        consumeCompressedChunk(localPosition, info, source)
      }
    }
  }
} finally {
  exactStore.close()
}
```

`LiveRegionFileReader.open(exactRegionPath)` is its exact-file read-only counterpart. It uses live file sharing,
performs no repair, and likewise leaves read/close coordination to the caller. Direct primitives must be closed by the
caller and should be used instead of, rather than concurrently with, another mutable owner covering the same file.

```kotlin
val exactReader = LiveRegionFileReader.open(exactRegionPath)
try {
  val byAbsolute = exactReader.readChunk(absoluteChunk)
  val byLocal = exactReader.readChunk(localChunk)
  exactReader.readChunk(absoluteChunk) { info, source ->
    consumeCompressedChunk(absoluteChunk.local, info, source)
  }
  exactReader.readRegion {
    for (localPosition in chunkPositions) {
      readChunk(localPosition) { info, source ->
        consumeCompressedChunk(localPosition, info, source)
      }
    }
  }
} finally {
  exactReader.close()
}
```

## Reading a live world

`LiveMinecraftWorldReader` takes no lock, never repairs or writes files, and needs no `close()` itself. One-shot calls
open and close their files independently:

```kotlin
val reader = LiveMinecraftWorldReader.open(worldRoot)
val liveLevel = reader.readLevelData<LevelDat>()
val liveChunk = reader.readChunkNbt<MyChunkData>(absoluteChunk)
val sameLiveChunk = reader.readChunkNbt<MyChunkData>(region, localChunk)
```

The default formats can be replaced as one immutable reader configuration, matching the mutable world entry point:

```kotlin
val configuredReader = LiveMinecraftWorldReader.open(
  root = worldRoot,
  configuration = LiveMinecraftWorldReaderConfiguration(
    regionChunkNbtFormat = RegionChunkNbtFormat(),
    standaloneNbtFormat = minecraftWorldNbtFormat(),
  ),
)
```

The supplied Region format is also inherited by every `LiveWorldRegion` opened through that reader. Its NBT serializers
module and compression registry therefore apply consistently to one-shot, retained-Region, document, and typed Chunk
reads, including caller-registered CUSTOM compression.

### Streaming live reads

`LiveMinecraftWorldReader` is the World-level selector: a world is a directory of independent logical files, so there is
no single World byte stream. Its streaming methods open the selected standalone file or Region for one callback and
close it afterward. Use the narrower layers below according to the value being consumed.

#### Chunk stream

`readChunk` lends one compressed Chunk payload together with its record metadata. An external Chunk is read
transparently from its associated `.mcc` file. The `Source` contains compressed bytes; use `info.compression` to
interpret them:

```kotlin
reader.readChunk(absoluteChunk) { info, source ->
  consumeCompressedChunk(absoluteChunk.local, info, source)
}
```

#### Region stream

A Region is streamed as its Header snapshot and independent Chunk streams, not as one artificial continuous `.mca`
stream. `chunkPositions` comes from that Header snapshot, and every nested `Source` contains one compressed Chunk
payload:

```kotlin
reader.readRegion(region) {
  for (localPosition in chunkPositions) {
    readChunk(localPosition) { info, source ->
      consumeCompressedChunk(localPosition, info, source)
    }
  }
}
```

#### Standalone NBT stream

Level data, player data, and dimension-scoped saved data each lend their decompressed binary NBT stream:

```kotlin
reader.readLevelData { source ->
  consumeDecompressedNbt(source)
}
reader.readPlayerData(playerUuid) { source ->
  consumeDecompressedNbt(source)
}
reader.readSavedData("example:renderer/state") { source ->
  consumeDecompressedNbt(source)
}
```

#### Statistics and advancement streams

Statistics and advancement callbacks lend the selected file's UTF-8 JSON stream without first retaining a complete
`String` or JSON tree:

```kotlin
reader.readStatistics(playerUuid) { source ->
  consumeUtf8Json(source)
}
reader.readAdvancements(playerUuid) { source ->
  consumeUtf8Json(source)
}
```

Every `Source` above is borrowed only for its callback and must not escape it. Typed and document reads decode directly
from the same streams but return a complete value.

#### Retained live Region streams

Use an explicit Region to reuse one live MCA/MCC handle across several complete or streaming reads:

```kotlin
reader.withRegion(region) {
  readChunk(absoluteChunk) { info, source ->
    consumeCompressedChunk(absoluteChunk.local, info, source)
  }
  readChunk(targetChunk.local) { info, source ->
    consumeCompressedChunk(targetChunk.local, info, source)
  }
}

val liveRegion = reader.openRegion(region)
try {
  liveRegion?.readRegion {
    for (localPosition in chunkPositions) {
      readChunk(localPosition) { info, source ->
        consumeCompressedChunk(localPosition, info, source)
      }
    }
  }
} finally {
  liveRegion?.close()
}
```

`openRegion` returns `null` for a missing `.mca`. Its result owns one handle; callers must exclude `close()` from its
concurrent reads. `withRegion` supplies structured ownership and always closes the Region.

An external save may make any live observation stale or torn. Callers should treat I/O, format, and decoding failures
according to their retry policy.

## Platforms and failures

The system filesystem is available on JVM, Android, Native, and Kotlin/JS Node. Browser and Wasm targets can use the
filesystem-independent format modules but do not receive a partial world filesystem implementation. I/O failures use
Okio `IOException`; `WorldIOException` marks world-storage policy failures and `WorldLockException` marks confirmed
`session.lock` contention.
