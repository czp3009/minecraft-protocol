---
name: minecraft-protocol-modeling
description: Closed-loop workflow for updating, implementing, testing, and auditing this repository's Kotlin Multiplatform Minecraft Java Edition protocol stack against the single official release selected in buildSrc. Use for release upgrades, packet/type/registry/vanilla-data work, MinecraftFormat, transport, session, auth, client/server APIs, interoperability, or completeness audits.
---

# Minecraft protocol modeling

Align the complete protocol stack with the exact official server selected by
`MinecraftTarget.version`. This skill is an optional development playbook, never a project input. Removing `.agents`
must not affect Gradle, compilation, tests, publication, or runtime behavior.

## Start every invocation

Read all four references before acting:

- [references/modeling-rules.md](references/modeling-rules.md)
- [references/audit-and-update.md](references/audit-and-update.md)
- [references/gradle-workflow.md](references/gradle-workflow.md)
- [references/implementation-state.md](references/implementation-state.md)

Then read the root and nearest module `AGENTS.md`, preserve unrelated changes, and discover current state exactly as
described in `implementation-state.md`.

## Target selection

The one target variable is `MinecraftTarget.version` in
`buildSrc/src/main/kotlin/com/hiczp/minecraft/protocol/buildScript/MinecraftTarget.kt`.

- With no explicit user-selected release, keep that value and print it with `.\gradlew.bat -q minecraftVersion`.
- When the user explicitly requests another release, change only that constant, then run
  `refreshProtocolSpecification`.
- Never add a Gradle property, Wiki-derived default, protocol-ID selector, or second version constant.
- Java remains independently fixed at 25 in `configureAllTargets`; never infer it from Minecraft.

## Automation boundary

Use Gradle for deterministic project work:

- verified official artifact acquisition;
- official data-generator reports;
- non-source-driven protocol constants, static vanilla data, and Configuration payload generation through cacheable task
  types in `buildSrc`;
- source-derived packet and data-component dispatch generation through KSP;
- canonical checked-in official evidence through `refreshProtocolSpecification`;
- official codec/server/headless-client/world tests through standard KMP test tasks;
- compilation, publication source JARs, and final verification.

Do not hand-edit generated Kotlin or transcribe generated data into source. Do not add separate layer, audit, or
interoperability test tasks: test logic belongs in `commonTest`, `jvmTest`, or another standard test source set. Do not
regenerate deterministic output merely to compare it with itself or checked-in evidence. Each preparation task validates
its own work, and Gradle owns reuse through declared inputs, outputs, implementation, and dependencies.

Use agent judgment for semantic work a deterministic program cannot perform: interpreting codec control flow,
nullability, invariants, conditional shapes, Wiki prose, and exact-version third-party disagreements; then encode the
conclusion in idiomatic source and tests.

Wiki/MCProtocolLib/Minestom acquisition and human-oriented decompilation are skill/manual work, not Gradle tasks. Put
invocation-only clones, decompiled trees, and notes under repository `temp/`. Treat `temp/` as exceptional scratch, not
a routine pipeline. If semantic analysis requires a decompiler and none is installed, stop and tell the user which tool
is missing; do not install one silently or add a Gradle decompilation task.

## Evidence order

1. matching official server JAR, its reports, and executable codecs;
2. revision-matched Minecraft Wiki;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

Clear official behavior wins. `protocol-specification` contains deterministic official facts only; semantic judgments
belong in code, tests, and public documentation, not a hand-maintained ledger.

## Execution contract

An ordinary invocation is update mode: identify gaps, implement them, add tests, and continue until completion gates
pass. Use read-only audit mode only when explicitly requested.

For each dependency-ordered batch:

1. inspect the official report/codec and, only when needed, manually inspect decompiled official code under `temp/`;
2. consult secondary evidence for facts the JAR does not expose;
3. update format-neutral models, physical serialization, and higher protocol layers in their owning modules;
4. add focused standard tests, including malformed input and official differentials where relevant;
5. run affected `jvmTest` tasks;
6. repeat until the JVM and official interoperability path is stable.

Finish by refreshing specification evidence when the target or generated facts changed, reviewing the diff, and running
the applicable standard platform tests or the KMP `allTests` selector. Do not start with Native compilation; Windows,
Linux, and macOS are peers, and the current host is only the place this invocation happens to run.

## Self-correction

When the workflow itself proves stale or flaky, fix the narrowest project task, skill reference, or skill script,
forward-test the fix, and resume the same work queue. Stable workflow knowledge must be written here or in the owning
`AGENTS.md`; changing release facts must remain generated project evidence.
