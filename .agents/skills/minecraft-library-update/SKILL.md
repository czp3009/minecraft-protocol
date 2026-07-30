---
name: minecraft-library-update
description: Top-level closed-loop orchestrator for updating and verifying this repository's complete Kotlin Multiplatform Minecraft library for the latest stable or explicitly selected release. Use when a Minecraft release changes, when all protocol and world-storage modules must be refreshed together, or when a complete freshness and interoperability audit is requested.
---

# Minecraft Library Update

Coordinate the network-protocol and world-storage workflows with one target, then finish with whole-library
verification.

This is optional agent orchestration over the same Gradle workflow documented for humans. It is not a project input;
Gradle and production code must remain fully functional if this skill and all other agent files are removed.

## Command interface

Invoke this skill with one of:

```text
$minecraft-library-update
$minecraft-library-update <minecraft-release>
$minecraft-library-update protocol:<decimal-id>
```

Accept zero or one release argument. Zero arguments retain `MinecraftTarget.version`; an explicit release changes that
single buildSrc constant before refresh. Reject protocol-ID selectors, malformed releases, and extra arguments.

## Required sub-workflows

Before acting, read these files and every reference they require completely:

1. `../minecraft-protocol-modeling/SKILL.md`;
2. `../minecraft-world-storage/SKILL.md`;
3. [references/orchestration.md](references/orchestration.md).

Treat this as update mode unless the user explicitly asks for a read-only audit. Preserve unrelated user changes.

## Orchestration

1. Select the target once in `MinecraftTarget.version` and run `refreshProtocolSpecification`.
2. Execute the `minecraft-protocol-modeling` workflow through its affected JVM suites.
3. Execute the `minecraft-world-storage` workflow against the same official artifact through `:world-io:jvmTest`.
4. Reuse the same generated reports and verified artifacts; never select or refresh another target mid-invocation.
5. Resolve cross-module effects in dependency order. Shared NBT changes require both workflows' lower-level and
   interoperability gates.
6. Run:

   ```powershell
   .\gradlew.bat test
   ```

7. Confirm the aggregate ran official codec/server/headless-client/world tests through standard platform test tasks.

Do not replace a failed lower layer with a successful end-to-end test. Continue until every applicable deterministic
gate passes or a genuine external prerequisite requires user action.

## Workspace and evidence

Gradle owns downloaded artifacts, generated source, worlds, reports, and tests under `build/`. Human/agent decompilation
and third-party reference checkouts are exceptional scratch under `temp/`, never Gradle inputs. Leave
`.gitignore` unchanged.

Keep target-dependent facts in refreshed specification state and generated reports. Skill prose contains only stable
workflow and architecture rules.

The matching official JAR is the primary source and final behavioral authority; the Wiki is secondary, followed by
exact-version MCProtocolLib and Minestom. The final report identifies the selected target from generated state, passed
gates, source disagreements, nullable uncertainty, unsupported platforms, custom-extension limits, and any genuine
external prerequisite.

## Self-correction

If either sub-workflow exposes a repeatable process defect, patch the owning skill or Gradle task, validate every
changed skill with the skill-creator
`quick_validate.py`, forward-test the change, and resume the same invocation. Do not preserve orchestration fixes only
in conversation.
