# World-storage audit and update workflow

Use this reference to turn an exact release or storage request into a concrete investigation and verification queue.

## Investigation inventory

Inspect the selected official implementation for the affected behavior:

- binary NBT input, output, named roots, modified UTF, accounting, and compressed-file helpers;
- region headers, sector allocation, compression registration, external sidecars, and storage-cache behavior;
- chunk, entity, and POI region handling;
- dimension resource-key to filesystem-path resolution;
- current and historical paths for `level.dat`, player data, advancements, and statistics;
- official compression dependencies and wrapper formats.

Search by behavior and constants rather than remembered class names. Gradle-produced reports and executable behavior are
the first inputs. Manual decompilation is used only when those inputs do not expose the required semantics.

## Dependency order

1. `compression` raw DEFLATE;
2. the NBT value algebra in `protocol-model` when a shared value changes;
3. `nbt` binary streams;
4. `world-format` coordinates, containers, compression dispatch, external chunks, and NBT composition;
5. `world-io` paths, standalone files, atomic replacement, and region directories;
6. official world interoperability;
7. affected standard platform tests.

## Focused JVM tasks

```shell
./gradlew :compression:jvmTest
./gradlew :nbt:jvmTest
./gradlew :world-format:jvmTest
./gradlew :world-io:jvmTest
```

Run only the affected prefix while iterating and include downstream tasks after a shared binary change.

The `:world-io:jvmTest` interoperability scenario asks the Fixture Host to generate a world with the exact official
server, stops it, downloads a snapshot into a test-local sandbox, rewrites it through `world-io`, uploads changed files,
restarts the server, and requires a successful reload. Test code never receives the host workspace path.
