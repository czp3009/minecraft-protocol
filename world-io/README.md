# world-io

World-file adapters built on Okio `Path`, `FileSystem`, and positional `FileHandle` APIs.

`WorldRegionStore` reads and mutates terrain, entity, or point-of-interest regions one chunk at a time. Updates allocate
new sectors, write them in place, commit the MCA header, and retain the old bytes without replacing or shrinking the
whole region file. Timestamps and the internal/`.mcc` threshold are automatic, and write compression defaults to the
official server's default `deflate` setting:

```kotlin
val paths = MinecraftWorldPaths(worldRoot)
val terrain = WorldRegionStore(
    paths = paths,
    configuration = WorldRegionStoreConfiguration(
        writeCompression = RegionCompression.LZ4,
    ),
)

val chunk = terrain.readChunkNbt(ChunkPosition(x, z))
terrain.writeChunkNbt(
    position = ChunkPosition(x, z),
    document = updated,
)
terrain.close()
```

Opening a store never scans, migrates, or recompresses existing chunks, so one `.mca` may contain chunks with different
compression registrations. Only the modern Anvil `.mca` format is supported.

`MinecraftWorldPaths` models the namespaced dimension, player, and saved-data layout. `LevelDataStore`,
`PlayerDataStore`,
`SavedDataFileStore`, and `Utf8JsonFileStore` cover the remaining world files with their official backup, fallback, and
compression policies. `MinecraftWorldAccess.open` adds a system-filesystem lease backed by the official `session.lock`
protocol and shares one region-store configuration across every dimension:

```kotlin
val world = MinecraftWorldAccess.open(
    root = worldRoot,
    configuration = MinecraftWorldAccessConfiguration(
        regionStoreConfiguration = WorldRegionStoreConfiguration(
            writeCompression = RegionCompression.LZ4,
        ),
    ),
)
try {
    world.writeChunkNbt(ChunkPosition(x, z), updated)
} finally {
    world.close()
}
```

Use `LiveMinecraftWorldReader` when another process, such as the official server, owns and mutates the world. It does
not acquire `session.lock` and never creates, repairs, or rewrites world files, so it is suitable for rendering or
inspecting a running server's world. Concurrent saves can still produce stale or torn input, so callers should treat
I/O, format, and decompression failures as an expected retry condition:

```kotlin
val reader = LiveMinecraftWorldReader.open(worldRoot)
try {
    val chunk = reader.readChunkNbt(ChunkPosition(x, z))
} finally {
    reader.close()
}
```

The default filesystem is Okio `FileSystem.SYSTEM` on JVM, Android, and Native, and the Node filesystem on Kotlin/JS
Node; raw stores accept any other Okio `FileSystem`. Filesystem and store entry points expose I/O failures through Okio
`IOException`, with `WorldIOException` marking this module's own policy failures and `WorldLockException` marking
confirmed `session.lock` contention. Data-format failures from `world-format` and NBT propagate unchanged.
