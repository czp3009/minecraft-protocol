# World-storage audit and update workflow

Use this reference to turn an exact release or storage request into a concrete investigation and verification queue.

## Investigation inventory

Inspect the selected official implementation for the affected behavior:

- the NBT value algebra, binary input/output, root forms, modified UTF, accounting, and compressed-file composition;
- region headers, sector allocation, compression registration, external sidecars, and storage-cache behavior;
- chunk, entity, and POI region handling;
- dimension resource-key to filesystem-path resolution;
- current and historical paths for `level.dat`, player data, advancements, and statistics;
- official compression dependencies and wrapper formats.

Search by behavior and constants rather than remembered class names. Gradle-produced reports and executable behavior are
the first inputs. Manual decompilation is used only when those inputs do not expose the required semantics.

## Dependency order

1. `nbt` value algebra and logical serializer handoff;
2. `nbt-serialization` tree conversion and binary streams;
3. `world-format` coordinates, containers, library-backed compression dispatch, external chunks, and NBT composition;
4. `world-io` paths, standalone files, atomic replacement, and region directories;
5. official world interoperability;
6. affected standard platform tests.

## Focused JVM tasks

```shell
./gradlew :nbt:jvmTest
./gradlew :nbt-serialization:jvmTest
./gradlew :world-format:jvmTest
./gradlew :world-io:jvmTest
```

Run only the affected prefix while iterating and include downstream tasks after a shared binary change.

The `:world-io:jvmTest` and `:world-io:jsNodeTest` interoperability entries ask the Fixture Host to generate a world
with the exact official server, synchronously close the process, open its Host working directory through the documented
same-filesystem backdoor, and rewrite four marked chunks in one `.mca` through the platform's public `WorldRegionStore`
API using GZIP, ZLIB, NONE, and LZ4. The matching official server must then load every marker, save, restart, and load
them again. Non-default server properties automatically bypass the stopped default world template. The shared runner and
its annotated entry live in the unique `hostFilesystemTest` capability source set with an explicit Host-filesystem
warning. Standard JVM, Node, and desktop Native test source sets depend on it directly and need no platform entry files.
Android host tests inherit portable `commonTest` coverage without repeating this JVM-hosted official scenario. Device,
simulator, browser, D8, and Wasm/WASI source sets do not invoke it. This real process path may use a longer `runTest`
timeout than portable tests; do not replace its observed readiness and log evidence with delays.
