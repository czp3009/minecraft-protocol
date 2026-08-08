---
name: minecraft-world-storage
description: Update, implement, test, or audit this repository's Kotlin Multiplatform raw compression, binary NBT, Anvil region, dimension-path, and world filesystem support against the selected official Minecraft server. Use for compression, nbt, world-format, world-io, level.dat, playerdata, chunk/entity/POI region files, save compatibility, or storage completeness audits.
---

# Minecraft world storage

Execute the storage development path defined by repository source, `AGENTS.md`, and standard Gradle tasks. The skill
coordinates investigation and implementation; it does not supply build inputs or a separate verification path.

## Establish current state

1. Read the root `AGENTS.md` and the `AGENTS.md` files in every affected storage module.
2. Read [references/workflow.md](references/workflow.md).
3. Inspect the worktree, current APIs and tests, build wiring, and available generated analysis.
4. Confirm the selected release according to the root guide before inspecting release-specific storage behavior.

Storage work never changes the selected release from a protocol number alone. After an explicit release change, run
`./gradlew officialMinecraftAnalysis` before modeling storage behavior.

## Execute the human development loop

1. Build a dependency-ordered queue covering only the requested behavior and its downstream storage layers.
2. Inspect Gradle-produced official evidence and executable behavior first. Use Wiki prose, exact-version secondary
   implementations, or manual decompilation only where the higher evidence is insufficient.
3. Implement the smallest coherent change in the owning module according to its `AGENTS.md`.
4. Add valid, boundary, malformed, limit, round-trip, and cross-implementation tests at the affected layers.
5. Run the affected standard JVM tasks after each coherent batch.
6. Run the official world generate/rewrite/reload scenario when binary NBT, compression, region layout, paths, or
   filesystem behavior changes.
7. Repeat the inventory until the requested scope has no unexplained gap.
8. After the JVM path is stable, run the applicable standard platform tasks or `./gradlew allTests`.

Use the existing Gradle producers and standard tests. Any optional manual investigation follows the `temp/` boundary in
root `AGENTS.md`.

The world interoperability runner supplies non-default official-server properties, so the Fixture Host automatically
uses prepared runtime state without the default world template. It must synchronously stop the server before opening the
Host path. The runner and its annotated entry live in `hostFilesystemTest`; same-filesystem JVM, JS Node, and desktop
Native standard test source sets inherit it directly without platform entry files. Do not expose or select a
template/fresh policy in test code.

Only an explicit read-only audit stops after reporting concrete gaps. Other invocations implement and verify gaps within
the requested scope.

## Report

Report changed owning layers, evidence behind non-obvious format decisions, standard tasks run in the current worktree,
the official reload result when applicable, unsupported target capabilities, custom extension limits, and unresolved
evidence. A successful reload does not replace lower-layer tests.
