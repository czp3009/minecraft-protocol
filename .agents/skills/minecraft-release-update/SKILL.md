---
name: minecraft-release-update
description: Coordinate an incremental or complete alignment of this repository's handwritten Minecraft-dependent code with the repository-selected official release. Use when changing MinecraftTarget.MINECRAFT_VERSION, updating from one Minecraft release to another, auditing release completeness across protocol and world storage, or triaging version-change failures produced by official analysis, KSP, generated vanilla data, or official-peer tests.
---

# Minecraft release update

Coordinate the existing Gradle pipeline and the narrow domain skills. Do not create a second update pipeline or edit
generated evidence or source.

This skill is optional guidance for the same work a human performs. Treat current source, Gradle wiring, and tests as
authoritative. If this playbook is stale, correct the playbook; never add project machinery merely to make the skill
executable.

## Establish the target

1. Inspect the worktree before changing anything.
2. Run `./gradlew -q minecraftVersion` to resolve the current target.
3. Change `MinecraftTarget.MINECRAFT_VERSION` only when the user explicitly requests another release, then run
   `./gradlew prepareOfficialMinecraftData` before target-specific implementation.

`prepareOfficialMinecraftData` prepares the official analysis and extracted-data artifacts from the official server. Run
`./gradlew downloadMinecraftClientJar` only when an affected workflow needs matching client bytecode; use that declared
Gradle producer instead of downloading an artifact manually.

Do not infer a release change from a protocol number or from a secondary project. Keep other `buildSrc` version targets
independent.

## Build the incremental queue

Read [references/routing.md](references/routing.md). Inspect the new official analysis, current handwritten source,
generated-task failures, KSP diagnostics, and official client/server implementation before deciding which domains
changed. Compilation alone does not prove an unchanged wire or storage contract.

Read only the affected leaf skills completely:

- packet payloads and shared protocol values: `../minecraft-protocol-model/SKILL.md`;
- physical packet encodings: `../minecraft-protocol-serialization/SKILL.md`;
- official registries and Configuration data: `../minecraft-protocol-vanilla-data/SKILL.md`;
- session, client, and server lifecycle: `../minecraft-protocol-flow/SKILL.md`;
- NBT value or binary semantics: `../minecraft-nbt/SKILL.md`;
- filesystem-independent Anvil formats: `../minecraft-world-format/SKILL.md`;
- selected-release standalone world-file models and serializers, world paths, and disk behavior:
  `../minecraft-world-io/SKILL.md`.

Treat every library-provided serializable Minecraft model as a handwritten version-dependent contract even when its
source still compiles. For each affected standalone file, compare the model and serializer with the matching official
writer, reader, codec, and generated output. Revisit every field's name, type, presence, nullability, default, and
dynamic/raw boundary; a field the selected release always writes is required and non-null without an old-version
default. Update model tests and user documentation together, and do not preserve old schema branches unless historical
compatibility is explicitly requested.

For standalone world files, explicitly account for `LevelDat` and every nested model, `PlayerAdvancements` and its
heterogeneous root serializer, and `PlayerStatistics`; none may be waved through because its generated serializer still
compiles. Reconfirm the current paths, root encoding/compression, strict unknown-field behavior, and direct-stream file
adapters at the same time.

For a repository-wide completeness request, account for every leaf domain: either apply its workflow or record concrete
official evidence that its contract did not change. Do not create or load a transport skill merely because the release
changed. Audit `protocol-transport` only if official framing, compression-envelope, or stream-encryption behavior
actually changed.

Use the cross-cutting routing in the reference for handwritten KSP, authentication, transport, official-oracle, and
fixture infrastructure. These are not generated outputs and do not acquire separate release skills.

## Execute and verify

Implement lower-layer changes before their consumers. Let standard compile and test tasks invoke their declared
analyzers, generators, KSP processors, and fixtures. Never edit or commit files below `build/generated`, copy generated
Kotlin into source directories, or add refresh/freshness tasks.

Start with affected JVM suites, then run downstream official-peer suites and applicable standard platform tests. Never
run Gradle wrapper invocations concurrently.

Report the selected release, leaf workflows used, handwritten changes, official evidence behind non-obvious decisions,
standalone-file models and serializers reviewed, standard tasks run, unresolved evidence including
`@UnknownNullability`, and any external prerequisite.
