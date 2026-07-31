# Agent development guide

This file is for coding agents. Humans should start with [README.md](README.md), which explains the project, public
usage, prerequisites, and Gradle commands. Read the closest module-level `AGENTS.md` before changing that module; its
rules extend this file.

## Work in the owning layer

- `compression`: portable raw DEFLATE shared by network and world formats.
- `nbt`: binary NBT stream representation.
- `protocol-model`: format-independent packet and shared value models.
- `protocol-serialization`: Minecraft wire encodings and the runtime packet registry.
- `protocol-vanilla-data`: generated typed data captured from the matching official server.
- `protocol-transport`: Ktor sockets, framing, compression, and encryption.
- `protocol-session`: typed dispatch and connection-state transitions.
- `protocol-auth`: offline identity, session services, and cryptographic abstractions.
- `protocol-client` / `protocol-server`: connection orchestration through Play; the server also projects a finite
  initial chunk/entity view.
- `world-format`: filesystem-independent Anvil containers, coordinates, compression, and chunk NBT composition.
- `world-io`: `kotlinx.io.files` paths and filesystem adapters.
- `protocol-symbol-processor`: private KSP processor for source-derived packet and data-component dispatch.
- `minecraft-test-support`: private JVM library for reusable official-artifact and process fixtures used by standard
  tests.
- `protocol-specification`: a handwritten overview plus checked-in target-dependent evidence under `generated/`.
- `buildSrc`: shared Gradle configuration and self-validating generators whose inputs are not Kotlin source.

Do not move physical byte encodings into models, network I/O into serialization, or filesystem behavior into
`world-format`. Gameplay, authoritative ticking worlds, persistence policy, and a general Minecraft server are outside
the library's scope.

Keep the three development layers distinct:

- compiler/build preparation lives in KSP processors or task implementations in `buildSrc`;
- published runtime modules contain only reusable library code and generated runtime source, never generator entry
  points, test launchers, or process scaffolding;
- test-only fixtures live in standard test source sets or the unpublished `minecraft-test-support` library.

## Evidence and modeling

Use the matching official server JAR as the primary behavioral authority. Use the revision-matched Minecraft Wiki for
descriptions and facts that official code does not expose, then exact-version MCProtocolLib and Minestom as tertiary
evidence. Resolve conflicts in favor of official behavior. `protocol-specification/generated` contains only
deterministic facts generated from the official JAR; semantic decisions belong in source, tests, and public
documentation. Its README is a handwritten, version-independent overview and is not a project input.

For nullability, inspect official codecs, constructors, access paths, annotations, optionals, and sentinels first. Fall
back through Wiki, MCProtocolLib, and Minestom only when the preceding evidence is inconclusive. Keep unresolved values
nullable and annotate them with `@UnknownNullability`.

Write idiomatic Kotlin Multiplatform code:

- keep shared models free of buffers and I/O;
- represent logical variants with sealed types and logical serializers;
- put physical representation in `protocol-serialization`;
- use `kotlinx.io.Source`/`Sink` and, where supported, `kotlinx.io.files.FileSystem`;
- omit redundant `public`, and keep implementation helpers internal or private.

Match tests to actual platform capabilities. Exercise portable Web code under the Gradle-provisioned Node/D8 runtimes;
browser-runtime tests are not a repository gate. Keep in-memory protocol state, NBT, compression, Anvil
`ByteArray`/`Source` loading, and chunk composition portable, but do not invent browser filesystem or listening-server
support. Run `world-io` and production socket tests only on targets that expose the required filesystem or networking
primitives. Do not add browser-driver infrastructure unless a task explicitly requires browser-specific behavior.

## Version and deterministic generation

`MinecraftTarget.version` in `buildSrc` is the only manually selected Minecraft version.
`./gradlew -q minecraftVersion` prints it. Do not read a version from checked-in specification files or duplicate it in
module build scripts. The official server JAR's `version.json` supplies the protocol number and other version facts.

Java is independent of Minecraft: `KotlinMultiplatformExtension.configureAllTargets` fixes the whole project at Java 25.
Never infer or change that project toolchain from Mojang metadata.

Automate everything that can be derived exactly:

- Gradle downloads and verifies official artifacts under `build/`, keyed by `MinecraftTarget.version`.
- task types in `buildSrc` generate source from non-source inputs such as the official JAR and reports, and only the
  module owning an output registers the corresponding task;
- the private KSP processor generates source-derived packet definitions and data-component dispatch from annotations;
- the `protocol-vanilla-data` task implemented in `buildSrc` directly captures both official Configuration Known Packs
  branches under the owning module's `build/generated`;
- published source JARs include generated Kotlin;
- `refreshProtocolSpecification` synchronizes canonical official evidence from `build/` into the checked-in
  `protocol-specification/generated` directory.

Compilation and tests may depend on these cacheable tasks and network access on the first run. Production code and
normal compilation and tests must not read or rewrite `protocol-specification`. There is no specification freshness
comparison: `refreshProtocolSpecification` is the only task that writes `generated/`, using Gradle `Sync`, and root
`clean` preserves the complete checked-in directory.

Do not commit generated runtime Kotlin. Do not hand-transcribe deterministic intermediate data into code. Gradle tasks
must not perform human-oriented decompilation, download Wiki/third-party source trees, or invoke agent workflows.

Keep production build automation and test infrastructure separate:

- register a generator or preparation task only in the module that owns its output, even when its reusable task class
  lives in `buildSrc`;
- use KSP for source-to-source generation and a cacheable `buildSrc` task for generation driven by non-source files;
- make each task validate its own downloads and outputs instead of adding a separate verification task or a
  `buildSrc` unit test;
- let Gradle decide reuse from declared inputs, outputs, implementation, and dependency provenance; do not add manual
  freshness comparisons or regenerate deterministic output merely to compare it;
- put shared test setup in ordinary library APIs under `minecraft-test-support` and call them from standard test source
  sets; do not add Gradle preparation tasks, command-line helpers, or system-property wiring for test fixtures.

## Development and verification

Inspect existing code and generated specification state before editing. Preserve unrelated user changes. Prefer focused
JVM tests while iterating:

```shell
./gradlew :affected-module:jvmTest
```

Use Gradle's standard task selector for a repository-wide JVM pass when needed:

```shell
./gradlew jvmTest
```

After the JVM path is stable, `./gradlew allTests` selects every module's standard KMP aggregate. Do not use Native
compilation as the first feedback loop; it is substantially slower, and no host operating system is a design-time
first-class platform. All test logic, including official codec/server/client and world interoperability, lives under the
applicable `commonTest`, `jvmTest`, or other standard test source set and is launched by `allTests` or the platform's
standard test task. Do not add a root `test`, custom layer-test, or interoperability-test task.

Production tasks and standard tests keep deterministic downloads, generated runtimes, reports, worlds, and test
artifacts under `build/`. Checked-in target evidence belongs under `protocol-specification/`. Agent-only notes, manual
decompilation, and other scratch belong under `temp/`; `temp/` is exceptional scratch, not a development pipeline. If
manual investigation requires a decompiler that is not installed, tell the user what is missing instead of adding a
Gradle decompilation task or installing it silently. Preserve `.gitignore`.

## Optional agent skills

`.agents/skills` contains optional playbooks that help an agent perform release-wide protocol and storage work. They may
invoke the same Gradle commands a human would invoke, but they are not project inputs:

- project source, Gradle logic, tests, and runtime code must never read skill files or skill-generated scratch;
- Gradle tasks and their helper scripts must never read from or write to `temp/`;
- removing `.agents/skills` must not affect compilation, tests, publication, or runtime behavior.

Use the narrowest applicable skill for an update or exhaustive audit. Ordinary development remains fully defined by the
source tree, Gradle tasks, specification state, and README.
