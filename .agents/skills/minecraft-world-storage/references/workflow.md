# World storage update workflow

## Preparation

Run target refresh and preparation separately:

```powershell
.\gradlew.bat refreshProtocolSpecification
.\gradlew.bat prepareWorldStorageUpdate
```

Add the quoted `-PprotocolTarget` argument to the refresh command for a pinned target. Read the resulting snapshot to
obtain the target and analysis JDK.

Search the exact decompiled server by behavior and constants, not only by remembered class names. Locate:

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

```powershell
.\gradlew.bat :nbt:compileKotlinJvm
.\gradlew.bat :nbt:nbtLayerTest
.\gradlew.bat :world-format:compileKotlinJvm
.\gradlew.bat :world-format:worldFormatLayerTest
.\gradlew.bat :world-io:compileKotlinJvm
.\gradlew.bat :world-io:worldIoLayerTest
```

The format suite includes reference-library differential tests. The file suite uses real temporary files through
`SystemFileSystem`.

Run strong interoperability after relevant changes:

```powershell
.\gradlew.bat officialWorldStorageInteropTest
```

This task generates a world with the exact downloaded official server, decodes every generated region container and
chunk, rewrites standalone NBT and region containers, then requires the same official server to load and save the
rewritten world. Include empty on-disk containers in this audit.

## Completion gates

Run:

```powershell
.\gradlew.bat verifyWorldStorageUpdate
```

Then compile representative publication families:

```powershell
.\gradlew.bat :nbt:compileKotlinJs
.\gradlew.bat :nbt:compileKotlinLinuxX64
.\gradlew.bat :world-format:compileKotlinJs
.\gradlew.bat :world-format:compileKotlinLinuxX64
.\gradlew.bat :world-io:compileKotlinLinuxX64
```

Do not add a JS filesystem target merely to make this list symmetrical.

The completion report identifies source disagreements, unimplemented custom extensions, unsupported targets, and any
remaining uncertainty. It does not copy volatile counts or version facts into this reference.
