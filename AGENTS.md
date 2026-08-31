# Agent development guide

This file contains repository-wide rules for coding agents. The root [README.md](README.md) is the human-facing project
guide. Before changing a directory, read its nearest `AGENTS.md`; a nested guide may add local ownership, invariants,
and verification steps, but must not repeat this file.

## Authority and design goals

- Treat checked-in source, build scripts, generated-source wiring, and tests as the authority for the current project.
  Keep documentation and agent guidance aligned with them.
- This is an early-stage project. Prefer a coherent design over compatibility shims, deprecated aliases, transitional
  paths, or preserving an accidental module boundary.
- Keep the high-level vanilla client and server paths zero-configuration beyond facts the application must supply, such
  as a selector, remote address, or player identity. Connection definitions, negotiation profiles, protocol data, and
  vanilla data-pack registry projectors have release-matched defaults; mods remain explicit overrides or extensions.
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
| `protocol-datapack`         | Data-pack resolution, Configuration projection, and protocol/world Chunk adapters       |
| `protocol-datapack-vanilla` | Generated defaults for the repository-selected official release                         |
| `protocol-transport`        | Ktor sockets, framing, compression envelopes, and stream encryption                     |
| `protocol-session`          | Typed packet dispatch, direction, state transitions, and loader negotiation profiles    |
| `account-auth`              | Launcher-side Microsoft, Xbox, and Minecraft Services HTTP APIs                         |
| `protocol-auth`             | Game identities, Session/Services HTTP APIs, Login cryptography, and chat signing       |
| `protocol-client`           | Client orchestration through entry into Play plus received world projections            |
| `protocol-server`           | Server orchestration through entry into Play plus finite initial-view projection        |
| `world-format`              | Filesystem-independent world schemas, data packs, Anvil containers, and semantic chunks |
| `world-io`                  | Okio paths, world leases, files, and filesystem-backed stores                           |

Use the representation-stage names consistently across module boundaries: `DataPackArchive` is raw file bytes,
`DataPack` is parsed content, `DataPackStack`/`ResolvedDataPackStack` are priority views, `ResolvedProtocolData` is the
server-side Configuration projection, `DataPackConfigurationSnapshot` is the client-visible capture, and
`ClientRegistryView` is its resolved lookup view. Variables use the corresponding lower-camel name where the full type
name remains readable.

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
- Put a wire-visible logical value in `protocol-model` whenever both endpoints produce or consume that same value.
  Packet direction does not make the value private to `protocol-client` or `protocol-server`; those modules own
  orchestration and endpoint policy around the shared model.
- Exposing a lower-layer type is correct when it is the natural contract. Do not create wrappers solely to conceal a
  valid downward dependency.
- Enforce intrinsic protocol, format, and representation bounds in their owning layer. Do not add shared policy-sized
  byte, collection, nesting, allocation, decompressed-output, or file-count limits to low-level formats and stores;
  callers own those policies and use the available streaming or inspection paths to enforce them.
- Keep optional `compileOnly` adapters inert unless explicitly called, document the dependency supplied by the caller,
  and test both the direct and adapter paths.

## Kotlin Multiplatform implementation

- Use imports instead of fully qualified names in Kotlin declarations and expressions. For a nested type, import its
  enclosing top-level type and refer to the nested type through that owner; do not import the nested type directly. If
  imported names conflict, resolve the conflict with import aliases rather than fully qualified names.
- By default, name variables, properties, and parameters after their full visible nominal type in lower camel case; for
  example, use `regionPosition: RegionPosition` and `localPosition: LocalPosition`. Use another name only when it
  communicates a distinct domain role, avoids a naming conflict, or satisfies another specific semantic requirement.
  Preserve role-based names for meaningful distinctions such as `client`/`server`, `input`/`output`, lifecycle state,
  external overrides, and official wire or serialized schema fields.
- A single-parameter lambda may use Kotlin's implicit `it`. When declaring a lambda parameter explicitly, apply the same
  type-derived lower-camel naming rule unless one of the exceptions above applies; for example, use
  `regionPositions.map { regionPosition -> ... }`.
- Prefer a `data class` for a type whose primary responsibility is carrying data. Use an ordinary class when generated
  `copy`, component, or equality behavior cannot represent the contract, notably for constructor normalization, lazy
  state, or snapshot ownership. Add explicit `equals` and `hashCode` to an ordinary data-bearing class only when its
  public value contract or an actual use requires equality. A data class whose property has reference-identity equality
  but represents content in that model, especially a Kotlin array, must override `equals` and `hashCode` with the
  matching content operations; apply the same rule to array components in a Java record. `List`, `Map`, and `Set`
  already have structural equality and need no redundant override; strategy, callback, serializer, and similar behavior
  objects retain identity equality when no content equality exists.
- Generated declarations follow the same data-class and equality rules. Fix the owning generator rather than editing its
  output when a generated type violates them.
- Read-only data types retain caller-supplied `List`, `Map`, and `Set` instances by default. Do not defensively copy a
  collection solely because its runtime implementation might be mutable; the caller owns that choice and must keep
  inputs stable when the value builds derived indexes. Copy only when the implementation will mutate or clear the input,
  the contract explicitly creates a detached snapshot, an array-backed value needs stable content equality, or the API
  must convert to a different representation.
- Keep implementation helpers internal or private, but do not restrict a data type or constructor merely to force use of
  a factory. Restrict construction when direct use could bypass an invariant, break resource or lifecycle ownership, or
  expose an implementation-only type.
- Prefer maintained Kotlin, Kotlinx, Ktor, Gradle, and other multiplatform APIs to project-specific helpers or `expect`/
  `actual`. When a platform boundary is unavoidable, expose the smallest reusable primitive.
- Keep shared models free of buffers and I/O. Physical stream formats use `kotlinx.io.Source` and `Sink`;
  filesystem-backed code in `world-io` uses Okio `Path`, `FileSystem`, and `FileHandle`.
- Never let broad `catch`, `runCatching`, or another `Result` helper convert `CancellationException` into an ordinary
  failure. Complete mandatory rollback in `NonCancellable`, preserve cancellation as the primary failure, and attach
  cleanup failure as suppressed context.
- Use maintained format-aware libraries for JSON, XML, form data, and other structured formats. Never assemble or escape
  structured data with string concatenation or templates. JSON uses `kotlinx.serialization.json`.
- When a reified serialization overload mirrors an explicit strategy overload, preserve the shared parameter order and
  append the `SerializationStrategy` or `DeserializationStrategy`. Resolve the reified serializer from the executing
  format's `serializersModule`; use top-level `serializer<T>()` only when no format/module context exists.
- Give built-in typed conveniences, explicit-strategy overloads, and reified serialization overloads the same Kotlin
  operation name. Use `@JvmName` to resolve an erased JVM signature clash instead of adding an `As` suffix or a dummy
  public parameter; reserve representation suffixes such as `Document` and `Json` for genuinely different value forms.
- Validate KMP overload symmetry by inspecting source pairs and compiling ordinary Kotlin calls on every affected
  backend. Do not make JVM reflection or erased bytecode signatures the test contract for a multiplatform source API.
- Public library HTTP APIs and online-login flows borrow a caller-configured Ktor `HttpClient`. Do not create or
  configure an engine, install plugins on the caller's behalf, close the client, or add implicit retry, cache, or token
  policy.
- Every source generator uses a language-aware library such as KotlinPoet or JavaPoet. Generated declarations are not
  assembled with raw source strings.
- Apply these text-construction rules whenever the resulting value is a `String`:
  - Never compose text with binary `+`, `String.plus`, `String.concat`, or `+=` on a String receiver. Kotlin uses a
    string template; Java uses one literal with `formatted` or another maintained formatting API. This does not prohibit
    numeric, collection, array, byte-sequence, coroutine-context, or other non-text `+` operations, including adding a
    String element to a collection.
  - Keep constant text in one literal. Keep one logical single-line value in one ordinary quoted literal or template and
    on one source line, even when it exceeds the 120-column guideline; never split it merely to wrap source. Keep
    program-authored exception, assertion, validation, and log prose on one logical line; aggregate uniformly generated
    items with a single-line separator.
  - A diagnostic may remain multiline when it embeds verbatim evidence whose line boundaries are part of that evidence,
    such as bounded process output, compiler output, or a wire/file dump. Preserve those original line breaks and
    introduce the evidence clearly; do not flatten it merely to satisfy the single-line prose rule.
  - Use a triple-quoted raw string for genuinely multiline literal content whose authored line boundaries are part of
    the value's contract, such as a file, protocol payload, source/data fixture, generated documentation, or a
    diagnostic with structural labels around verbatim multiline evidence. A one-line diagnostic introduction followed by
    such evidence may instead use an ordinary template with one explicit `\n`; the evidence keeps its own line breaks. A
    single-line raw string remains appropriate when verbatim escaping materially improves readability, such as for a
    regular expression. Do not use either form to disguise concatenation or source wrapping.
  - Use a mutable text accumulator such as `StringBuilder`, `StringBuffer`, or `buildString` only for genuinely
    incremental construction driven by a loop, streaming input, or multiple non-trivial branches. A collection rendered
    uniformly uses `joinToString` with a template; a simple conditional fragment uses an intermediate value or template
    expression. An accumulator is never a substitute for one literal or template. These rules do not override the
    format-aware-library requirement for structured data above.
- Treat 120 columns as a soft wrapping guideline for ordinary expressions, not a hard source limit. Keep assignments on
  one line when the complete expression fits within that margin. Break after `=` only when the expression is
  intrinsically multiline or would exceed the guideline.
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
- External HTTP API tests use deterministic Ktor mock engines and never live services or credentials.

Fixture preparation and lifecycle details belong in the nearest guides under `buildSrc`, `minecraft-test-support`, and
`minecraft-test-fixture-host`; do not duplicate them here or in consumer modules.

## Documentation

- A README describes the public contract visible in current source. Do not promise planned behavior, infer target
  support, or copy generated release constants.
- README files, AGENTS guides, and project skills refer to release and tool versions through their owning selector or by
  role, such as the repository-selected Minecraft release and matching Java major. Never copy the selector's current
  literal value. This covers `MinecraftTarget`, `BuildVersions`, the Gradle wrapper, the version catalog, and other
  `buildSrc` target declarations. Stable protocol identifiers, data-format revisions, HTTP status codes, and example
  addresses are not release versions and stay explicit when useful.
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
