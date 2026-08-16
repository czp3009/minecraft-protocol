# world-io

World-file adapters built on Okio `Path`, `FileSystem`, and positional `FileHandle` APIs. Entries come in tiers: a
locked whole-world lease, a lock-free live reader, a directory-level region store, and the single region-file primitive
the others share.

## Locked world access

`MinecraftWorldAccess.open` takes the system-filesystem lease backed by the official `session.lock` protocol and shares
one region-store configuration across every storage directory and dimension. It covers `level.dat` with its official
backup and fallback policies, player data, saved data, statistics, advancements, and all region storage:

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
not acquire `session.lock` and never creates, repairs, or rewrites world files, so it is suitable for rendering or
inspecting a running server's world. Concurrent saves can still produce stale or torn input, so callers should treat
I/O, format, and decompression failures as an expected retry condition:

```kotlin
val reader = LiveMinecraftWorldReader.open(worldRoot)
try {
    val level = reader.readLevelData()
    val chunk = reader.readChunkNbt(ChunkPosition(x, z))
} finally {
    reader.close()
}
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
world lease, callers own coordination with any other writer.

Only the modern Anvil `.mca` format is supported.

## Single region-file access

`RegionFileStore` is the per-file primitive both region entries above are built on. Its primary construction input is
the exact `.mca` path — region coordinates come from the canonical `r.<x>.<z>.mca` name. External `.mcc` sidecars live
next to the file and are handled automatically: reads resolve them, chunks crossing the official size threshold are
written to them atomically, and rewritten or cleared chunks remove stale ones. It runs the same in-place sector protocol
on that one file without any world lease:

```kotlin
val store = RegionFileStore.open(mcaPath)
try {
    val chunk = store.readChunkNbt(ChunkPosition(x, z))
    store.writeChunkNbt(ChunkPosition(x, z), edited, Compression.ZLIB)
    store.flush()
} finally {
    store.close()
}
```

Chunk coordinates must belong to the opened file's region; coordinates of other regions are rejected instead of routed
to sibling files. Instances are not thread-safe, and at most one writable store may cover one file at a time —
`MinecraftWorldAccess` provides that coordination for a whole world through `session.lock`.

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
```

The default filesystem is Okio `FileSystem.SYSTEM` on JVM, Android, and Native, and the Node filesystem on Kotlin/JS
Node; raw stores accept any other Okio `FileSystem`. Filesystem and store entry points expose I/O failures through Okio
`IOException`, with `WorldIOException` marking this module's own policy failures and `WorldLockException` marking
confirmed `session.lock` contention. Data-format failures from `world-format` and NBT propagate unchanged.
