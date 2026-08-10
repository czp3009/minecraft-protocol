# world-io

World-file adapters built on Okio `Path`, `FileSystem`, and positional `FileHandle` APIs.

`WorldRegionStore` reads and mutates terrain, entity, or point-of-interest regions one chunk at a time. Updates allocate
new sectors, write them in place, commit the MCA header, and retain the old bytes without replacing or shrinking the
whole region file. Timestamps and the internal/`.mcc` threshold are automatic.
`WorldRegionStoreConfiguration.writeCompression` defaults to `RegionCompression.ZLIB`, matching the repository-selected
official server, whose `region-file-compression` property defaults to `deflate`. The official server can instead select
LZ4 with `region-file-compression=lz4`; this library selects the equivalent format with `RegionCompression.LZ4`. The
`writeChunkNbt` overload can select compression for one chunk. Both APIs accept any official modern Anvil
registration—GZIP, ZLIB, NONE, or LZ4—or CUSTOM when the store's `RegionChunkNbtFormat` has a matching registered codec.
Raw CUSTOM chunks can be stored without decoding them.

Compression selection affects only NBT content encoded after it is selected. Opening a store never scans, migrates, or
recompresses existing chunks, and raw `writeChunk` calls preserve the compression and payload already carried by their
`RegionChunk`. Consequently one `.mca` may contain chunks using different registrations.

Only the modern Anvil `.mca` format is supported. Legacy Region `.mcr` files are neither read nor converted; the
explicit legacy path variants below select directory layouts only.

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
terrain.writeChunkNbt(
    position = ChunkPosition(otherX, otherZ),
    document = otherUpdated,
    compression = RegionCompression.ZLIB,
)
terrain.close()
```

`MinecraftWorldPaths` models the current namespaced dimension, player, and saved-data layout, plus explicit legacy
variants. `LevelDataStore`, `PlayerDataStore`, `SavedDataFileStore`, and `Utf8JsonFileStore` preserve their different
official backup, fallback, compression-detection, and direct-write policies. `MinecraftWorldAccess.open` adds a
system-filesystem lease backed by the official `session.lock` protocol. Its `MinecraftWorldAccessConfiguration` shares
one region-store configuration and chunk-NBT format across terrain, entity, and point-of-interest stores in every
dimension. That region configuration does not alter the distinct standalone-file policies.

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
not acquire `session.lock` and never creates, repairs, or rewrites world files. Standalone NBT, JSON, and external
`.mcc` files use short-lived read handles; `.mca` handles are cached for targeted positional reads and released by
`close`. Each region operation reloads the current MCA header. A concurrent save can still produce stale or torn input,
so callers should treat I/O, format, and decompression failures as an expected retry condition.

```kotlin
val reader = LiveMinecraftWorldReader.open(worldRoot)
try {
    val chunk = reader.readChunkNbt(ChunkPosition(x, z))
    // Render this chunk without loading unrelated regions or world files.
} finally {
    reader.close()
}
```

On Windows, live-read handles opened through the system filesystem share read, write, delete, and replacement access
with the matching official server. This is particularly important for external `.mcc` chunks, which the server replaces
rather than updating in place.

The default filesystem is Okio `FileSystem.SYSTEM` on JVM, Android, and Native, and `NodeJsFileSystem` on Kotlin/JS
Node. Raw stores may receive another Okio `FileSystem`, including the fake filesystem in tests. Okio is part of this
module's public dependency contract. No `world-io` filesystem or store signature introduces a `kotlinx.io` stream type;
`kotlinx-io-core` and the maintained `kotlinx-io-okio` adapters remain implementation details used to compose the NBT
and Anvil stream formats.

## Exception contract

Public filesystem and store entry points expose I/O failures through Okio `IOException`. `WorldIOException` identifies
this module's own filesystem-policy failures. Confirmed `session.lock` contention—including a platform-specific
marker-write violation before the non-blocking lock attempt—is the more specific `WorldLockException` on every supported
filesystem target. When a world-format operation originates a `kotlinx.io.IOException` after an official stream
conversion, the enclosing `NbtFileStore` or `WorldRegionStore` entry point converts it to Okio; if the official adapter
had converted an existing Okio failure in the other direction, its preserved Okio cause is restored so a specific
subtype such as `WorldIOException` is not erased.

`RegionFormatException` and NBT grammar/serialization exceptions are data-format failures rather than filesystem I/O and
therefore propagate unchanged. Standard argument/state exceptions and coroutine cancellation also propagate unchanged.
Calling an exposed lower-level `world-format` object directly follows that module's kotlinx.io exception contract.
Public exception categories are stable across targets; messages, causes, and stack traces are not.

The JS target is Node-only and uses synchronous host filesystem calls. Durable system-file writes issue `fsync`, while
`MinecraftWorldAccess` uses `fs-native-extensions` 1.5.0 for the official non-blocking exclusive `session.lock`
protocol; closing the lease or terminating the process releases the OS lock while retaining the lock file. That addon
publishes prebuilds for Linux, macOS, and Windows on x64 and arm64. Browser and Wasm consumers use `nbt`,
`nbt-serialization`, and `world-format` through trees, streams, or byte arrays instead.

The official generate/rewrite/reload test, including its annotated entry, is isolated in `hostFilesystemTest`. Every
execution uses one public `WorldRegionStore` to rewrite four marked chunks in one region with per-write GZIP, ZLIB,
NONE, and LZ4 selections, then requires the matching official server to load each marker, save the world, restart, and
load them again. JVM, JS Node, and desktop Native test source sets that can open the Fixture Host's absolute path
inherit it directly, without platform-specific entry files. Its non-default server properties automatically select a
fresh world rather than the stopped default server template.
