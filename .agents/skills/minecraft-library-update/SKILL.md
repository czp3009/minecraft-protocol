---
name: minecraft-library-update
description: Top-level closed-loop orchestrator for updating and verifying this repository's complete Kotlin Multiplatform Minecraft library for the latest stable or explicitly selected release. Use when a Minecraft release changes, when all protocol and world-storage modules must be refreshed together, or when a complete freshness and interoperability audit is requested.
---

# Minecraft Library Update

Coordinate the network-protocol and world-storage workflows with one target, then finish with whole-library
verification.

## Command interface

Invoke this skill with one of:

```text
$minecraft-library-update
$minecraft-library-update <minecraft-release>
$minecraft-library-update protocol:<decimal-id>
```

Accept zero or one target argument. Zero arguments select the current stable Wiki target. Reject malformed or extra
arguments.

## Required sub-workflows

Before acting, read these files and every reference they require completely:

1. `../minecraft-protocol-modeling/SKILL.md`;
2. `../minecraft-world-storage/SKILL.md`;
3. [references/orchestration.md](references/orchestration.md).

Treat this as update mode unless the user explicitly asks for a read-only audit. Preserve unrelated user changes.

## Orchestration

1. Execute the `minecraft-protocol-modeling` workflow with the invocation target through `verifyProtocolUpdate`.
2. Execute the `minecraft-world-storage` workflow with the same target through
   `verifyWorldStorageUpdate`.
3. When both run in one uninterrupted invocation, reuse the already refreshed snapshot for the second workflow only
   after verifying that its target matches exactly. Do not refresh to "latest" a second time.
4. Resolve cross-module effects in dependency order. Shared NBT changes require both workflows' lower-level and
   interoperability gates.
5. Run:

   ```powershell
   .\gradlew.bat verifyMinecraftLibrary
   ```

6. Run the representative KMP compilation commands required by both sub-workflows.
7. Confirm that the aggregate ran the build-local headless official-client-to-project-server gate required by the
   network skill. Its artifacts are prepared under `build/`; run the separate direct desktop launcher only as an
   additional acceptance test when that environment exists.

Do not replace a failed lower layer with a successful end-to-end test. Continue until every applicable deterministic
gate passes or a genuine external prerequisite requires user action.

## Workspace and evidence

Gradle owns downloaded artifacts, generated worlds, decompilation, reports, and tests under `build/`. Only the language
model uses `temp/` for invocation scratch. Gradle never reads or writes `temp/`. Leave `.gitignore` unchanged.

Keep target-dependent facts in refreshed specification state and generated reports. Skill prose contains only stable
workflow and architecture rules.

The matching official JAR is the primary source and final behavioral authority; the Wiki is secondary, followed by
exact-version MCProtocolLib and Minestom. The final report identifies the selected target from generated state, passed
gates, source disagreements, nullable uncertainty, unsupported platforms, custom-extension limits, and any external
client/JDK prerequisite.

## Self-correction

If either sub-workflow exposes a repeatable process defect, patch the owning skill or Gradle task, validate every
changed skill with the skill-creator
`quick_validate.py`, forward-test the change, and resume the same invocation. Do not preserve orchestration fixes only
in conversation.
