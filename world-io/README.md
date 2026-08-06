# world-io

World-file adapters built on `kotlinx.io.files.FileSystem`.

`NbtFileStore` reads and atomically writes standalone NBT files such as `level.dat` and player data. `WorldRegionStore`
reads and writes chunk, entity, and point-of-interest regions, resolves external chunk sidecars, and removes obsolete
sidecars after a region commit. `MinecraftWorldPaths` provides current dimension and player-storage paths together with
explicit legacy variants.

```kotlin
val paths = MinecraftWorldPaths(worldRoot)
val regions = WorldRegionStore(paths)

val chunk = regions.readChunkNbt(ChunkPosition(x, z))
regions.writeChunkNbt(
    position = ChunkPosition(x, z),
    document = updated,
    timestamp = timestamp,
)
```

The default filesystem is `SystemFileSystem`; callers may supply another supported `FileSystem`. This module targets
JVM, Android, and Native rather than browser-like JS. Model-only consumers use `nbt`; stream-only consumers combine
`nbt-serialization` and `world-format` without this filesystem layer.

Writes stream into sibling temporary files and atomically replace their destination. Any serialization, compression,
filesystem, flush, close, or replacement exception is rethrown unchanged after temporary-file cleanup is attempted.
