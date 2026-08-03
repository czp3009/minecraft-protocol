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
- `minecraft-test-support`: private Kotlin Multiplatform library for reusable official-artifact and external-process
  fixtures used by standard tests on JVM, desktop Native, and Node runtimes.
- `buildSrc`: shared Gradle configuration, official-analysis tasks, and self-validating generators whose inputs are not
  Kotlin source.

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
evidence. Resolve conflicts in favor of official behavior. Deterministic official-analysis data stays under
`build/generated/official-minecraft/<version>/`; semantic decisions belong in source, tests, and public documentation.

For nullability, inspect official codecs, constructors, access paths, annotations, optionals, and sentinels first. Fall
back through Wiki, MCProtocolLib, and Minestom only when the preceding evidence is inconclusive. Keep unresolved values
nullable and annotate them with `@UnknownNullability`.

Write idiomatic Kotlin Multiplatform code:

- before implementing a helper, abstraction, codec, or platform adapter, search the Kotlin standard library and the
  relevant Kotlinx, Ktor, Gradle, and Kotlin Multiplatform APIs, then established actively maintained libraries. Reuse
  those facilities whenever they express the required behavior; write project-specific infrastructure only when no
  suitable maintained facility exists or the required semantics materially differ;
- keep shared models free of buffers and I/O;
- represent logical variants with sealed types and logical serializers;
- put physical representation in `protocol-serialization`;
- use `kotlinx.io.Source`/`Sink` and, where supported, `kotlinx.io.files.FileSystem`;
- before adding `expect`/`actual` glue or platform APIs, look for an existing portable Kotlinx, Ktor, or other
  maintained multiplatform library that provides the capability;
- when `expect`/`actual` is unavoidable, expose the smallest reusable platform primitive (for example, reading one
  environment variable) instead of moving a higher-level abstraction into platform source sets. Prefer Kotlin's standard
  platform source sets, share identical implementations, and handle an engine's missing optional capability with a
  narrow call-site branch or omission;
- construct and serialize JSON with `kotlinx.serialization.json` elements, builders, or serializers. Do not implement
  JSON escaping or generate JSON reports and protocol components with large string templates;
- generate Kotlin source with KotlinPoet in both KSP processors and Gradle generators. Do not hand-build source text,
  escaping, imports, declarations, or control flow with string concatenation or templates;
- when literal multiline text is genuinely required, use a triple-quoted string instead of concatenating quoted lines;
- treat externally consumable declarations as library API even when this repository has no internal caller; do not add
  `unused` suppressions solely to silence that expected condition;
- omit redundant `public`, and keep implementation helpers internal or private.

Ordinary Kotlin code that needs logging uses kotlin-logging. Gradle task code uses Gradle's logger and KSP processors
use `KSPLogger`; do not route those environments through kotlin-logging. Prefer structured test reports and assertion
messages over success `println` calls.

Match tests to actual platform capabilities. Exercise portable Web code under the Gradle-provisioned Node/D8 runtimes;
browser-runtime tests are not a repository gate. Keep in-memory protocol state, NBT, compression, Anvil
`ByteArray`/`Source` loading, and chunk composition portable, but do not invent browser filesystem or listening-server
support. Run `world-io` and production socket tests only on targets that expose the required filesystem or networking
primitives. Do not add browser-driver infrastructure unless a task explicitly requires browser-specific behavior.

## Version and deterministic generation

`MinecraftTarget.MINECRAFT_VERSION` in `buildSrc` is the only manually selected Minecraft version. `./gradlew -q
minecraftVersion` prints it. Do not duplicate it in module build scripts. The official server JAR's `version.json`
supplies the protocol number and other version facts.

Java policy is independent of the selected Minecraft release. Each owning module's explicit platform configuration uses
`BuildVersions` in `buildSrc` to fix the Gradle JVM toolchain and JVM/Android bytecode target at Java 25; this uniform
baseline does not mean the Kotlin sources intrinsically require Java 25-only APIs. Tests that launch official Minecraft
processes use the `java` command on `PATH`, whose major version may be 25 or newer; never require an exact minor or
patch release. Do not infer or change the project toolchain from Mojang metadata.

Automate everything that can be derived exactly:

- Gradle downloads and verifies official artifacts under `build/`, keyed by `MinecraftTarget.MINECRAFT_VERSION`;
- HTTP artifact acquisition uses the Ktor client with its timeout and retry plugins and streams large responses through
  `kotlinx-io`; do not add `java.net` downloaders, thread sleeps, or hand-written retry schedulers;
- root official-analysis tasks are the only build tasks that inspect or execute the official server JAR. Each owns a
  non-overlapping directory under `build/generated/official-minecraft/<version>/`, and the root exposes precise
  consumable Gradle artifacts for `target`, `reports`, and `configuration` data;
- cacheable task types in `buildSrc` generate source solely from those analysis files, and only the module owning an
  output registers and wires the corresponding task;
- the private KSP processor generates source-derived packet definitions and data-component dispatch from annotations;
- the root Configuration analysis captures both official Known Packs branches as complete JSON data; the owning
  `protocol-vanilla-data` generator renders Kotlin from that JSON without reading the JAR;
- published source JARs include generated Kotlin. Generated Kotlin uses KSP's standard output or the owning module's
  `build/generated/sources/<generator>/<source-set>/kotlin` directory.

Compilation and tests may depend on these cacheable tasks and network access on the first run. There is no checked-in
target evidence, copy/synchronization workflow, or manual freshness comparison; Gradle decides reuse from declared
inputs, outputs, task implementation, and producer provenance.

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
  sets; do not add Gradle preparation tasks, command-line helpers, or system-property wiring for test fixtures;
- test resources share only verified immutable downloads. Every running server/client owns a unique work directory,
  directly launched process, logs, and internally selected endpoint; bound-port failures retry before returning a ready
  resource. Prefer an external launcher's supported in-process mode over spawning an opaque child process; each resource
  must retain and idempotently close every process it directly launches.

## Development and verification

Inspect existing code and official-analysis state before editing. Preserve unrelated user changes. Prefer focused JVM
tests while iterating:

```shell
./gradlew :affected-module:jvmTest
```

Use Gradle's standard task selector for a repository-wide JVM pass when needed:

```shell
./gradlew jvmTest
```

After the JVM path is stable, `./gradlew allTests` selects every module's standard KMP aggregate. Do not use Native
compilation as the first feedback loop; it is substantially slower, and no host operating system is a design-time
first-class platform. Put each portable test entry, scenario, and assertion in `commonTest`; keep only an unavoidable
platform resource/process adapter in `jvmTest`, `nativeTest`, or another platform source set. An official JAR running as
an external peer does not by itself make the protocol scenario JVM-specific. Do not add fake passing implementations on
unsupported targets. All tests are launched by `allTests` or the platform's standard test task; do not add a root
`test`, custom layer-test, or interoperability-test task.

Use `clean` or `--rerun-tasks` when a full or forced Gradle verification is useful, but keep the build cache enabled. Do
not use `--no-build-cache`; deterministic tasks must continue to exercise Gradle's normal cache behavior.

Coroutine tests use `runTest`, never `runBlocking` or `Dispatchers.IO`. Remember that `runTest` uses virtual time: do
not use `delay`, sleeps, arbitrary timeouts, or dispatcher switches to make local socket and concurrent tests pass. Real
Ktor selector loops are resource executors, not test schedulers: construct them with `Dispatchers.Default` because
Native selectors perform blocking OS waits and must never run on `runTest`'s virtual dispatcher. Establish every
critical ordering edge explicitly with coroutine primitives such as `await`, `join`, channels, or
`CompletableDeferred`, or with an observed protocol/process readiness event. Concurrency tests must not depend on a
probabilistic interleaving. Express unavailable platform capabilities through KMP source-set boundaries or
`expect`/`actual`, rather than runtime guesses or fake successful tests.

Production tasks and standard tests keep deterministic downloads, generated data and sources, runtimes, reports, worlds,
and test artifacts under `build/`. Agent-only notes, manual decompilation, and other scratch belong under
`temp/`; `temp/` is exceptional scratch, not a development pipeline. If
manual investigation requires a decompiler that is not installed, tell the user what is missing instead of adding a
Gradle decompilation task or installing it silently. Preserve `.gitignore`.

## Optional agent skills

`.agents/skills` contains optional playbooks that help an agent perform release-wide protocol and storage work. They may
invoke the same Gradle commands a human would invoke, but they are not project inputs:

- project source, Gradle logic, tests, and runtime code must never read skill files or skill-generated scratch;
- Gradle tasks and their helper scripts must never read from or write to `temp/`;
- removing `.agents/skills` must not affect compilation, tests, publication, or runtime behavior.

Use the narrowest applicable skill for an update or exhaustive audit. Ordinary development remains fully defined by the
source tree, Gradle tasks, generated official-analysis state, and README.
