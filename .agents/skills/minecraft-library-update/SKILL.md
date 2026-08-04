---
name: minecraft-library-update
description: Coordinate a complete update or audit of this repository's Kotlin Multiplatform Minecraft protocol and world-storage modules for the repository-selected or explicitly selected release. Use when one Minecraft release change spans both domains or when complete library freshness and interoperability must be established.
---

# Minecraft library update

Execute the same release-wide development path available to a human through repository source, `AGENTS.md`, and standard
Gradle tasks. This skill only coordinates the two domain workflows; it introduces no project input or alternate gate.

## Load the domain workflows

Read these skills completely, including every reference they require:

1. `../minecraft-protocol-modeling/SKILL.md`
2. `../minecraft-world-storage/SKILL.md`

Read the root `AGENTS.md` before editing. Module `AGENTS.md` files remain authoritative for local implementation rules.

## Select one release

Apply the target-selection rule in root `AGENTS.md` once and confirm the result with `./gradlew -q minecraftVersion`.
Both domain workflows use that same release and the same Gradle-produced artifacts for the entire invocation. An
explicit release change is followed by `./gradlew officialMinecraftAnalysis` before target-specific modeling.

## Coordinate the update

1. Inspect the current source, build wiring, generated analysis, tests, and worktree before building a work queue.
2. Order cross-domain changes as build preparation, shared compression/NBT values, binary NBT, packet serialization and
   vanilla data, transport/session/auth/client/server, Anvil containers, filesystem paths, and interoperability.
3. Apply the protocol-modeling workflow and its affected standard JVM tests.
4. Apply the world-storage workflow and its affected standard JVM tests. Changes to shared compression or NBT run both
   domains' dependent suites.
5. Resolve every lower-layer failure before relying on an end-to-end result.
6. After the JVM path is stable, run the applicable standard platform tests and `./gradlew allTests`.

Read-only audit mode applies only when the user explicitly requests an audit without changes. Update mode continues
through implementation and verification until the applicable standard gates pass or a genuine external prerequisite
requires user action.

## Report

Report the selected release, changed owning layers, standard tasks run in the current worktree, official
interoperability results, source disagreements, unresolved nullability or format evidence, unsupported platform
capabilities, and any external prerequisite. Historical reports and one successful end-to-end test do not establish
completion.
