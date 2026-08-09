# world-io

World-file adapters built on Okio `Path`, `FileSystem`, and positional `FileHandle` APIs.

`WorldRegionStore` reads and mutates terrain, entity, or point-of-interest regions one chunk at a time. Updates allocate
new sectors, write them in place, commit the MCA header, and retain the old bytes without replacing or shrinking the
whole region file. Timestamps and the internal/`.mcc` threshold are automatic. `WorldRegionStoreConfiguration` can
select any official region compression ID supported by modern Anvil files—GZIP, ZLIB, NONE, or LZ4—or CUSTOM when the
store's `RegionChunkNbtFormat` has a matching registered codec. Raw CUSTOM chunks can be stored without decoding them.

Only the modern Anvil `.mca` format is supported. Legacy Region `.mcr` files are neither read nor converted; the
explicit legacy path variants below select directory layouts only.

```kotlin
val paths = MinecraftWorldPaths(worldRoot)
val terrain = WorldRegionStore(paths)

val chunk = terrain.readChunkNbt(ChunkPosition(x, z))
terrain.writeChunkNbt(
    position = ChunkPosition(x, z),
    document = updated,
)
terrain.close()
```

`MinecraftWorldPaths` models the current namespaced dimension, player, and saved-data layout, plus explicit legacy
variants. `LevelDataStore`, `PlayerDataStore`, `SavedDataFileStore`, and `Utf8JsonFileStore` preserve their different
official backup, fallback, compression-detection, and direct-write policies. `MinecraftWorldAccess.open` adds a
system-filesystem lease backed by the official `session.lock` protocol.

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
module's public dependency contract, while `kotlinx-io-core` and the maintained `kotlinx-io-okio` adapters remain
implementation details used by the existing NBT and Anvil stream formats. The adapters also preserve the active stream
API's I/O exception hierarchy across that boundary.

Public filesystem failures remain catchable as Okio `IOException`. Confirmed `session.lock` contention—including the
platform-specific marker-write violation that can occur before a non-blocking lock attempt—is reported as
`WorldLockException` on every supported filesystem platform; message, cause, and stack details are not a cross-platform
contract.

The JS target is Node-only and uses synchronous host filesystem calls. Durable system-file writes issue `fsync`, while
`MinecraftWorldAccess` uses `fs-native-extensions` 1.5.0 for the official non-blocking exclusive `session.lock`
protocol; closing the lease or terminating the process releases the OS lock while retaining the lock file. That addon
publishes prebuilds for Linux, macOS, and Windows on x64 and arm64. Browser and Wasm consumers use `nbt`,
`nbt-serialization`, and `world-format` through trees, streams, or byte arrays instead.

The official generate/rewrite/reload test, including its annotated entry, is isolated in `hostFilesystemTest`. Every
execution rewrites four marked chunks in one region through the platform's public store API using GZIP, ZLIB, NONE, and
LZ4, then requires the matching official server to load each marker, save the world, restart, and load them again. JVM,
JS Node, and desktop Native test source sets that can open the Fixture Host's absolute path inherit it directly, without
platform-specific entry files. Its non-default server properties automatically select a fresh world rather than the
stopped default server template.
