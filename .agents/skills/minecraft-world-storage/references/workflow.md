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
server, synchronously closes the process, opens its Host working directory through the documented same-filesystem
backdoor, rewrites the world in place through `world-io`, restarts the server, and requires a successful reload. Its
shared runner remains in `commonTest` with an explicit Host-filesystem warning, while thin annotated entries exist only
in standard JVM and desktop Native test source sets. Android host tests inherit portable `commonTest` coverage without
repeating this JVM-hosted official scenario. Device and simulator source sets do not invoke it.
