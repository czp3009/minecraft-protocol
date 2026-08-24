# Agent development guide

This file contains repository-wide rules for coding agents. The root [README.md](README.md) is the human-facing project
guide. Before changing a directory, read its nearest `AGENTS.md`; a nested guide may add local ownership, invariants,
and verification steps, but must not repeat this file.

## Authority and design goals

- Treat checked-in source, build scripts, generated-source wiring, and tests as the authority for the current project.
  Keep documentation and agent guidance aligned with them.
- This is an early-stage project. Prefer a coherent design over compatibility shims, deprecated aliases, transitional
  paths, or preserving an accidental module boundary.
- Inspect existing code and tests before editing. Preserve unrelated work in a dirty worktree and change the layer that
  owns the behavior.
- Gameplay, authoritative world ticking, permission systems, persistence policy, and a general-purpose Minecraft server
  are outside this library's scope.

## Module boundaries

Runtime libraries are arranged from reusable formats and models toward connection orchestration:

| Module                      | Responsibility                                                                          |
|-----------------------------|-----------------------------------------------------------------------------------------|
| `nbt`                       | Format-independent NBT values and the logical serializer handoff                        |
| `nbt-serialization`         | Binary NBT, SNBT, and their `kotlinx.serialization` formats                             |
| `protocol-model`            | Packet payloads, shared protocol values, logical serializers, and wire annotations      |
| `protocol-serialization`    | Physical packet payload encoding and packet registries                                  |
| `protocol-datapack`         | Vanilla-neutral data-pack resolution and Configuration projection                       |
| `protocol-datapack-vanilla` | Generated defaults for the repository-selected official release                         |
| `protocol-transport`        | Ktor sockets, framing, compression envelopes, and stream encryption                     |
| `protocol-session`          | Typed packet dispatch, direction, state transitions, and loader negotiation profiles    |
| `account-auth`              | Launcher-side Microsoft, Xbox, and Minecraft Services HTTP APIs                         |
| `protocol-auth`             | Game identities, Session/Services HTTP APIs, Login cryptography, and chat signing       |
| `protocol-client`           | Client orchestration through entry into Play plus received world projections            |
| `protocol-server`           | Server orchestration through entry into Play plus finite initial-view projection        |
| `world-format`              | Filesystem-independent world schemas, data packs, Anvil containers, and semantic chunks |
| `world-io`                  | Okio paths, world leases, files, and filesystem-backed stores                           |

Private development infrastructure has separate boundaries:

- `buildSrc` owns shared Gradle configuration, official-artifact preparation and analysis, non-source-input generators,
  and Fixture Host service wiring.
- `protocol-symbol-processor` owns KSP generation derived from source annotations.
- `minecraft-test-support` owns the portable kRPC fixture contract and test-process client.
- `minecraft-test-fixture-host` owns the private JVM host, processes, workspaces, and host filesystem implementation.
- `demo/launcher` is an example application, not a reusable runtime layer.

Keep physical byte encoding out of models, socket and framing behavior out of serialization, filesystem behavior out of
`world-format`, and generators or test launchers out of runtime source sets.

### Dependency and API rules

- Keep the runtime project graph acyclic and directed toward narrower capabilities. Do not add convenience dependencies
  that reverse ownership or turn a focused module into an implicit bundle.
- Use `api` only when dependency types appear in public/protected ABI or are part of the documented caller contract. Use
  `implementation` for internal runtime dependencies and dedicated configurations for tests and generation.
- A public declaration must be usable with the dependency metadata exposed by its module. It must not rely on another
  module's tests, repository-only initialization, or implementation-only types.
- Exposing a lower-layer type is correct when it is the natural contract. Do not create wrappers solely to conceal a
  valid downward dependency.
- Keep optional `compileOnly` adapters inert unless explicitly called, document the dependency supplied by the caller,
  and test both the direct and adapter paths.

## Kotlin Multiplatform implementation

- Prefer maintained Kotlin, Kotlinx, Ktor, Gradle, and other multiplatform APIs to project-specific helpers or `expect`/
  `actual`. When a platform boundary is unavoidable, expose the smallest reusable primitive.
- Keep shared models free of buffers and I/O. Physical stream formats use `kotlinx.io.Source` and `Sink`;
  filesystem-backed code in `world-io` uses Okio `Path`, `FileSystem`, and `FileHandle`.
- Never let broad `catch`, `runCatching`, or another `Result` helper convert `CancellationException` into an ordinary
  failure. Complete mandatory rollback in `NonCancellable`, preserve cancellation as the primary failure, and attach
  cleanup failure as suppressed context.
- Use maintained format-aware libraries for JSON, XML, form data, and other structured formats. Never assemble or escape
  structured data with string concatenation or templates. JSON uses `kotlinx.serialization.json`.
- Every source generator uses a language-aware library such as KotlinPoet or JavaPoet. Generated declarations are not
  assembled with raw source strings.
- In Kotlin and Java, prefer templates over `+` for text composition. Keep a complete single-line string on one source
  line; use triple-quoted strings for real multiline content. Use builders only when loops or non-trivial branching
  require them.
- Keep assignments on one line when the complete expression fits within 120 columns. Break after `=` only when the
  expression itself is multiline or would exceed that margin.
- Omit redundant `public`. Keep helpers internal or private, but do not suppress `unused` merely because an external
  consumer is the only expected caller.
- Production logging uses kotlin-logging, Gradle's logger, `KSPLogger`, or the hosting framework's logger. Direct
  standard-stream writes are reserved for explicit machine/subprocess protocols and tests of those protocols.
- JVM processes launched by project Kotlin or Java code execute the literal `java` command from `PATH`. Do not derive
  the executable from `java.home`, `JAVA_HOME`, Gradle toolchains, or a JDK installation path.

### Gradle source sets and plugins

- Use generated accessors such as `commonMain`, `jvmTest`, and `jsMain` for default source sets. Create a custom source
  set once, only for a real shared capability that the default hierarchy cannot express.
- Keep production source-set declarations and dependencies before test source-set configuration.
- Declare each subproject plugin in the root `build.gradle.kts` plugin block with `apply false`; subprojects use that
  declaration instead of choosing an independent version.
- A module exposes only the targets configured by its build script. Do not infer support from another module or add fake
  implementations for unsupported runtimes.

## Release evidence and generated code

`MinecraftTarget.MINECRAFT_VERSION` in `buildSrc` is the sole manual selector for the repository's Minecraft release.
Documentation calls it the repository-selected or matching official release and does not copy its literal value. Run
`./gradlew -q minecraftVersion` to print it. Other external tool versions in `buildSrc` are independent inputs.

When implementing Minecraft-dependent behavior, use evidence in this order:

1. the matching official server and client implementations, including both producer and consumer;
2. the revision-matched Minecraft Wiki when official code does not expose the fact;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

Official behavior resolves conflicts. Keep unresolved nullability nullable and mark it `@UnknownNullability`. Put
deterministic facts in Gradle-produced analysis or generated source; keep semantic decisions in handwritten source,
tests, and public documentation.

The Java toolchain and bytecode target come from `BuildVersions.JAVA_VERSION`, which follows the selected Minecraft
release. Official child processes use `java` from `PATH` at that major version or newer.

- Root official-data producers alone inspect the official server JAR. Each owns a non-overlapping output below
  `build/generated/official-minecraft/<version>/` and exposes a precise artifact.
- KSP owns source-derived generation. Cacheable `buildSrc` tasks own generation from non-source inputs, and the runtime
  module that owns an output registers it.
- Generators consume declared artifacts, not undeclared files or the official JAR. Generated output remains below
  `build/` and is included in source JARs where configured.
- Do not commit generated Kotlin or analysis output, hand-transcribe deterministic data, add refresh/copy comparison
  workflows, or regenerate output only to compare it.
- Normal builds do not search for the latest external release. Downloads and generated outputs are governed by declared
  Gradle inputs, outputs, and provider relationships.

Read [buildSrc/AGENTS.md](buildSrc/AGENTS.md) before changing artifact preparation, analysis, generation, shared KMP
configuration, or Fixture Host wiring.

## Test architecture

- Put portable scenarios and assertions in `commonTest`. Use platform source sets only for genuine platform
  implementations or oracles.
- External official-peer scenarios also originate in portable test code. Their annotated entries have a `fixturetest`
  package segment so unsupported leaf tasks can exclude them.
- The only host-filesystem capability source set is `world-io`'s `hostFilesystemTest`. Do not create module-specific
  duplicates when a standard source set or package filter is sufficient.
- Use Gradle-provisioned Node or D8 for portable Web code. Browser execution is not a repository gate. Filesystem and
  real-socket tests run only on configured runtimes that provide those capabilities.
- Fixture tests communicate through `minecraft-test-support`; test code never receives process objects or official
  artifact paths. The documented `hostWorkingDirectory` path used by `world-io` is the sole same-filesystem exception.
- Reuse one fixture process within one platform test task only when ordered phases are compatible. Acquire and close it
  inside the scenario; do not hide startup in per-test lifecycle hooks or depend on unspecified test order.
- Coroutine tests use `runTest`, explicit signals, and observed readiness. Do not use `runBlocking`, `Dispatchers.IO`,
  delays, sleeps, arbitrary timeouts, or scheduler luck to prove ordering. Ktor selector loops use `Dispatchers.Default`
  where Native selectors block.

Fixture preparation and lifecycle details belong in the nearest guides under `buildSrc`, `minecraft-test-support`, and
`minecraft-test-fixture-host`; do not duplicate them here or in consumer modules.

## Documentation

- A README describes the public contract visible in current source. Do not promise planned behavior, infer target
  support, or copy generated release constants.
- Every value in an example has a discoverable origin before first use: a parameter, a local declaration, a clearly
  continued earlier example, or an immediately described producer. Identify receiver types for unqualified DSL
  properties.
- Prefer short examples that demonstrate stable entry points. Link to the owning module instead of copying another
  module's full workflow.
- Every Gradle subproject keeps `README.md` and `AGENTS.md` in its project directory. A directory used only to group
  subprojects does not duplicate its child's guides; `buildSrc` keeps both files because it is an independently
  maintained build layer.
- Nested `AGENTS.md` files contain only local rules. If a rule applies repository-wide, move it here; if it is already
  here, remove the nested copy.

## Workflow and verification

Use the platform-native wrapper and never run Gradle wrapper invocations concurrently. Start with the narrowest affected
JVM task:

```shell
./gradlew :affected-module:jvmTest
```

The JVM-only Fixture Host uses `:minecraft-test-fixture-host:test`. A repository-wide JVM pass is:

```shell
./gradlew :minecraft-test-fixture-host:test jvmTest
```

After the JVM path is stable, run the relevant standard platform task or `./gradlew allTests`. Use
`--max-workers=<count>` when memory is constrained. Keep the build cache enabled; changes to task inputs, outputs, or
Build Service wiring also require configuration-cache store and reuse checks.

Production outputs and fixture workspaces stay below `build/`. Agent notes, manual decompilation, and temporary
third-party references stay below `temp/`, which Gradle must not consume. Preserve `.gitignore`.

## Optional agent skills

Everything below `.agents` is optional guidance. It is not a project input, release authority, build pipeline, or
verification gate. If a skill disagrees with source or build wiring, fix or remove the skill rather than changing the
project to satisfy it. Removing `.agents` must not affect compilation, publication, tests, or runtime behavior.
