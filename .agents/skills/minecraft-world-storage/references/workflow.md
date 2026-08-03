# World storage update workflow

## Preparation

Confirm the buildSrc target and generate official analysis:

```shell
./gradlew -q minecraftVersion
./gradlew officialMinecraftAnalysis
```

For an explicit release request, edit only `MinecraftTarget.MINECRAFT_VERSION`. Read
`build/generated/official-minecraft/<version>/target/target.json` to obtain official protocol/artifact facts.

When structured reports and executable behavior are insufficient, manually decompile the exact server into `temp/` and
search by behavior and constants, not only remembered class names. Locate:

- binary NBT input, output, accounting, and compressed-file helpers;
- region file, region compression registration, allocation bitmap, external sidecar, and storage-cache code;
- dimension resource-key to filesystem-path resolution;
- level-resource constants and every file migration that distinguishes current and legacy layouts, including player
  data, advancements, and stats;
- official compression-library versions bundled with the server.

Build the initial inventory from the matching official storage implementation and bundled dependencies. Use the Wiki's
NBT, region-file, and chunk-format prose as secondary descriptive evidence and treat natural-language notes as review
items. Consult matching MCProtocolLib and then Minestom snapshots only after official and Wiki evidence.

## Focused verification

Use the smallest applicable tasks while iterating:

```shell
./gradlew :compression:jvmTest
./gradlew :nbt:jvmTest
./gradlew :world-format:jvmTest
./gradlew :world-io:jvmTest
```

The format suite includes reference-library differentials. `:world-io:jvmTest` uses real temporary files and generates a
world with the exact official server, rewrites it, and requires that server to reload it.

## Completion gates

After all affected JVM suites pass, run:

```shell
./gradlew allTests
```

This selects the modules' standard KMP aggregates; do not add a root verification task or a JS filesystem target merely
to make this list symmetrical.

The completion report identifies source disagreements, unimplemented custom extensions, unsupported targets, and any
remaining uncertainty. It does not copy volatile counts or version facts into this reference.
