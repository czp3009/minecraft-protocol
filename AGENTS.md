# Agent development guide

This file defines repository-wide development rules for coding agents. [README.md](README.md) is the human-facing
project and usage guide. Before changing a module, read that module's nearest `AGENTS.md`; module guidance adds only
local ownership, invariants, and verification requirements.

## Repository architecture

Published runtime code is divided by responsibility:

- `nbt` owns the shared NBT value algebra and logical raw-tag serializer handoff.
- `nbt-serialization` owns physical binary NBT streams and the NBT `kotlinx.serialization` format.
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
- `world-io` owns Okio paths, filesystems, and filesystem-backed stores.

Private development infrastructure has separate boundaries:

- `buildSrc` contains shared Gradle configuration, official-analysis tasks, artifact preparation, and cacheable
  generators driven by non-source inputs.
- `protocol-symbol-processor` contains the KSP processor for source-derived packet and data-component dispatch.
- `minecraft-test-support` contains only the KMP kRPC service, test-process client, serializable remote-resource values,
  structured helpers, and the explicitly documented Fixture Host working-directory backdoor used by same-host filesystem
  interoperability tests.
- `minecraft-test-fixture-host` contains the JVM process and host-filesystem implementation managed by a Gradle Build
  Service. Consuming test runtime classpaths never include this module.

Physical byte encoding does not belong in models, network I/O does not belong in serialization, and filesystem behavior
does not belong in `world-format`. Published runtime modules contain reusable APIs and generated runtime source only;
generators, launchers, process scaffolding, and test entry points stay in their owning development layer.

Gameplay, authoritative ticking worlds, persistence policy, permissions, and a general Minecraft server are outside the
library's scope.

### Published-module consumption

Every published runtime module is an independently consumable library entry point. A consumer may select only that
module and the lower-level modules required by its documented API; using a module must not require the repository's
complete runtime stack or bring unrelated protocol, world, client/server, generator, fixture, or test infrastructure
onto the consumer classpath.

- Keep the published project graph acyclic and directed from orchestration toward narrower capabilities. Do not add
  convenience dependencies that reverse a boundary, create a cycle, or turn a focused module into an implicit bundle.
- Declare a dependency as `api` only when its types are part of the module's public/protected ABI or its documented
  public contract requires consumers to interact with that dependency directly. All other runtime dependencies are
  `implementation`; test and generation dependencies remain on their dedicated configurations.
- Public declarations must work for an external consumer with the dependency metadata Gradle publishes. They must not
  rely on an implementation-only type, repository-only generated state, another module's test fixtures, or implicit
  initialization performed by an in-repository application.
- A higher-level module may use lower-level types directly in its public API when that is the natural contract; do not
  invent wrappers merely to conceal a valid downward dependency. It may expose several lower layers when orchestration
  is its stated responsibility, but lower layers never depend back on it and sibling capabilities are not exposed unless
  the API actually requires them. Split reusable capabilities at the owning boundary instead of adding an aggregator
  dependency.
- Architecture verification includes inspecting public signatures, published dependency scopes, and production runtime
  classpaths, plus an external-consumer smoke test when metadata inspection alone cannot prove standalone use. Passing
  the repository's internal test graph is not sufficient evidence of independent consumption.

## Kotlin Multiplatform implementation

- Search Kotlin, Kotlinx, Ktor, Gradle, and established maintained libraries before adding a project-specific helper,
  abstraction, codec, or platform adapter. Use project infrastructure only where maintained APIs do not provide the
  required semantics.
- Keep shared models free of buffers and I/O. Represent logical variants with Kotlin types, sealed hierarchies, and
  logical serializers; place physical representation in `protocol-serialization`.
- Use `kotlinx.io.Source` and `Sink` for physical stream formats. Real world-file access in `world-io` uses Okio
  `Path`, `FileSystem`, and `FileHandle`; other published runtime modules remain filesystem-independent.
- Use maintained multiplatform APIs before adding `expect`/`actual`. An unavoidable platform boundary exposes the
  smallest reusable primitive, shares identical implementations through standard source sets, and handles optional
  capabilities at the narrowest call site.
- When a maintained library cannot be called directly and integration needs preprocessing, framing, ownership
  protection, failure normalization, or a compatibility workaround, document that reason beside the special logic and
  identify which library still owns the underlying algorithm. Platform internals may differ, but equivalent public calls
  retain the same result semantics and public exception type across targets; messages, causes, and stacks may differ.
- In Gradle `sourceSets` blocks, use generated accessors such as `commonMain`, `jsMain`, and `jvmTest` for default
  source sets. Create a custom source set once with `create(name)` only when a shared capability cannot be expressed by
  the default hierarchy; do not retrieve default source sets through string-based `getByName` or `named` calls. Keep all
  production source-set declarations and dependencies above test source-set configuration.
- Declare every Gradle plugin used by a subproject in the root `build.gradle.kts` plugins block with `apply false`;
  subprojects reference that shared declaration and do not introduce an undeclared plugin version independently.
- Kotlin and Java code that starts a JVM always executes the literal `java` command from `PATH`. Do not inspect
  `java.home`, `JAVA_HOME`, a Gradle Java launcher, or a JDK installation directory to locate the executable. A
  developer machine is required to provide `java` on `PATH`.
- Build and serialize JSON with `kotlinx.serialization.json` elements, builders, or serializers. Do not implement JSON
  escaping or construct protocol/report JSON with large string templates.
- Every source generator uses a language-aware library such as KotlinPoet or JavaPoet. This applies to build logic,
  processors, tasks, scripts, tools, tests, and every generated target language; generated declarations are never
  assembled with string concatenation or templates.
- In Kotlin and Java, do not concatenate strings with `+`; prefer string-template syntax and keep strings on one line
  where practical. If a Kotlin string genuinely spans lines, use a triple-quoted string. For complex assembly, use
  `buildString` in Kotlin and `StringBuilder` in Java.
- Keep simple assignments such as `val a = "1"` on one line whenever practical.
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

`MinecraftTarget.MINECRAFT_VERSION` in `buildSrc` selects the Minecraft release for the entire repository and is the
only place where that release is set manually. Documentation refers to it as the repository-selected or matching
official Minecraft release and never copies the constant's literal value. `./gradlew -q minecraftVersion` prints it.
Release versions selected by separate `buildSrc` targets are independent inputs; compatibility with Minecraft does not
make those versions aliases of `MINECRAFT_VERSION`. The official server's `version.json` supplies the protocol number
and other release facts; module build scripts do not duplicate them.

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
minor and patch versions are not pinned or inferred from Mojang metadata. `JvmProcessArguments` owns the shared
build-logic native-access argument; the Fixture Host owns the equivalent child-process command helper.

The deterministic build pipeline follows these ownership rules:

- Gradle downloads official artifacts under `build/`, keyed by their exact selected versions or release coordinates.
  Download completion and HTTP failures are handled by the HTTP client; build tasks do not add content-digest or
  expected-size validation. A Mojang asset hash remains only when it is the upstream content-addressed path component.
- Root official-analysis tasks are the only build tasks that inspect the official server JAR. Each analyzer owns a
  non-overlapping directory below `build/generated/official-minecraft/<version>/` and exposes a precise consumable
  artifact for target, report, or Configuration data. `generateOfficialMinecraftServerTemplate` may execute, but never
  inspect, that JAR solely to produce the stopped default fixture template.
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
- Reusable external-official-peer scenarios and their annotated entries also enter through `commonTest`. Every annotated
  Fixture Host entry has a `fixturetest` package segment so unsupported standard test tasks can exclude it without
  relying on class names. The four unsupported leaf tasks are exactly `jsBrowserTest`, `wasmJsBrowserTest`,
  `wasmJsD8Test`, and `wasmWasiNodeTest`; they do not receive Fixture Host inputs or service wiring.
- A scenario that dereferences the Fixture Host's absolute path is the exception. `world-io` uses the repository's one
  `hostFilesystemTest` capability source set for the runner and its annotated entry. The JVM, Node, and desktop Native
  standard test source sets depend on it directly; they do not repeat platform-specific entry files. Android host,
  device, simulator, browser, D8, and Wasm/WASI source sets do not depend on it.
- Do not create another custom test source set when standard KMP source sets and package filtering express the
  capability. Never add fake passing implementations or runtime guesses. When a future capability genuinely needs an
  intermediate source set, use one repository-wide capability name rather than module-specific duplicates.

Run portable Web code under Gradle-provisioned Node or D8. Browser execution is not a repository gate. In-memory
protocol state, NBT, compression, Anvil byte-array/stream loading, and chunk composition remain portable. `world-io` and
real socket tests run only where the configured runtime exposes the required filesystem or networking primitive. Do not
add browser-driver infrastructure without an explicit browser-specific requirement.

Official-peer tests use the repository's standard KMP test tasks. Root preparation tasks expose exact immutable fixture
outputs as lazy file providers. The consuming test task's execution action obtains the shared Build Service only after
those producer inputs are ready; explicit fixture-launch tasks, helper CLIs, `dependsOn` lifecycle wiring, and system
properties for resource paths do not belong in this design.

`prepareOfficialMinecraftServer` and `prepareHeadlessClient` are actionless gates over actual cacheable template
producers. Those producers assemble every required version-pinned resource before launch, start the assembled fixture
once, observe real readiness, stop it normally, preserve reusable files and empty directories, remove only the fixed
per-process files recorded in the manifest, and publish an immutable runtime plus template. HeadlessMC launches must not
download resources. The normal build never discovers a latest HMC-Specifics, Fabric Loader, or HeadlessMC release and
does not validate downloaded bytes with hashes or expected sizes.

Fixture callers do not select a workspace policy. An exact default server configuration clones the stopped server
template; any non-default server property or lifecycle option starts from the prepared runtime without the template
world. A headless client's required offline player name does not make it non-default: default optional lifecycle values
clone the stopped client template, while any non-default optional value starts from the assembled client runtime. Every
workspace owns its root and mutable state, and templates are never launched in place. The complete read-only client
Minecraft runtime and official-server library directory use one directory symbolic link when supported, falling back to
private directory trees with per-file hard links or copies. Other immutable runtime files, the HeadlessMC launcher, the
client template's sole mod, and its processed-mod cache use hard links with copy fallback. Generated options and every
other mutable template file use real copies. Cleanup unlinks directory symbolic links without traversing their targets.

The Build Service starts `minecraft-test-fixture-host` lazily. Test code loads only `minecraft-test-support` and calls
the generated kotlinx.rpc service over Ktor WebSocket with JSON payloads. Processes, official fixture paths, logs, and
cleanup remain inside the Fixture Host. Ordinary tests never receive a process object or Host path. The `world-io`
official interoperability scenario is the explicit exception: after synchronously closing the official process, it uses
the documented `hostWorkingDirectory` backdoor to open the Host-owned world in place. This requires the test process and
Fixture Host to share a filesystem namespace. Its single annotated entry lives in `hostFilesystemTest`, which JVM, Node,
and desktop Native test source sets inherit directly. Android host tests inherit portable `commonTest` coverage without
repeating this JVM-hosted official scenario; device, simulator, browser, and Wasm/WASI source sets do not invoke it.
Codec verification returns normally or throws with failure details.

Within one subproject's single platform test task, compatible cases reuse one official process instead of creating a
process per assertion or test method. Express ordered stateful coverage as phases of one shared runner and one thin
annotated entry per supported compilation, acquire the remote resource once inside the scenario, and close it after the
final phase with structured cleanup. Do not move process or socket startup into `BeforeTest`/`BeforeEach` merely to
exclude its cost from the test timeout; startup remains in the test whose behavior requires that fixture. Class-scoped
or global reuse is acceptable only when the process is genuinely suite-scoped shared state, cleanup is deterministic,
and the cases do not require a fresh workspace, a different fixed endpoint, process exit, or another incompatible state
transition. Its normal after-all/final phase closes the resource explicitly; task-owner cleanup at test-task completion
handles aborted tests, and Build Service shutdown is only the final fallback. Do not rely on unspecified test-method
order. The shared Build Service does not by itself justify pooling mutable fixture processes across separate platform
test tasks; keep those lifetimes isolated unless an explicit cross-platform design proves state isolation without
substantial coordination complexity.

Coroutine tests use `runTest`, not `runBlocking` or `Dispatchers.IO`. `runTest` uses virtual time, so socket and process
tests establish ordering with `await`, `join`, channels, `CompletableDeferred`, or observed readiness events rather than
delays, sleeps, arbitrary timeouts, or probabilistic interleavings. Ktor selector loops use `Dispatchers.Default`
because Native selectors perform blocking OS waits and cannot run on the virtual test dispatcher.

## Development and verification

Inspect existing source, build wiring, generated state, and tests before editing. Preserve unrelated changes and modify
the owning layer only.

Never run Gradle wrapper command lines concurrently. Concurrent Gradle invocations can write the same files and race;
finish one invocation before starting the next.

Use the platform-native wrapper. On Unix-like systems, start with the affected JVM suite:

```shell
./gradlew :affected-module:jvmTest
```

The pure JVM `minecraft-test-fixture-host` module uses `:minecraft-test-fixture-host:test`; KMP modules use `jvmTest`.
Use `./gradlew :minecraft-test-fixture-host:test jvmTest` for a repository-wide JVM pass. After the JVM path is stable,
use the applicable standard platform task or `./gradlew allTests`. Native compilation is not the first feedback loop,
and no host operating system is a design-time first-class platform. The root project does not define a replacement
`test`, layer-test, or interoperability-test task.

When the current machine has insufficient available memory, limit every Gradle invocation to an appropriate worker count
with `--max-workers=<count>`.

Use `clean` or `--rerun-tasks` when forced verification is necessary, but keep the build cache enabled. Configuration
cache and unchanged-rerun checks accompany changes to task inputs, outputs, or service wiring.

Production task outputs stay under `build/`. Fixture Host runtimes, worlds, and scratch also stay there while in use and
are deleted when their owning resource or host closes; successful tests do not leave standalone result files. Process
logs remain Host-owned and cross the RPC boundary as values only when requested. A test client that exercises its own
filesystem uses a self-owned system temporary directory and removes it after the scenario. Agent-only notes, manual
decompilation, and third-party reference checkouts belong under `temp/`; Gradle and its helper scripts never read or
write that directory. Agents may use IDEA MCP to view decompiled code. Preserve `.gitignore`.

## Optional agent skills

`.agents/skills` contains optional playbooks for release-wide protocol and storage work. A skill coordinates the same
source, Gradle tasks, and verification path available to a human; it is never a project input or an alternative build
pipeline. Removing `.agents/skills` does not affect compilation, tests, publication, or runtime behavior. Use the
narrowest applicable skill for an update or exhaustive audit.
