# world-io

World-file adapters built on `kotlinx.io.files.FileSystem`.

`NbtFileStore` reads and atomically writes standalone NBT files such as
`level.dat` and player data. `WorldRegionStore` reads and writes chunk, entity, and point-of-interest regions, resolves
external chunk sidecars, and removes obsolete sidecars after a region commit. `MinecraftWorldPaths` provides current
dimension and player-storage paths together with explicit legacy variants.

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
JVM, Android, and Native rather than browser-like JS. Stream-only consumers use `nbt` and `world-format`.

Run `./gradlew :world-io:jvmTest` for the focused filesystem and official-compatibility suite; the standard host
Native test task exercises the same external official-server scenario.
