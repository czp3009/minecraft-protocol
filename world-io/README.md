# world-io

Okio-based access to Minecraft world directories. The module combines paths, standalone NBT/JSON files, Anvil region
files, official replacement policies, and `session.lock`; binary formats remain in `nbt-serialization` and
[`world-format`](../world-format/README.md).

Choose the narrowest entry point that owns the behavior you need:

| Entry point                 | Use                                                      |
|-----------------------------|----------------------------------------------------------|
| `MinecraftWorldAccess`      | Mutate a complete world while holding its `session.lock` |
| `LiveMinecraftWorldReader`  | Read a world currently owned by another process          |
| `WorldRegionStore`          | Access all `.mca` files in one region directory          |
| `RegionFileStore`           | Access one `.mca` file without higher-level coordination |
| Standalone stores and paths | Compose individual files without a whole-world owner     |

## Whole-world access

`MinecraftWorldAccess` covers level data, player data, saved data, statistics, advancements, and terrain/entity/POI
regions. Typed standalone files use the selected-release models from `world-format`:

```kotlin
val world = MinecraftWorldAccess.open(worldRoot)
try {
    val level = world.readLevelData<LevelDat>()
    world.writeLevelData(level.copy(data = level.data.copy(levelName = "Edited world")))

    val statistics = world.readStatistics<PlayerStatistics>(playerUuid)
    world.writeStatistics(playerUuid, statistics)

    val advancements = world.readAdvancements<PlayerAdvancements>(playerUuid)
} finally {
    world.close()
}
```

The reified methods are the normal API. Overloads accepting a serializer remain available when the type alone cannot
select the desired serializer. Decoding and encoding connect that serializer directly to the file stream.

The built-in models match only the repository-selected Minecraft release and reject unknown fields by default. Use the
complete-value or raw APIs when unmodeled data must survive:

```kotlin
val levelDocument = world.readLevelDataDocument()
val statisticsJson = world.readStatistics<JsonElement>(playerUuid)
val advancementText = world.readAdvancementsText(playerUuid)
```

`MinecraftWorldAccess` holds `session.lock` until `close()`. Reads of one logical file may run together; its writes are
exclusive, while different files can progress concurrently. Filesystem and codec work remain synchronous on the calling
thread, so callers choose an appropriate dispatcher. Cancellation can stop an operation while it waits for admission;
once a synchronous file commit begins, that commit and its cleanup finish before cancellation is propagated.

The API preserves each official storage policy: level and player NBT use temporary files and backups, saved data writes
GZIP directly, player JSON truncates its final path, and region updates commit in place with `.mcc` sidecars when
needed.

## Reading a live world

`LiveMinecraftWorldReader` takes no lock, retains no handles between calls, and never repairs or writes files:

```kotlin
val reader = LiveMinecraftWorldReader.open(worldRoot)
val level = reader.readLevelData<LevelDat>()
val chunk = reader.readChunkNbt(ChunkPosition(x, z))
```

An external save may make an individual observation stale or torn, so callers should treat I/O and decoding failures as
retryable. The reader is synchronous and does not need to be closed.

## Region access

`WorldRegionStore` routes chunk coordinates across one terrain, entity, or POI directory:

```kotlin
val terrain = WorldRegionStore(
    paths = MinecraftWorldPaths(worldRoot),
    storage = RegionStorageDirectory.CHUNKS,
    dimension = DimensionDirectory.Overworld,
)
try {
    val chunk = terrain.readChunkNbt(ChunkPosition(x, z))
    terrain.writeChunkNbt(ChunkPosition(x, z), edited, Compression.LZ4)
} finally {
    terrain.close()
}
```

Use `RegionFileStore.open(mcaPath)` when the caller wants the uncoordinated byte-level primitive for exactly one region
file. Both stores preserve each chunk's compression and handle external `.mcc` payloads automatically.

## Streaming and standalone stores

Typed methods avoid an intermediate byte array, text value, or NBT/JSON tree. Source/sink callback overloads are
available when the result itself should not be materialized, for example copying an advancement file:

```kotlin
world.readAdvancements(playerUuid) {
    backupSink.writeAll(this)
}
world.writeAdvancements(playerUuid) {
    writeAll(restoredSource)
}
```

`MinecraftWorldPaths`, `NbtFileStore`, `LevelDataStore`, `PlayerDataStore`, `SavedDataFileStore`, and
`Utf8JsonFileStore` expose the same building blocks independently. Raw stores accept a caller-provided `FileSystem`;
their sources, sinks, and handles remain owned by the store for the duration documented by each callback.

The system filesystem is available on JVM, Android, Native, and Kotlin/JS Node. Browser and Wasm targets can use the
filesystem-independent format modules but do not receive a partial world filesystem implementation. I/O failures use
Okio `IOException`; `WorldIOException` marks world-storage policy failures and `WorldLockException` marks confirmed
`session.lock` contention.
