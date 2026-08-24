# Agent development guide

This file defines repository-wide development rules for coding agents. [README.md](README.md) is the human-facing
project and usage guide. Before changing a module, read that module's nearest `AGENTS.md`; module guidance adds only
local ownership, invariants, and verification requirements.

## Project maturity and compatibility

This is an early-stage project. Complete refactors are allowed, and backward API compatibility is not required. Change
public APIs, module boundaries, implementations, and tests as needed when that produces a cleaner overall result. Prefer
a simple, elegant, and coherent final design over compatibility shims, deprecated aliases, transitional paths, or
preserving existing structure for its own sake.

## Repository architecture

Published runtime code is divided by responsibility:

- `nbt` owns the shared NBT value algebra and logical raw-tag serializer handoff.
- `nbt-serialization` owns physical binary NBT streams, SNBT text streams, and their `kotlinx.serialization` formats.
- `protocol-model` owns format-independent packet payloads, shared values, logical serializers, and wire annotations.
- `protocol-serialization` owns physical Minecraft encodings and the runtime packet registry.
- `protocol-datapack` owns vanilla-neutral data-pack to Configuration projection, constructible protocol-data sets,
  synchronized-registry/dimension resolution, and received Configuration runtime views.
- `protocol-datapack-vanilla` owns generated, version-matched official packs, static registries, block states, and
  Configuration defaults.
- `protocol-transport` owns Ktor sockets, framing, the compression envelope, and encryption.
- `protocol-session` owns typed dispatch, packet direction, and protocol-state transitions.
- `account-auth` owns launcher-driven Microsoft OAuth, Xbox authentication, Minecraft Services access-token,
  entitlement, and Java-profile HTTP calls.
- `protocol-auth` owns offline/online identities; Session Server, profile lookup, profile-key, game-user attribute,
  block-list, Friends, and Presence HTTP calls; hashes; Login key exchange; profile-key credential verification; and
  player chat signatures/chains.
- `protocol-client` and `protocol-server` own connection orchestration through Play. The server can project a finite
  initial chunk/entity view; it does not implement gameplay.
- `world-format` owns selected-release standalone structured world-file models plus filesystem-independent Anvil
  containers, coordinates, compression dispatch, and chunk NBT composition.
- `world-io` owns Okio paths, filesystems, and filesystem-backed stores.

Private development infrastructure has separate boundaries:

- `buildSrc` contains shared Gradle configuration, official-data analysis/extraction tasks, artifact preparation, and
  cacheable
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
  `implementation`; test and generation dependencies remain on their dedicated configurations. An explicitly optional
  extension adapter may instead use `compileOnly` when the module's direct API and execution paths are independent of
  that dependency and callers add it only when using the adapter.
- Public declarations must work for an external consumer with the dependency metadata Gradle publishes. They must not
  rely on an implementation-only type, repository-only generated state, another module's test fixtures, or implicit
  initialization performed by an in-repository application. Optional `compileOnly` extensions are the sole exception:
  keep them inert unless called, document their caller-supplied dependency, and never invoke them from the direct API.
- A higher-level module may use lower-level types directly in its public API when that is the natural contract; do not
  invent wrappers merely to conceal a valid downward dependency. It may expose several lower layers when orchestration
  is its stated responsibility, but lower layers never depend back on it and sibling capabilities are not exposed unless
  the API actually requires them. Split reusable capabilities at the owning boundary instead of adding an aggregator
  dependency.
- Architecture verification includes inspecting public signatures, published dependency scopes, and production runtime
  classpaths, plus an external-consumer smoke test when metadata inspection alone cannot prove standalone use. Passing
  the repository's internal test graph is not sufficient evidence of independent consumption. The established optional
  extension pattern does not by itself require an external-consumer test; verify its direct and adapter paths in the
  owning and downstream module suites.

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
- In coroutine-aware production code, a broad `catch`, `runCatching`, or other `Result` helper must not turn
  `CancellationException` into an ordinary failure. Rethrow cancellation unless a documented boundary owns an
  independent job and intentionally publishes cancellation as its terminal state. Complete required state/resource
  rollback with `NonCancellable` before rethrowing, keep cancellation primary when cleanup also fails, and retain the
  other failure as suppressed context.
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
- Build and serialize all structured data, including formats such as JSON and XML, with the corresponding maintained
  format-aware library and its elements, builders, or serializers. Do not implement format escaping or construct
  structured data with string literals, concatenation, interpolation, or templates. For JSON, use
  `kotlinx.serialization.json`.
- Every source generator uses a language-aware library such as KotlinPoet or JavaPoet. This applies to build logic,
  processors, tasks, scripts, tools, tests, and every generated target language; generated declarations are never
  assembled with string concatenation or templates.
- In Kotlin and Java, do not concatenate strings with `+`; prefer string-template syntax and keep strings on one line
  where practical. If a Kotlin string genuinely spans lines, use a triple-quoted string. For complex assembly, use
  `buildString` in Kotlin and `StringBuilder` in Java.
- In Kotlin and Java, keep an assignment on one line whenever its right-hand side is a complete expression and the
  joined line stays within the 120-column margin; this covers `val`/`var` declarations, named arguments, and property or
  indexed assignments (for example `val a = "1"`). Break after `=` only when the expression itself spans lines or the
  joined line would exceed the margin.
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

## Documentation examples

Every value used by a README code example must have a discoverable origin before its first use. Declare it as a function
parameter or local value, introduce it in an earlier example that the text explicitly continues, or name and describe
its producer in the immediately preceding prose. Do not leave unexplained application placeholders such as
`myData`, `applicationState`, or `customRegistry`. When an extension or receiver lambda uses an unqualified property,
identify the receiver type and the API that produced that receiver before the example.

## Evidence, versioning, and generated code

`MinecraftTarget.MINECRAFT_VERSION` in `buildSrc` selects the Minecraft release for the entire repository and is the
only place where that release is set manually. Documentation refers to it as the repository-selected or matching
official Minecraft release and never copies the constant's literal value. `./gradlew -q minecraftVersion` prints it.
Release versions selected by separate `buildSrc` targets are independent inputs; compatibility with Minecraft does not
make those versions aliases of `MINECRAFT_VERSION`. The official server's `version.json` supplies the protocol number
and other release facts; module build scripts do not duplicate them.

Evidence has this fixed precedence:

1. the matching official server and client JARs: inspect the direction-specific producer and consumer plus shared
   codecs, constructors, access paths, annotations, optionals, and sentinels; when the two sides expose conflicting
   behavior, inspect both and report the conflict;
2. the revision-matched Minecraft Wiki for facts or descriptions the official implementation does not expose;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

Official behavior resolves conflicts. Unresolved nullability remains nullable and carries `@UnknownNullability`.
Deterministic facts belong in Gradle-produced analysis or generated source; semantic decisions belong in source, tests,
and public documentation.

Java policy follows the repository-selected Minecraft release: the project's Java major version is the Java major
required by that release, and `BuildVersions.JAVA_VERSION` fixes this value for the Gradle JVM toolchain and JVM/Android
bytecode target. The Fixture Host launches official processes with `java` from `PATH`, whose major version is the
configured major or newer; minor and patch versions are not pinned or inferred from Mojang metadata.
`JvmProcessArguments` owns the shared build-logic native-access argument; the Fixture Host owns the equivalent
child-process command helper.

The deterministic build pipeline follows these ownership rules:

- Gradle downloads official artifacts under `build/`, keyed by their exact selected versions or release coordinates.
  Download completion and HTTP failures are handled by the HTTP client; build tasks do not add content-digest or
  expected-size validation. A Mojang asset hash remains only when it is the upstream content-addressed path component.
- Root official-data tasks are the only build tasks that inspect the official server JAR. Each producer owns a
  non-overlapping directory below `build/generated/official-minecraft/<version>/` and exposes a precise consumable
  artifact for target, report, Configuration, or extracted data-pack content. `generateOfficialMinecraftServerTemplate`
  may execute, but never inspect, that JAR solely to produce the stopped default fixture template.
- KSP handles source-derived generation. Cacheable `buildSrc` task types handle generation from non-source files, and
  only the module owning an output registers its generator.
- Data-driven generators consume declared official artifacts, never the official JAR.
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
delays, sleeps, arbitrary timeouts, or probabilistic interleavings. Cancellation/concurrency tests first observe the
exact admission, wait, commit, or cleanup point, then cancel and explicitly release the next gate; an unchecked
`isCompleted` observation alone is not ordering evidence. Ktor selector loops use `Dispatchers.Default` because Native
selectors perform blocking OS waits and cannot run on the virtual test dispatcher.

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

Everything below `.agents` is optional, agent-only guidance for reproducing development work a human can perform from
the repository's source, module guides, existing Gradle tasks, and tests. It is never a project input, source of release
facts, required tool, verification gate, or alternative build pipeline. Current source and build wiring are
authoritative: when a skill disagrees with them, correct or remove the skill; never change the project merely to satisfy
agent guidance. Removing `.agents` does not affect compilation, tests, publication, runtime behavior, or a human's
ability to maintain the project.

`.agents/skills` contains narrowly triggered playbooks for handwritten Minecraft-dependent protocol and world-storage
work plus one release-update coordinator. No project skill body is unconditionally loaded for every task. The release
coordinator routes only the affected domain skills, while ordinary work uses the narrowest matching skill.
