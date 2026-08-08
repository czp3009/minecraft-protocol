---
name: minecraft-protocol-modeling
description: Update, implement, test, or audit this repository's Kotlin Multiplatform Minecraft Java Edition protocol stack against its selected official release. Use for release changes, packets and shared types, MinecraftFormat and registries, vanilla Configuration data, transport, sessions, authentication, client/server APIs, protocol interoperability, or completeness audits.
---

# Minecraft protocol modeling

Execute the protocol development path defined by repository source, `AGENTS.md`, and standard Gradle tasks. The skill
coordinates investigation and implementation; it does not replace generators, tests, or the task graph.

## Establish current state

1. Read the root `AGENTS.md` and every affected module's nearest `AGENTS.md`.
2. Read [references/audit-and-update.md](references/audit-and-update.md).
3. Inspect the worktree, current source annotations and tests, build wiring, and available generated analysis before
   deciding what is missing.
4. Apply the root guide's target-selection rule and confirm the selected release before reading release-specific
   evidence.

A protocol number discovered in evidence is not authorization to select a different Minecraft release. An explicit
release change is followed by `./gradlew officialMinecraftAnalysis` before target-specific modeling.

## Execute the human development loop

1. Build a dependency-ordered work queue from the concrete request and the completeness checklist.
2. Inspect the matching Gradle-produced reports and executable official behavior using the evidence order in root
   `AGENTS.md`. Use manual decompilation or exact-version secondary sources only when higher evidence is insufficient.
3. Implement each conclusion in the owning runtime, build, processor, or test layer defined by the applicable
   `AGENTS.md`.
4. Add or update standard tests at every affected layer, including invalid and boundary behavior where relevant.
5. Run the narrowest affected JVM test tasks after each coherent batch. Do not substitute an interoperability result for
   lower-layer tests.
6. Repeat the inventory after implementation so newly exposed gaps enter the same queue.
7. After the JVM path is stable, run the applicable standard platform tasks or `./gradlew allTests`.

External-peer tests use `minecraft-test-support` through standard test tasks. The Fixture Host chooses stopped templates
for all-default optional configuration and prepared-runtime fresh state for any non-default optional field; a required
headless-client player name does not disable template reuse. Do not add a public workspace policy, custom fixture task,
or direct launcher invocation. Preserve workspace isolation when touching Fixture Host materialization: immutable
client-runtime and server-library directories may use one directory symbolic link with a per-file hard-link-or-copy tree
as fallback; other immutable runtime, launcher, mod, and processed-cache files may use hard links with copy fallback.
Copy mutable template files, and unlink directory symbolic links during cleanup without following their targets. Packet
observations, not HeadlessMC text, establish protocol states such as Play.

Apply the repository capability matrix when changing targets: pure model/serialization code keeps all supported KMP
targets, while Minecraft TCP modules exclude browser, D8, and Wasm/WASI runtimes. Fixture annotated entries use the
`fixturetest` package and rely on the exact unsupported leaf-task routing in root `AGENTS.md`.

Use only the existing Gradle producers and standard test tasks. Optional manual evidence stays within the `temp/`
boundary defined by root `AGENTS.md`.

An explicit read-only audit reports concrete gaps without writing. Every other invocation implements and verifies gaps
within the requested scope before reporting completion.

## Report

Report concrete source changes, evidence that determined non-obvious modeling decisions, standard tasks run in the
current worktree, interoperability results, unresolved evidence, and unsupported platform capabilities. Counts,
self-round-trips, stale reports, and remembered release facts are not completion evidence.
