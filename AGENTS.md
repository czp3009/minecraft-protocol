# Agent development guide

This file defines repository-wide development rules for coding agents. [README.md](README.md) is the human-facing
project and usage guide. Before changing a module, read that module's nearest `AGENTS.md`; module guidance adds only
local ownership, invariants, and verification requirements.

## Repository architecture

Published runtime code is divided by responsibility:

- `compression` owns portable raw DEFLATE.
- `nbt` owns binary NBT streams; the shared NBT value algebra remains in `protocol-model`.
- `protocol-model` owns format-independent packet payloads, shared values, logical serializers, and wire annotations.
- `protocol-serialization` owns physical Minecraft encodings and the runtime packet registry.
- `protocol-vanilla-data` owns generated, version-matched protocol data.
- `protocol-transport` owns Ktor sockets, framing, the compression envelope, and encryption.
- `protocol-session` owns typed dispatch, packet direction, and protocol-state transitions.
- `protocol-auth` owns offline identities, session-service calls, hashes, and cryptographic abstractions.
- `protocol-client` and `protocol-server` own connection orchestration through Play. The server can project a finite
  initial chunk/entity view; it does not implement gameplay.
- `world-format` owns filesystem-independent Anvil containers, coordinates, compression dispatch, and chunk NBT
  composition.
- `world-io` owns `kotlinx.io.files` paths and filesystem-backed stores.

Private development infrastructure has separate boundaries:

- `buildSrc` contains shared Gradle configuration, official-analysis tasks, artifact preparation, and cacheable
  generators driven by non-source inputs.
- `protocol-symbol-processor` contains the KSP processor for source-derived packet and data-component dispatch.
- `minecraft-test-support` contains only the KMP kRPC service, test-process client, serializable remote-resource values,
  and structured helpers used by official-peer tests.
- `minecraft-test-fixture-host` contains the JVM process and host-filesystem implementation managed by a Gradle Build
  Service. Consuming test runtime classpaths never include this module.

Physical byte encoding does not belong in models, network I/O does not belong in serialization, and filesystem behavior
does not belong in `world-format`. Published runtime modules contain reusable APIs and generated runtime source only;
generators, launchers, process scaffolding, and test entry points stay in their owning development layer.

Gameplay, authoritative ticking worlds, persistence policy, permissions, and a general Minecraft server are outside the
library's scope.

## Kotlin Multiplatform implementation

- Search Kotlin, Kotlinx, Ktor, Gradle, and established maintained libraries before adding a project-specific helper,
  abstraction, codec, or platform adapter. Use project infrastructure only where maintained APIs do not provide the
  required semantics.
- Keep shared models free of buffers and I/O. Represent logical variants with Kotlin types, sealed hierarchies, and
  logical serializers; place physical representation in `protocol-serialization`.
- Use `kotlinx.io.Source` and `Sink`, and use `kotlinx.io.files.FileSystem` only on supported filesystem targets.
- Use maintained multiplatform APIs before adding `expect`/`actual`. An unavoidable platform boundary exposes the
  smallest reusable primitive, shares identical implementations through standard source sets, and handles optional
  capabilities at the narrowest call site.
- Build and serialize JSON with `kotlinx.serialization.json` elements, builders, or serializers. Do not implement JSON
  escaping or construct protocol/report JSON with large string templates.
- Every source generator uses a language-aware library such as KotlinPoet or JavaPoet. This applies to build logic,
  processors, tasks, scripts, tools, tests, and every generated target language; generated declarations are never
  assembled with string concatenation or templates.
- Keep string literals on one line where practical. Use a triple-quoted string when a literal genuinely spans lines.
- Treat externally consumable declarations as API even without an in-repository caller. Do not suppress `unused` for
  that reason. Omit redundant `public`; keep implementation helpers internal or private.
- Ordinary logs never write directly to the console. Do not use `print`, `println`, `System.out`, `System.err`,
  `printStackTrace`, or equivalent direct console writes as a logging mechanism. Ordinary Kotlin code uses
  kotlin-logging, Gradle build logic uses Gradle's logger, KSP processors use `KSPLogger`, and code hosted by another
  framework uses that framework's logging API. Tests use assertions; a successful execution does not emit a standalone
  success log or report, while failure details travel through the test framework's exception and reporting path. A
  direct standard-stream write is permitted only when the bytes are an explicit machine or subprocess protocol, or when
  a test fixture specifically exercises standard-stream behavior; isolate that write at the protocol boundary and never
  mix diagnostic logs into the protocol stream. Agent-only infrastructure such as `.codex` hooks follows its host
  protocol and is outside this project-code logging rule.

## Evidence, versioning, and generated code

`MinecraftTarget.MINECRAFT_VERSION` in `buildSrc` is the only manually selected Minecraft release.
`./gradlew -q minecraftVersion` prints it. The official server's `version.json` supplies the protocol number and other
release facts; module build scripts do not duplicate them.

Evidence has this fixed precedence:

1. the matching official server JAR, its codecs, constructors, access paths, annotations, optionals, and sentinels;
2. the revision-matched Minecraft Wiki for facts or descriptions the official implementation does not expose;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

Official behavior resolves conflicts. Unresolved nullability remains nullable and carries `@UnknownNullability`.
Deterministic facts belong in Gradle-produced analysis or generated source; semantic decisions belong in source, tests,
and public documentation.

Java policy is independent of Minecraft. `BuildVersions` fixes the Gradle JVM toolchain and JVM/Android bytecode target
at Java 25. The Fixture Host launches official processes with `java` from `PATH`, whose major version is 25 or newer;
minor and patch versions are not pinned or inferred from Mojang metadata.

The deterministic build pipeline follows these ownership rules:

- Gradle downloads and verifies official artifacts under `build/`, keyed by the selected Minecraft release.
- Root official-analysis tasks are the only build tasks that inspect or execute the official server JAR. Each analyzer
  owns a non-overlapping directory below `build/generated/official-minecraft/<version>/` and exposes a precise
  consumable artifact for target, report, or Configuration data.
- KSP handles source-derived generation. Cacheable `buildSrc` task types handle generation from non-source files, and
  only the module owning an output registers its generator.
- Data-driven generators consume declared analysis artifacts, never the official JAR.
- Generated Kotlin uses KSP's standard output or the owning module's
  `build/generated/sources/<generator>/<source-set>/kotlin` directory. Published source JARs include generated Kotlin.

Read [`buildSrc/AGENTS.md`](buildSrc/AGENTS.md) before changing downloads, analysis, generators, shared KMP
configuration, or Fixture Host service wiring.

Do not commit generated Kotlin or target evidence, hand-transcribe deterministic data, add refresh/copy/freshness
comparison workflows, or regenerate output solely to compare it. Gradle decides reuse from declared inputs, outputs,
task implementations, and producer provenance. Gradle tasks do not perform human-oriented decompilation, download
Wiki/third-party source trees, or invoke agent workflows.

## Test architecture

Use source sets according to capability:

- Portable ordinary test entries, scenarios, and assertions belong in `commonTest`.
- External-official-peer scenarios also enter through `commonTest`. Standard platform test source sets contain only an
  unavoidable replaceable transport, environment primitive, oracle, or capability-specific test.
- Do not create a custom test source set when standard KMP source sets express the capability. Unsupported network or
  filesystem scenarios are excluded at the standard test-task boundary; never add fake passing implementations or
  runtime guesses.

Run portable Web code under Gradle-provisioned Node or D8. Browser execution is not a repository gate. In-memory
protocol state, NBT, compression, Anvil byte-array/stream loading, and chunk composition remain portable. `world-io` and
real socket tests run only where the configured runtime exposes the required filesystem or networking primitive. Do not
add browser-driver infrastructure without an explicit browser-specific requirement.

Official-peer tests use the repository's standard KMP test tasks. Root preparation tasks expose exact immutable fixture
outputs as lazy file providers. The consuming test task's execution action obtains the shared Build Service only after
those producer inputs are ready; explicit fixture-launch tasks, helper CLIs, `dependsOn` lifecycle wiring, and system
properties for resource paths do not belong in this design.

The Build Service starts `minecraft-test-fixture-host` lazily. Test code loads only `minecraft-test-support` and calls
the generated kotlinx.rpc service over Ktor WebSocket with JSON payloads. Processes, official fixture paths, host
workspaces, logs, and cleanup remain inside the Fixture Host. Tests never receive a host workspace path or process
object; codec verification returns normally or throws with failure details, and `world-io` receives file contents
through RPC and uses a test-owned local scratch directory.

Within one subproject's single platform test task, compatible cases reuse one official process instead of creating a
process per assertion or test method. Express ordered stateful coverage as phases of one `commonTest` entry and runner,
acquire the remote resource once inside the annotated test scenario, and close it after the final phase with structured
cleanup. Do not move process or socket startup into `BeforeTest`/`BeforeEach` merely to exclude its cost from the test
timeout; startup remains in the test whose behavior requires that fixture. Class-scoped or global reuse is acceptable
only when the process is genuinely suite-scoped shared state, cleanup is deterministic, and the cases do not require a
fresh workspace, a different fixed endpoint, process exit, or another incompatible state transition. Its normal
after-all/final phase closes the resource explicitly; task-owner cleanup at test-task completion handles aborted tests,
and Build Service shutdown is only the final fallback. Do not rely on unspecified test-method order. The shared Build
Service does not by itself justify pooling mutable fixture processes across separate platform test tasks; keep those
lifetimes isolated unless an explicit cross-platform design proves state isolation without substantial coordination
complexity.

Coroutine tests use `runTest`, not `runBlocking` or `Dispatchers.IO`. `runTest` uses virtual time, so socket and process
tests establish ordering with `await`, `join`, channels, `CompletableDeferred`, or observed readiness events rather than
delays, sleeps, arbitrary timeouts, or probabilistic interleavings. Ktor selector loops use `Dispatchers.Default`
because Native selectors perform blocking OS waits and cannot run on the virtual test dispatcher.

## Development and verification

Inspect existing source, build wiring, generated state, and tests before editing. Preserve unrelated changes and modify
the owning layer only.

Use the platform-native wrapper. On Unix-like systems, start with the affected JVM suite:

```shell
./gradlew :affected-module:jvmTest
```

The pure JVM `minecraft-test-fixture-host` module uses `:minecraft-test-fixture-host:test`; KMP modules use `jvmTest`.
Use `./gradlew :minecraft-test-fixture-host:test jvmTest` for a repository-wide JVM pass. After the JVM path is stable,
use the applicable standard platform task or `./gradlew allTests`. Native compilation is not the first feedback loop,
and no host operating system is a design-time first-class platform. The root project does not define a replacement
`test`, layer-test, or interoperability-test task.

Use `clean` or `--rerun-tasks` when forced verification is necessary, but keep the build cache enabled. Configuration
cache and unchanged-rerun checks accompany changes to task inputs, outputs, or service wiring.

Production task outputs stay under `build/`. Fixture Host runtimes, worlds, and scratch also stay there while in use and
are deleted when their owning resource or host closes; successful tests do not leave standalone result files. Process
logs remain Host-owned and cross the RPC boundary as values only when requested. A test client that exercises its own
filesystem uses a self-owned system temporary directory and removes it after the scenario. Agent-only notes, manual
decompilation, and third-party reference checkouts belong under `temp/`; Gradle and its helper scripts never read or
write that directory. A missing decompiler is reported to the user rather than installed silently or added as a Gradle
task. Preserve `.gitignore`.

## Optional agent skills

`.agents/skills` contains optional playbooks for release-wide protocol and storage work. A skill coordinates the same
source, Gradle tasks, and verification path available to a human; it is never a project input or an alternative build
pipeline. Removing `.agents/skills` does not affect compilation, tests, publication, or runtime behavior. Use the
narrowest applicable skill for an update or exhaustive audit.
