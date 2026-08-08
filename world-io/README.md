# world-io

World-file adapters built on Okio `Path`, `FileSystem`, and positional `FileHandle` APIs.

`WorldRegionStore` reads and mutates terrain, entity, or point-of-interest regions one chunk at a time. Updates allocate
new sectors, write them in place, commit the MCA header, and retain the old bytes without replacing or shrinking the
whole region file. Timestamps and the internal/`.mcc` threshold are automatic.

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

The default filesystem is Okio `FileSystem.SYSTEM` on JVM, Android, and Native, and `NodeJsFileSystem` on Kotlin/JS
Node. Raw stores may receive another Okio `FileSystem`, including the fake filesystem in tests. Okio is part of this
module's public dependency contract, while `kotlinx-io-core` and the maintained `kotlinx-io-okio` adapters remain
implementation details used by the existing NBT and Anvil stream formats. The adapters also preserve the active stream
API's I/O exception hierarchy across that boundary.

The JS target is Node-only and uses synchronous host filesystem calls. Durable system-file writes issue `fsync`, while
`MinecraftWorldAccess` uses `fs-native-extensions` 1.5.0 for the official non-blocking exclusive `session.lock`
protocol; closing the lease or terminating the process releases the OS lock while retaining the lock file. That addon
publishes prebuilds for Linux, macOS, and Windows on x64 and arm64. Browser and Wasm consumers use `nbt`,
`nbt-serialization`, and `world-format` through trees, streams, or byte arrays instead.

The official generate/rewrite/reload test, including its annotated entry, is isolated in `hostFilesystemTest`. JVM, JS
Node, and desktop Native test source sets that can open the Fixture Host's absolute path inherit it directly, without
platform-specific entry files. Its non-default server properties automatically select a fresh world rather than the
stopped default server template.
