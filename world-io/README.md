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

The default filesystem is `FileSystem.SYSTEM`; raw stores may receive another Okio `FileSystem`, including the fake
filesystem in tests. Okio is part of this module's public dependency contract, while `kotlinx-io-core` remains an
implementation detail used to bridge the existing NBT and Anvil stream formats. The module targets JVM, Android, and
Native. Okio 3.18.1 does not expose a JS system filesystem, so browser-like consumers use `nbt`, `nbt-serialization`,
and `world-format` through trees, streams, or byte arrays instead.
