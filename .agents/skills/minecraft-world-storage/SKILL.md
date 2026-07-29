---
name: minecraft-world-storage
description: Closed-loop workflow to update, implement, test, and audit this repository's Kotlin Multiplatform binary NBT, Anvil region, compression, dimension-path, and world filesystem support against the matching official server JAR as primary authority, with selected Minecraft Wiki documentation and exact-version third-party sources as secondary evidence. Use for nbt, world-format, world-io, level.dat, playerdata, chunk/entity/POI region files, save compatibility, or world-storage freshness audits.
---

# Minecraft World Storage

Bring `nbt`, `world-format`, and `world-io` to the selected Minecraft release and finish with an official-server
generate, library rewrite, and official-server reload cycle.

## Command interface

Invoke this skill with one of:

```text
$minecraft-world-storage
$minecraft-world-storage <minecraft-release>
$minecraft-world-storage protocol:<decimal-id>
```

Accept zero or one target argument. With no argument, select the stable target resolved by the Minecraft Wiki refresh.
Pass an explicit target unchanged to:

```powershell
.\gradlew.bat refreshProtocolSpecification "-PprotocolTarget=<target>"
```

Reject malformed or extra arguments. Never mix sources from different target snapshots.

## Start every invocation

1. Read [references/storage-rules.md](references/storage-rules.md) and
   [references/workflow.md](references/workflow.md) completely.
2. Read the repository and applicable module `AGENTS.md` files.
3. Preserve unrelated user changes.
4. Run the target refresh and source preparation as separate Gradle invocations:

   ```powershell
   .\gradlew.bat refreshProtocolSpecification ["-PprotocolTarget=<target>"]
   .\gradlew.bat prepareWorldStorageUpdate
   ```

   A release refresh may change the JDK required to analyze the official server, so a single Gradle invocation is
   invalid.
5. Read the refreshed protocol snapshot, locate the exact official NBT, region-file, compression, dimension-path, and
   storage sources, then build a dependency-ordered work queue.
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
this skill. Derive them anew from the refreshed Wiki pages, official reports, decompiled JAR, and executable tests.

## Gradle and language-model boundary

Use Gradle for deterministic work: target selection, downloads, hashes, official JAR unpacking/decompilation, auxiliary
source acquisition, compilation, unit tests, malformed-input tests, JVM reference-library differentials, real filesystem
tests, and official-server interoperability.

Use language-model judgment for Wiki prose, semantic format interpretation, idiomatic API design, and discrepancies that
cannot be normalized mechanically. When a repeated deterministic manual step appears, implement a Gradle task or script
and update this skill before continuing.

Gradle and its scripts own `build/` and never access `temp/`. Only the language-model workflow may use repository-root
`temp/` for scratch notes, manual extraction, ad hoc comparisons, or context-compaction state. Leave
`.gitignore` unchanged.

## Implementation loop

For each coherent batch:

1. inspect the matching official source and then the target Wiki description;
2. inspect auxiliary implementations only where useful;
3. implement the smallest idiomatic KMP API at the correct layer;
4. add valid, boundary, malformed, limit, round-trip, and cross-implementation tests at every affected layer;
5. run focused compilation and layer tests;
6. run the official world rewrite/reload test when binary NBT, compression, container layout, paths, or filesystem
   behavior changes;
7. update module documentation and agent guidance when stable architecture or workflow rules change;
8. rerun the work queue and repeat until no gap remains.

Finish with:

```powershell
.\gradlew.bat verifyWorldStorageUpdate
```

Then run the representative multiplatform compilation commands in
`references/workflow.md`.

Completion requires stream and byte-array NBT, every official region compression path, inline and external chunks,
chunk/entity/POI containers, standalone NBT files, current dimension and player-storage paths, historical path access
where the public API promises it, hostile-input limits, filesystem round trips, and the exact official server accepting
files rewritten by this library. A final interop result never replaces the lower testing layers.

## Analysis Java

The official server's Java requirement belongs only to analysis and interoperability. It must not change the library
bytecode target, Kotlin language version, Android target, or native/JS publication matrix.

Do not install a JDK automatically. If the exact official server requires a newer JDK that is unavailable, ask the user
to install it, then let Gradle discover it or pass its path through the documented analysis-Java property.

## Self-correction

When the workflow reveals a repeatable omission, stale assumption, unstable step, or false-positive gate:

1. stop the affected sequence;
2. patch this skill, its reference, or the responsible Gradle task;
3. validate this skill with the skill-creator `quick_validate.py`;
4. forward-test the changed task;
5. re-read the changed instructions and resume the same work queue.

Keep changing evidence in generated reports or checked-in specification state, not in skill prose.
