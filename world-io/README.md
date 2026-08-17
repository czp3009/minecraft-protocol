# world-io

World-file adapters built on Okio `Path`, `FileSystem`, and positional `FileHandle` APIs. Entries come in tiers: a
locked whole-world lease, a lock-free live reader, a directory-level region store, and the single region-file primitive
the others share.

World-file stores impose no policy-sized maximum read, write, decompression, or allocation limit. APIs returning a
`String`, `JsonElement`, `NbtDocument`, `RegionFile`, or `ByteArray` necessarily retain that complete result; overloads
accepting an Okio or `kotlinx.io` source/sink callback are the low-memory path for large files. Format-intrinsic lengths
remain in force, including NBT modified UTF and Anvil header/location fields.

All three high-level entries support concurrent coroutine calls. The mutable entries, `MinecraftWorldAccess` and
`WorldRegionStore`, allow readers of one logical metadata file or `.mca` file to run concurrently. A writer takes
exclusive access to that logical file, waits for existing readers, and blocks new readers and writers until its file
update is complete; once a writer is waiting, later readers do not bypass it. Operations for different files can run
concurrently. This is writer preference, not a fair/FIFO scheduling contract: queued writers have no promised relative
order, and neither do readers that become eligible together. `LiveMinecraftWorldReader` is the deliberately
uncoordinated bypass-reader described below.

The library does not create or own threads, choose a dispatcher, or impose an open-region limit. Blocking filesystem
I/O, NBT work, and compression run synchronously on the calling thread, so these APIs are not automatically main-safe;
callers choose their own coroutine dispatcher and concurrency with `withContext`, `launch`, or `async`.

For example, an application may place a batch on `Dispatchers.IO` (or its own bounded dispatcher) and launch as many
coroutines as its storage and memory budget can support:

```kotlin
val chunks = withContext(Dispatchers.IO) {
    positions.map { position ->
        async { world.readChunkNbt(position) }
    }.awaitAll()
}
```

The dispatcher determines where the blocking calls run; the mutable store's logical-file admission still preserves
same-file consistency while allowing different files to progress in parallel. The live reader performs independent
bypass reads on those caller-provided workers.

Mutable high-level stores retain entries and file handles only while operations are in flight. Readers briefly register
with a logical-file coordinator but hold no mutex while reading; this registration is what prevents a writer from
overlapping a long read. Exclusive access ends as soon as the file update ends, so remaining readers can proceed
together. The last user of a region closes and flushes its handle; with `syncWrites = false`, a concurrent wave of
operations for one region therefore performs one final close/flush when that wave drains. Sequential calls may reopen
and close the same file each time. With `syncWrites = true`, each write commit also performs its durable flush, but
handle close still waits for the last reader or writer reference. `flush()` pins only entries active when it starts and
is usually a no-op after all operations have returned. `flush()` and `close()` have no internal timeout and may wait for
admitted operations or slow storage.

Cancellation is observed at coroutine admission and waiting points. Once a synchronous filesystem call or region
record/header commit has started, cancellation does not interrupt it between physical writes: the operation completes
that commit and its non-cancellable users, handle, and barrier cleanup before returning `CancellationException`.
Cancellation before exclusive file admission prevents the physical update. A cancelled `flush()` releases every pin but
stops issuing new explicit flushes, so it does not promise that every entry in its original snapshot became durable;
`close()` still completes its full shared cleanup and publishes that cleanup's success or failure to later callers.
Cooperative cancellation therefore leaves either the state from before admission or the complete synchronous update, not
a cancellation-interrupted record/header or replacement sequence. If cancellation races an I/O or cleanup failure,
`CancellationException` remains primary and the other failure is retained as suppressed diagnostic context.

Final-entry cleanup is synchronous, not a background event. If its flush or close fails, the operation performing the
last release fails. Starting owner close after that cleanup completes does not replay the already-reported failure. If
owner close has already sealed admission and is directly waiting for the cleanup when it fails, the close barrier and
its waiters report it too; every call to that same owner close, including calls made after it completes, observes the
barrier's final success or failure.

Logical-file coordination follows the commit boundary rather than individual path names:

| Logical group | Covered paths                                                                                   |
|---------------|-------------------------------------------------------------------------------------------------|
| Region        | One `r.<x>.<z>.mca` and every `c.<chunk-x>.<chunk-z>.mcc` sidecar addressed by its header       |
| Level data    | `level.dat`, `level.dat_old`, and its temporary/recovery paths                                  |
| Player data   | One player's primary, old, temporary, and corrupt-copy paths                                    |
| Saved data    | One canonical namespaced identifier and dimension; `foo` and `minecraft:foo` are the same group |
| Player JSON   | One statistics file or one advancements file                                                    |

The coordinator does not change commit policy. Level and player NBT still write a synced temporary and replace with a
backup; saved data still writes synced GZIP directly; statistics and advancements still truncate and write their final
JSON path directly. A healthy level/player read is shared, but a recoverable primary failure upgrades to exclusive
access before performing promotion or corrupt-copy recovery. There is no transaction or global ordering across logical
groups.

## Streaming large files

Streaming changes only how bytes move through memory, not how they reach storage. In particular, no extra temporary file
or second disk pass is introduced as a memory-saving spool. Sources and sinks lent to a callback are valid only for that
callback; the store owns their completion and closure.

Standalone NBT callbacks expose the decompressed binary NBT stream. This avoids an intermediate compressed or
decompressed `ByteArray`; the example still returns an `NbtDocument`, so the resulting tree itself remains in memory:

```kotlin
val level = world.readLevelData { source ->
    NbtFormat.decodeDocumentFromSource(source)
}
world.writeLevelData { sink ->
    NbtFormat.encodeDocumentToSink(level, sink)
}
```

The same input/output callbacks exist for player data and saved data. A caller can instead use a serializable type with
`NbtFormat.decodeFromSource` and `encodeToSink` to avoid constructing an intermediate `NbtDocument`.

Statistics and advancements expose raw Okio streams, so copying or transforming a very large JSON file does not require
a `String` or `JsonElement`:

```kotlin
world.readAdvancements(playerUuid) {
    backupSink.writeAll(this)
}
world.writeAdvancements(playerUuid) {
    writeAll(restoredSource)
}
```

For structured JSON, `Utf8JsonFileStore` decodes and encodes directly against the file stream. The resulting Kotlin
value is still retained, but no intermediate `String` or `JsonElement` is created:

```kotlin
val jsonFiles = Utf8JsonFileStore()
val path = paths.statistics(playerUuid)
val statistics = jsonFiles.readJson(path, PlayerStatistics.serializer())
jsonFiles.writeJson(path, PlayerStatistics.serializer(), statistics)
```

Raw chunk reads lend the stored compressed payload, including a resolved `.mcc` sidecar, without making a
`RegionChunk` byte array:

```kotlin
val info = world.readChunk(position) { chunkInfo, compressedSource ->
    compressedArchiveSink.writeAll(compressedSource)
    chunkInfo
}
```

Anvil stores the compressed chunk length before its payload, so an NBT convenience write with an initially unknown
compressed length retains that one compressed payload in memory. If the compressed length is already known, stream the
payload directly and write exactly that many bytes:

```kotlin
val compressedLength = requireNotNull(fileSystem.metadata(compressedPath).size)
fileSystem.source(compressedPath).buffer().use { compressedSource ->
    world.writeChunk(position, Compression.ZLIB, compressedLength) {
        writeAll(compressedSource)
    }
}
```

The known-length chunk path selects inline MCA sectors or the normal external MCC strategy automatically. It does not
change the existing in-place MCA commit order or the existing temporary-and-move policy for an MCC sidecar. The same
compressed-source callbacks are available on `WorldRegionStore` and `RegionFileStore`; their read callbacks must consume
the complete lent payload.

## Locked world access

`MinecraftWorldAccess.open` takes the system-filesystem lease backed by the official `session.lock` protocol and shares
one region-store configuration across every storage directory and dimension. It covers `level.dat` with its official
backup and fallback policies, player data, saved data, statistics, advancements, and all region storage:

`session.lock` is the only operating-system file lock used by this high-level lease. It excludes another process or
world owner from the directory; it does not serialize operations inside the lease. Per-logical-file coordination is
in-memory and instance-local, so independent files remain parallel after the lease is acquired.

```kotlin
val world = MinecraftWorldAccess.open(worldRoot)
try {
    val level = world.readLevelData()
    world.writeLevelData(
        level.copy(
            root = NbtCompound(level.root.value + ("LevelName" to NbtString("New name"))),
        ),
    )

    val chunk = world.readChunkNbt(ChunkPosition(x, z))
    world.writeChunkNbt(ChunkPosition(x, z), edited)
} finally {
    world.close()
}
```

## Lock-free live reading

`LiveMinecraftWorldReader` is for when another process, such as the official server, owns and mutates the world. It does
not acquire `session.lock`, use an operating-system file lock, or enter the mutable stores' per-file coordinators. Every
level, player, saved-data, statistics, advancements, MCA, and MCC read is an independent bypass observation; concurrent
calls for the same file are not serialized, and no region handle or per-file entry is retained between calls. The reader
never creates, repairs, or rewrites world files, so it is suitable for rendering or inspecting a running server's world
without delaying that server's write, delete, or replacement operations.

The reader has no mutable lifecycle or cross-call resource and does not need to be closed. Concurrent saves can still
produce stale or torn input, so callers should treat I/O, format, NBT, and decompression failures as an expected retry
condition. Its methods are ordinary synchronous functions because Okio file access is synchronous; call them from
`Dispatchers.IO` or another caller-owned dispatcher when they must not block the current thread:

```kotlin
val reader = LiveMinecraftWorldReader.open(worldRoot)
val level = reader.readLevelData()
val chunk = reader.readChunkNbt(ChunkPosition(x, z))
```

## Direct region-store access

`WorldRegionStore` routes chunk coordinates across every `.mca` file of one region directory without any world lease or
`session.lock` interaction. Give it one region directory — terrain, entities, or points of interest for one dimension —
and read or mutate it one chunk at a time:

```kotlin
val terrain = WorldRegionStore(
    paths = MinecraftWorldPaths(worldRoot),
    storage = RegionStorageDirectory.CHUNKS,
    dimension = DimensionDirectory.Overworld,
)
try {
    val chunk = terrain.readChunkNbt(ChunkPosition(x, z))
    terrain.writeChunkNbt(ChunkPosition(x, z), edited, Compression.LZ4)
    terrain.flush()
} finally {
    terrain.close()
}
```

Updates allocate new sectors, write them in place, commit the MCA header, and retain the old bytes without replacing or
shrinking the whole region file. Timestamps and the internal/`.mcc` threshold are automatic; NBT writes use the
configured default compression or the per-write selection shown above. Opening a store never scans, migrates, or
recompresses existing chunks, so one `.mca` may contain chunks with different compression registrations. Without the
world lease, callers own coordination with any other store instance or direct file user. Calls through one
`WorldRegionStore` are coordinated per `.mca`; calls through separate instances are not.

Only the modern Anvil `.mca` format is supported.

## Single region-file access

`RegionFileStore` is the per-file primitive both region entries above are built on. Its primary construction input is
the exact `.mca` path — region coordinates come from the canonical `r.<x>.<z>.mca` name. External `.mcc` sidecars live
next to the file and are handled automatically: reads resolve them, chunks crossing the official size threshold are
written to them atomically, and rewritten or cleared chunks remove stale ones. It runs the same in-place sector protocol
on that one file without any world lease:

```kotlin
val chunkNbtFormat = RegionChunkNbtFormat()
val store = RegionFileStore.open(mcaPath)
try {
    val position = ChunkPosition(x, z)
    val chunk = store.read(position)?.let(chunkNbtFormat::decode)
    store.write(position, chunkNbtFormat.encode(edited, Compression.ZLIB))
    store.flush()
} finally {
    store.close()
}
```

Chunk coordinates must belong to the opened file's region; coordinates of other regions are rejected instead of routed
to sibling files. `RegionFileStore` performs no read/write/close coordination, including between multiple instances for
one path. Direct users own that responsibility. `WorldRegionStore` provides in-process shared-read/exclusive-write
coordination for each MCA-plus-sidecars group, and `MinecraftWorldAccess` also owns the whole-world `session.lock`
lease.

## Paths and standalone files

`MinecraftWorldPaths` models the namespaced dimension, player, and saved-data layout. `LevelDataStore`,
`PlayerDataStore`, `SavedDataFileStore`, and `Utf8JsonFileStore` cover the remaining world files with their official
backup, fallback, and compression policies, and each can be used independently of the region stores.

```kotlin
val paths = MinecraftWorldPaths(worldRoot)

val levelData = LevelDataStore(paths)
val level = levelData.read()
levelData.write(editedLevel)

val players = PlayerDataStore(paths)
val player = players.read(playerUuid)

val savedData = SavedDataFileStore(paths)
val raids = savedData.read("minecraft:raids")

val advancements = Utf8JsonFileStore().readJson(paths.advancement(playerUuid))
```

The default filesystem is Okio `FileSystem.SYSTEM` on JVM, Android, and Native, and the Node filesystem on Kotlin/JS
Node; raw stores accept any other Okio `FileSystem`. Filesystem and store entry points expose I/O failures through Okio
`IOException`, with `WorldIOException` marking this module's own policy failures and `WorldLockException` marking
confirmed `session.lock` contention. Data-format failures from `world-format` and NBT propagate unchanged.
