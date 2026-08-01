---
name: minecraft-world-storage
description: Closed-loop workflow to update, implement, test, and audit this repository's Kotlin Multiplatform binary NBT, Anvil region, compression, dimension-path, and world filesystem support against the matching official server JAR as primary authority, with selected Minecraft Wiki documentation and exact-version third-party sources as secondary evidence. Use for nbt, world-format, world-io, level.dat, playerdata, chunk/entity/POI region files, save compatibility, or world-storage freshness audits.
---

# Minecraft World Storage

Bring `compression`, `nbt`, `world-format`, and `world-io` to the selected Minecraft release and finish with an
official-server generate, library rewrite, and official-server reload cycle.

This skill is optional guidance for an agent performing the same work a human performs through Gradle. It is not a
project input. Never make Gradle, production code, or tests consume this skill, its references, or files generated only
for the agent.

## Command interface

Invoke this skill with one of:

```text
$minecraft-world-storage
$minecraft-world-storage <minecraft-release>
$minecraft-world-storage protocol:<decimal-id>
```

Accept zero or one Minecraft release argument. With no argument, retain `MinecraftTarget.MINECRAFT_VERSION`. For an
explicitly requested release, change only that buildSrc constant and then run official analysis. Reject protocol-ID
selectors, malformed
releases, and extra arguments. Never mix sources from different releases.

## Start every invocation

1. Read [references/storage-rules.md](references/storage-rules.md) and
   [references/workflow.md](references/workflow.md) completely.
2. Read the repository and applicable module `AGENTS.md` files.
3. Preserve unrelated user changes.
4. Print the selected version and generate deterministic official analysis:

   ```powershell
   .\gradlew.bat -q minecraftVersion
   .\gradlew.bat officialMinecraftAnalysis
   ```

5. Read `build/generated/official-minecraft/<version>/target/target.json`, locate exact official NBT, region-file,
   compression,
   dimension-path, and storage behavior, then build a dependency-ordered work queue.
6. In update mode, implement every queue item and keep iterating until all completion gates pass. Stop after reporting
   only when the user explicitly requests a read-only audit.

## Source authority

Use evidence in this order:

1. the matching official server JAR;
2. current target-relevant Minecraft Wiki documentation;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

The official JAR is the primary source and final behavioral authority. Start with its structured reports and exact
implementation, then use Wiki prose for descriptions and details official code does not expose directly. Auxiliary
projects are clarifying evidence only.

Do not place version numbers, compression tables, directory layouts, data versions, constants, hashes, or test counts in
this skill. Derive them anew from the official artifact/reports and executable tests.

## Gradle and language-model boundary

Use Gradle for deterministic project work: verified downloads, official reports, generated source, compilation, unit
tests, malformed-input tests, JVM reference-library differentials, real filesystem tests, and official-server
interoperability.

Use language-model judgment for Wiki prose, semantic format interpretation, idiomatic API design, and discrepancies that
cannot be normalized mechanically. Human-oriented decompilation and third-party acquisition stay in this skill or
`temp/`, never Gradle. If a needed decompiler is unavailable, tell the user instead of installing it silently.

Gradle and its scripts own `build/` and never access `temp/`. Only the language-model workflow may use repository-root
`temp/` for scratch notes, manual extraction, ad hoc comparisons, or context-compaction state. Leave
`.gitignore` unchanged.

## Implementation loop

For each coherent batch:

1. inspect the matching official source and then the target Wiki description;
2. inspect auxiliary implementations only where useful;
3. implement the smallest idiomatic KMP API at the correct layer;
4. add valid, boundary, malformed, limit, round-trip, and cross-implementation tests at every affected layer;
5. run affected standard JVM tests;
6. run the official world rewrite/reload test when binary NBT, compression, container layout, paths, or filesystem
   behavior changes;
7. update module documentation and agent guidance when stable architecture or workflow rules change;
8. rerun the work queue and repeat until no gap remains.

Finish the storage JVM path with `:world-io:jvmTest`, then run the applicable standard platform tests or the KMP
`allTests` selector once all JVM suites are stable.

Completion requires:

- stream and byte-array NBT;
- every official region compression path;
- inline and external chunks plus chunk, entity, and POI containers;
- standalone NBT files and current dimension and player-storage paths;
- historical path access where the public API promises it;
- hostile-input limits and filesystem round trips;
- acceptance of rewritten files by the exact official server.

A final interop result never replaces the lower testing layers.

## Self-correction

When the workflow reveals a repeatable omission, stale assumption, unstable step, or false-positive gate:

1. stop the affected sequence;
2. patch this skill, its reference, or the responsible Gradle task;
3. validate this skill with the skill-creator `quick_validate.py`;
4. forward-test the changed task;
5. re-read the changed instructions and resume the same work queue.

Keep changing evidence in generated official-analysis data and reports under `build/`, not in skill prose.
