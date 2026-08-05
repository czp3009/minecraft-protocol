# Template-backed Minecraft fixtures and controlled headless client

## Status and intent

This plan records the decisions already agreed for reducing official server and headless client fixture startup cost
while keeping the Gradle graph, remote fixture boundary, and lifecycle semantics explicit. Treat the decisions below as
the implementation baseline; do not reopen the architecture unless implementation evidence shows that a stated invariant
is unworkable.

The result has two independently prepared fixtures:

- an official Minecraft server artifact plus a sanitized, normally stopped default-world template;
- a controlled headless client assembled from Mojang client artifacts, Fabric Loader, a private fixture-control mod,
  HeadlessMC, libraries, and filtered assets, plus a sanitized, normally stopped client game-directory template.

Templates are immutable Gradle-produced inputs. Every launched process receives a private copy. The design does not pool
mutable, already-running ordinary server or client processes across test-task owners.

The rejected alternative is two always-running “ordinary” processes owned by the Build Service. It avoids JVM startup
only when tests can safely share all mutable in-memory and persistent state, but creates reset, ownership, concurrency,
endpoint, crash-recovery, and test-order coupling—especially for a single-stateful GUI client. Keep the existing allowed
reuse inside one compatible ordered `commonTest` scenario, while using templates for isolation across resources and test
tasks. Reconsider a resident pool only with measured startup evidence and a proven reset/isolation protocol; it is not
part of this implementation.

## Audited code baseline

This plan was reconciled with the current worktree on 2026-08-05. The current source has
`MinecraftTestSupport`, `MinecraftTestSupportService`, serializable `MinecraftTestResource` values,
`OfficialMinecraftServer`, and `HeadlessMinecraftClient`.

The audited selected inputs are Minecraft `26.2` (`./gradlew -q minecraftVersion`), HeadlessMC `2.10.0`, and repository
Java major `25`. The plan continues to obtain these values from `MinecraftTarget`, `HeadlessMcTarget`, and
`BuildVersions`; it does not duplicate them in module build scripts.

The audit covered the concrete producer and consumer path in:

- root `build.gradle.kts`, `OfficialDownloadsConvention.kt`, `OfficialDownloadTasks.kt`,
  `MinecraftTestFixtures.kt`, and `MinecraftTestFixtureService.kt`;
- the support service/models and the Fixture Host layout, host main, resource registry, and process launchers;
- the protocol-client, protocol-server, protocol-serialization, and world-io fixture declarations and scenarios;
- repository, buildSrc, support, Host, and affected-module `AGENTS.md`/README guidance.

The relevant current constraints that this plan deliberately fixes are:

- analyzers and the server-runtime extractor currently depend on `prepareOfficialMinecraftServer`;
- the Host currently receives separate client cache, version metadata, and HeadlessMC launcher paths;
- `newOfficialClient` currently launches Quick Play and returns after a HeadlessMC log marker;
- the common resource interface currently requires an endpoint even though only a server owns a listening endpoint;
- unsupported browser/Wasm test filtering currently depends on the test name containing `Official`;
- all three current official-server scenarios override `level-name`, while the world-io scenario genuinely needs a
  first-run world.

Concrete current-code migration points are:

| Current location                                                                             | Required change                                                                                                   |
|----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| root `build.gradle.kts`                                                                      | rename the aggregate convention/result variable and retain lazy service registration                              |
| `OfficialDownloadsConvention.kt`                                                             | split raw-server analysis inputs from the server fixture gate; rename/rebuild the client graph                    |
| `OfficialDownloadTasks.kt`                                                                   | neutralize client task/type/log names and separate asset download from assembly                                   |
| `MinecraftTestFixtures.kt`                                                                   | rename output/capability/input properties and replace `*Official*` filtering                                      |
| `MinecraftTestFixtureService.kt`                                                             | make Host runtime assembly/prepare distinct and consolidate Host input parameters                                 |
| support models/service/facade                                                                | move endpoint ownership, rename creation, add connect, add workspace policy                                       |
| `MinecraftTestLayout.kt`, Host main, `OfficialMinecraftClient.kt`, hosted resources/registry | consume the two template roots and one assembled client root; replace log readiness and official-client internals |
| `OfficialClientEndToEndRunner.kt` and its test                                               | rename, create title-ready, explicitly connect, then accept/service packets                                       |
| official-server runners                                                                      | keep template default for protocol tests; select fresh only for world-io                                          |

The external research anchors are
the [HeadlessMC repository and usage documentation](https://github.com/headlesshq/headlessmc),
the [MC-Runtime-Test reference project](https://github.com/headlesshq/mc-runtime-test), and Fabric's
[automatic-testing guide](https://docs.fabricmc.net/develop/automatic-testing). HeadlessMC documents headless LWJGL,
in-memory launch, separate game directories, and dummy assets; Fabric documents Loader-aware tests and client game
tests. Neither supplies this repository's protocol-specific `TITLE_READY`/`CONNECTING`/normal-stop contract, so the
small private control mod remains necessary.

## Settled terminology and boundaries

### Server

The server remains an **official Minecraft server** because the exact Mojang server JAR is both an external peer and the
repository's highest-precedence implementation evidence. Existing `Official` naming remains appropriate for server
downloads, analysis, codec-oracle work, RPC resources, and tests.

### Client

The client fixture is a **headless Minecraft client**, not an “official client”. Its Mojang client JAR and metadata
retain verified Mojang provenance, but the runnable fixture also contains Fabric Loader, the repository-owned control
mod, HeadlessMC's LWJGL support, and asset replacements. Remove `Official` from every client-facing task, capability,
RPC, resource, class, test, diagnostic, and documentation name.

The control mod must not change the protocol under test. It is client-only and may observe or control lifecycle state,
screens, connection initiation, disconnection, and shutdown. It must not:

- register custom payloads or alter packet codecs;
- add registry content, data packs, resource packs, blocks, items, or entities;
- mix into packet encoding, decoding, framing, encryption, compression, or protocol-state transitions;
- replace the vanilla connection/session implementation.

The actual server connection continues through the vanilla client path. Client creation stops at a stable title screen;
an explicit Fixture Host operation then tells the mod to initiate a vanilla connection to a supplied endpoint. The
production server's observed packets remain the protocol oracle; a mod-reported state is a lifecycle signal, not a
protocol assertion.

### Private modules

Add a private JVM/Fabric module:

```text
:minecraft-test-fixture-client
```

It owns only the version-matched Fabric fixture-control mod. It is not published and is never placed on consuming test
runtime classpaths. `minecraft-test-fixture-host` remains the only host-filesystem/process implementation, while
`minecraft-test-support` remains the only KMP dependency visible to consuming tests.

Cacheable Gradle template task types remain in `buildSrc`, but they invoke a private, non-RPC template-worker entry
point owned by `minecraft-test-fixture-host`. The worker reuses the Host's process, status, control-channel, copy, and
sanitization primitives. It is not a user-facing helper CLI, it is not launched by consuming test tasks, and it does not
replace the lazily started RPC Host used at test execution time.

## Gradle task vocabulary

Use these verbs consistently across the repository:

- `download*`: acquire and verify external bytes. A download task does not arrange a runnable layout.
- `generate*`: create new derived state from declared inputs, including executing a program to produce a template.
- `assemble*`: select, copy, combine, remap, or arrange existing inputs into their consumer-facing layout.
- `prepare*`: an actionless lifecycle gate and stable cross-project capability name.

Every `prepare*` task has all of these invariants:

- it is an ordinary actionless task;
- it declares no outputs and owns no files;
- it has no `doFirst`, `doLast`, `@TaskAction`, `Sync`, copy, download, validation, process execution, or generation
  logic;
- it declares only `group`, `description`, and dependencies on terminal producers;
- consuming modules depend on it through `dependsOn` or a file collection/provider `builtBy` that gate;
- internal producers remain connected through `TaskProvider.flatMap`, `RegularFileProperty`, `DirectoryProperty`, and
  other lazy provider provenance rather than redundant ordering edges.

Producer tasks validate the outputs they create. Do not add separate verification tasks or perform validation in a
`prepare*` gate.

Fresh runtime selection does not create a second public Gradle gate. A module still depends on the complete
`prepareOfficialMinecraftServer` or `prepareHeadlessClient` capability, then chooses `FRESH` per RPC resource. This may
prepare the default template even for a fresh-only scenario, but keeps the dependency model singular and explicit; split
the capability only if later measurements demonstrate that this one-time cost is material.

## Target Gradle task graph

### Shared release metadata

Keep the existing metadata chain:

```text
downloadVersionManifest
└── downloadVersionMetadata
```

`MinecraftTarget.MINECRAFT_VERSION` remains the only manually selected Minecraft release.

### Official server

```text
downloadVersionMetadata
└── downloadOfficialMinecraftServer
    ├── generateOfficialMinecraftServerTemplate
    │   └── prepareOfficialMinecraftServer       # actionless fixture gate
    ├── extractOfficialServerRuntime              # direct artifact input
    ├── analyzeOfficialMinecraftTarget            # direct artifact input
    └── analyzeOfficialMinecraftReports           # direct artifact input
```

The current analyzer and runtime-extractor `dependsOn(prepareOfficialMinecraftServer)` edges must be removed. Otherwise
every analysis or codec-oracle request would generate an unrelated world template after the gate is expanded. Official
analysis, runtime extraction, and codec-oracle producers consume the verified server JAR/metadata through their precise
`TaskProvider.flatMap` inputs and never consume the template. Only the external-server fixture file collection is
`builtBy(prepareOfficialMinecraftServer)` and includes the immutable server template.

### Headless client

Use these producer names as the target vocabulary. It is acceptable to combine adjacent internal producers when doing so
preserves precise non-overlapping outputs, but do not reintroduce `Official` client names or make a `prepare*` task do
work.

```text
downloadVersionMetadata
├── downloadMinecraftClientJar
├── downloadMinecraftClientLibraries
└── downloadMinecraftClientAssetIndex
    └── downloadMinecraftClientAssetObjects

HeadlessMcTarget.HEADLESS_MC_VERSION
├── downloadHeadlessMcLauncher
├── downloadHeadlessMcAssetReplacements
└── generateHeadlessMcJsonReplacement

:minecraft-test-fixture-client:remapJar

downloaded/resolved artifacts
├── assembleHeadlessClientAssets
├── assembleHeadlessClientVersionLayout
└── assembleHeadlessClientMods

all assembled client components
└── generateHeadlessClientTemplate
    └── prepareHeadlessClient                     # actionless gate
```

Fabric Loader and any narrowly required Fabric API modules are ordinary pinned Gradle dependencies resolved from the
official Fabric Maven repository. Do not create custom HTTP download tasks for Maven dependencies. The remapped
fixture-control mod, Fabric launch profile, and required runtime mods are inputs to the client assembly.

The precise client producer edges are:

- client JAR, libraries, asset index, HeadlessMC launcher, and HeadlessMC binary replacements are independent verified
  downloads after the minimum metadata they actually require;
- `downloadMinecraftClientAssetObjects` consumes only the asset index and writes the immutable original-object store;
- `assembleHeadlessClientAssets` consumes the index, original objects, downloaded binary replacements, and generated
  JSON replacement;
- `assembleHeadlessClientVersionLayout` consumes the client JAR, version metadata, Fabric Loader launch metadata, and
  resolved launch libraries;
- `assembleHeadlessClientMods` consumes the remapped control mod and only the selected Fabric API modules, if any;
- `generateHeadlessClientTemplate` consumes all client assembly outputs plus the launcher, and
  `prepareHeadlessClient` depends only on that terminal producer.

The current `DownloadOfficialMinecraftAssetsTask` combines downloading original objects with installing HeadlessMC
replacement objects. Split that ownership carefully: downloads create an immutable content-addressed object input and
`assembleHeadlessClientAssets` creates the consumer-facing filtered/replacement layout. Do not create a bulk `Sync` that
duplicates complete libraries and assets trees merely to claim one monolithic output. Prefer producer-owned,
non-overlapping subdirectories below one logical root; immutable asset objects may use a verified reflink/hard-link/copy
fallback, but mutable templates and runtime workspaces must never be hard-linked. Small compatibility layouts,
manifests, version JSON, and mod directories may use `Sync`.

### Fixture Host runtime

The existing `prepareMinecraftTestFixtureHostRuntime` is currently a `Sync` producer and therefore violates the new
vocabulary. Rename the producer and add an actionless gate:

```text
assembleMinecraftTestFixtureHostRuntime
└── prepareMinecraftTestFixtureHostRuntime        # actionless gate
```

### Removed/replaced public tasks

Do not keep deprecated aliases; this is private build infrastructure and a single vocabulary is clearer.

| Existing task                                         | Replacement                                                                    |
|-------------------------------------------------------|--------------------------------------------------------------------------------|
| `downloadOfficialMinecraftClient`                     | `downloadMinecraftClientJar`                                                   |
| `downloadOfficialMinecraftClientLibraries`            | `downloadMinecraftClientLibraries`                                             |
| `downloadOfficialMinecraftAssetIndex`                 | `downloadMinecraftClientAssetIndex`                                            |
| `downloadOfficialMinecraftAssets`                     | `downloadMinecraftClientAssetObjects` plus `assembleHeadlessClientAssets`      |
| `downloadHeadlessMc`                                  | `downloadHeadlessMcLauncher`                                                   |
| `downloadHeadlessMcDummyFiles`                        | `downloadHeadlessMcAssetReplacements` plus `generateHeadlessMcJsonReplacement` |
| `createHeadlessMcClientLayout`                        | `assembleHeadlessClientVersionLayout`                                          |
| `prepareHeadlessMc`                                   | removed; subsumed by `prepareHeadlessClient`                                   |
| `prepareOfficialMinecraftClient`                      | `prepareHeadlessClient`                                                        |
| current `prepareMinecraftTestFixtureHostRuntime` Sync | `assembleMinecraftTestFixtureHostRuntime`                                      |

Also rename the general convention and output vocabulary because the aggregate fixture set is no longer wholly official:

| Existing build-logic name                       | Target name                                |
|-------------------------------------------------|--------------------------------------------|
| `applyOfficialDownloadsConvention`              | `applyMinecraftFixtureArtifactsConvention` |
| `OfficialMinecraftFixtureOutputs`               | `MinecraftTestFixtureOutputs`              |
| `officialMinecraftFixtureOutputs` extension     | `minecraftTestFixtureOutputs`              |
| root `officialMinecraftFixtures` variable       | `minecraftTestFixtures`                    |
| test input property `officialMinecraftFixtures` | `minecraftTestFixtures`                    |

Rename client download task types and their logs as well, for example
`DownloadOfficialMinecraftClientTask` to `DownloadMinecraftClientJarTask` and `OfficialClientAsset` to
`MinecraftClientAsset`. Mojang provenance remains explicit in verification metadata. Use the neutral `minecraft
fixtures` task group for the mixed client/Host pipeline, retaining `official minecraft analysis` for official-server
analysis.

Descriptions and verification metadata may state that client bytes came from Mojang. That provenance must not leak back
into the runnable fixture's identity.

## Artifact layout and Gradle ownership

Expose one logical immutable root for each prepared capability. Exact paths may follow existing root-build conventions,
but the client must look conceptually like this:

```text
headless-client/<minecraft-version>/
├── runtime/
│   ├── headlessmc/
│   ├── minecraft/
│   │   ├── versions/
│   │   ├── libraries/
│   │   └── assets/
│   └── mods/
│       └── minecraft-test-fixture-client.jar
├── template/
└── manifest.json
```

Individual producers own non-overlapping subdirectories. No task claims a broad parent directory also written by another
task. The parent is a logical provider root, not an overlapping task output.

The terminal client producer owns `manifest.json`. It records the selected Minecraft, HeadlessMC, Fabric Loader, and
control-mod identities; relative launch/profile paths; template policy revision; and sanitized-template facts needed by
the Host. It contains no absolute build path. The server template has an equivalent producer-owned manifest containing
the Minecraft version, fixed world-generation policy revision, clean-stop result, and sanitized default world name.
These manifests replace Host rediscovery and the current need to pass client version metadata separately.

Generated templates, downloads, assembled runtimes, logs, and scratch data remain below root `build/`; none are
committed. The final server and client template directories are sanitized before the producing task completes so
cacheable outputs contain no volatile log, lock, endpoint, identity, or timestamp-only files.

Replace the client-related fields currently passed independently to the Build Service with one immutable
`headlessClientDirectory`. Add one `officialServerTemplateDirectory`. The resulting Build Service/Host input schema is:

- Host classpath and Host work directory;
- selected Minecraft version;
- official server artifact directory and official server template directory;
- one assembled headless-client directory;
- official codec runtime/classes directories;
- Fixture Host scratch root.

Remove the separate `clientCacheDirectory`, `versionMetadataFile`, and `headlessLauncherFile` service parameters and
Host main arguments. The assembled client manifest contains the exact launch information the Host needs. Update the Host
main argument count/order, `MinecraftTestLayout`, fixture input `FileCollection`s, and their focused tests together. The
Fixture Host must not know how Gradle downloaded or assembled the client JAR, Fabric, HeadlessMC, mod, libraries, or
assets.

## Official server template

### Generation

`generateOfficialMinecraftServerTemplate` is a cacheable producer with declared inputs including at least:

- the verified server artifact and selected Minecraft release;
- the Java-major policy used to launch the fixture;
- the fixed default template properties and sanitization policy;
- the task implementation.

The cacheable task type lives in `buildSrc`; its declared worker classpath points at the private template-worker runtime
from `minecraft-test-fixture-host`. The worker launches `java` from `PATH` and enforces the repository policy that its
major version is at least 25. It does not substitute the Gradle toolchain's exact Java executable or infer Java from
Mojang metadata. Fixed world inputs and sanitization normalize volatile values sufficiently that every cached output is
a valid equivalent default template for the same declared inputs.

Its action:

1. creates a private candidate work directory below `build/`;
2. writes `eula=true` and deterministic default server properties;
3. uses a fixed seed, a flat world, disabled structures, low view/simulation distances, offline mode, and an ephemeral
   loopback endpoint;
4. launches the official server and watches process exit while probing a complete status request and pong;
5. sends the vanilla `stop` command;
6. requires exit code zero and complete process-output closure;
7. sanitizes the stopped directory into the task output;
8. fails without publishing a template if graceful shutdown did not complete.

This is the only deliberate exception to the current rule that the root official-analysis/extraction pipeline is the
only build-task layer allowed to open or execute the official server JAR. Amend that rule explicitly: those tasks may
inspect/execute the JAR for evidence and precise runtime extraction, while the declared root server-template producer
may execute, but not inspect or decompile, it solely to publish its sanitized template. No other Gradle task may open or
execute the server JAR.

### Sanitization

The template is primarily the generated default world, not a captured server instance configuration. Use an allowlist or
an equivalently strict policy. At minimum, do not preserve:

- `server.properties`;
- `eula.txt` (the Host writes it for every private runtime);
- logs and crash reports;
- session/lock files;
- user cache, operator, whitelist, and ban state;
- a fixed runtime port, address, MOTD, or process-specific setting.

After copying the template, the Fixture Host writes the current resource's `eula.txt` and complete `server.properties`.
This ensures caller overrides and the selected ephemeral port are never inherited from the template.

### Runtime selection

The default official-server creation path clones the immutable default template. Provide an explicit fresh-workspace
path for tests that need first-run behavior or different world generation. In template mode, reject world-generation
overrides that are incompatible with the generated world, including seed, level type, generator settings, structure
generation, initial data packs, and equivalent worldgen choices, and direct the caller to fresh mode.

`level-name` is not by itself a world-generation override. It selects the runtime destination directory. After cloning,
the Host safely relocates the template's default world directory to the caller's validated relative `level-name`, then
writes the complete server properties. This preserves template use for the existing protocol-client and
protocol-serialization scenarios, both of which choose a custom world name. Endpoint, compression, view distance, MOTD,
and similar runtime settings remain overridable. The world-io generation/rewrite/reload scenario must explicitly request
fresh mode because first-run official world creation is part of what that scenario exercises.

Every resource receives a private real copy or copy-on-write clone. Never use mutable hard links for a server world and
never run a process directly in the template directory. The clone helper rejects symbolic links and path escapes, and
focused Host tests prove that modifying or deleting a runtime copy cannot mutate the immutable template.

Cloning happens exactly once, during `newOfficialServer`. `stopServer` retains that private workspace, and
`restartServer` relaunches in the same workspace so command changes and files written through the existing world RPC
survive restart. Restart never silently re-clones the template or resets a fresh workspace. Final resource cleanup
deletes the private workspace.

## Headless client control mod

### Complexity and implementation shape

The mod is deliberately small: one client entry point, one lifecycle/state observer, one host-control connection, and a
small command/event model. Prefer maintained Fabric client lifecycle/tick APIs; use a narrow Mixin only where no
maintained event exposes the required state. Build it against the repository-selected release and Mojang mappings.

Pin Fabric Loader, Loom, and any narrow Fabric API module versions in the build configuration. The mod must compile and
remap as part of the ordinary Gradle graph and update together with the selected Minecraft release.

### Control boundary

The Fixture Host creates one loopback-only internal control listener per client process and passes only its ephemeral
address through launch/JVM properties. The mod connects outward, and the Host accepts exactly the one expected child
connection. The channel is trusted process plumbing and never crosses the public kRPC boundary; it needs neither
authentication, TLS, discovery, nor a secret-token subsystem. If implementation uses one shared listener instead, use an
opaque correlation identifier only to associate the child, not as a claimed security boundary.

Use a small structured, versioned machine protocol rather than ordinary log text. Required events are:

- `CLIENT_STARTED`: the Minecraft instance and client thread exist;
- `TITLE_READY`: startup overlay/resource loading is complete and the title screen is stable;
- `CONNECTING`: the vanilla connection path has begun;
- `PLAY_READY`: player and level exist, no blocking screen is open, the local player chunk is non-empty, and stable
  client ticks have completed;
- `DISCONNECTED`: the client has left its level and returned to a stable menu state;
- `STOPPING`: normal client shutdown has been scheduled on the client thread;
- `FAILED`: a crash, error screen, connection failure, invalid transition, or timeout occurred.

The first child message identifies the control-protocol revision, Minecraft release, and control-mod build identity; the
Host rejects a mismatch. Events carry a monotonically increasing sequence number and the current lifecycle state so a
lost/duplicate or invalid transition fails with bounded process and control diagnostics. The Host owns all time limits
and watches process exit concurrently with every awaited event.

Required commands are initially limited to:

- `STATUS`;
- `CONNECT` with one validated loopback `MinecraftTestEndpoint`;
- `DISCONNECT`;
- `STOP`.

Do not expose general reflection, arbitrary commands, GUI automation, or packet manipulation. Encode the versioned
line-delimited messages with `kotlinx.serialization`; do not hand-build or hand-escape protocol JSON. Direct
standard-stream output is allowed only if it is explicitly isolated as this machine protocol. Ordinary diagnostics use
the owning logging API.

### Normal shutdown

On `CONNECT`, schedule the maintained vanilla screen/network connection path on the client thread and emit `CONNECTING`
once that operation has been accepted. On `DISCONNECT`, schedule the vanilla disconnect operation on the client thread
and report `DISCONNECTED` only after the level is absent. On `STOP`, disconnect first when necessary, then schedule
`Minecraft.stop()` on the client thread. The Host treats control-channel closure plus process exit code zero as
successful completion. Forced process-tree termination remains an abort/cleanup fallback and never qualifies a generated
template as valid.

HeadlessMC and `mc-runtime-test` establish the supported headless CI launch path, while Fabric Client GameTest
establishes that client-side state can be observed inside a real Minecraft client. The repository-specific conclusion is
to determine readiness from client state and stop on the client thread, rather than inferring readiness from HeadlessMC
launcher text or killing the JVM. HMC-Specifics is not selected because its GUI/message/click command surface and
separate version-matched mod are broader than this fixture contract requires.

Use Fabric Client GameTest, where supported by the selected Fabric toolchain, for focused in-client assertions about the
mod's event/state adapter and clean stop. It does not replace the repository's `commonTest` protocol scenario or become
the public test entry point; HeadlessMC plus the Fixture Host remains the actual headless integration path.

## Headless client template

### Generation

`generateHeadlessClientTemplate` consumes the complete assembled runtime and control-mod artifact. Its action:

1. creates a private candidate game directory;
2. writes deterministic default client options;
3. starts the Fabric launch profile through HeadlessMC's supported in-process mode without Quick Play;
4. launches the control mod in template-preparation mode;
5. waits for structured `TITLE_READY`, including completed startup overlay/resource loading and a short stable-tick
   condition;
6. requests or allows the template mode to perform normal `STOP`;
7. requires process exit code zero and complete output closure;
8. sanitizes the candidate into the template output;
9. fails without publishing the template if the client had to be forcibly terminated.

The generic template stops at the title screen and contains no server endpoint or player/session identity. Joining a
world is runtime state and is not required for the generic template. Add a separate play-warmed template only if
measured evidence later proves that it materially reduces persistent initialization cost.

### Sanitization

Prefer an allowlist of measured reusable state. Never retain:

- logs, crash reports, screenshots, or thread dumps;
- worlds/saves;
- `servers.dat`, Quick Play endpoints, or server resource-pack state;
- account tokens, session identity, telemetry identifiers, or user-specific data;
- locks, temporary files, or diagnostic output from Fabric, HeadlessMC, or the control mod.

Keep the control mod and Fabric runtime in the immutable assembled runtime, not as accidental mutable template residue.
Runtime-specific `options.txt`, player name, endpoint, and control-channel properties are written or passed after the
template is cloned. An endpoint is supplied only to the explicit connect operation, not persisted in the template or
client resource value.

HeadlessMC's writable home/configuration and working directory also live in the candidate/runtime workspace. The
assembled client root is a read-only task input: launch configuration must not let HeadlessMC or Minecraft repair,
download into, or otherwise mutate it. Producer/Host tests assert that client launch and shutdown leave that input
unchanged.

### Runtime use

The default `newHeadlessClient` path clones the template into a unique Fixture Host workspace, overlays current options
and launch parameters, starts the controlled Fabric client through HeadlessMC without Quick Play, and returns after
`TITLE_READY`. `connectHeadlessClient(client, endpoint)` then sends `CONNECT` and returns after the control mod reports
`CONNECTING`; it deliberately does not wait for `PLAY_READY`.

This split is required by the current production-server test: the test cannot call `MinecraftServer.accept()` and begin
servicing Login/Configuration/Play until client creation returns, while the client cannot reach `PLAY_READY` until that
server work happens. Waiting for Play inside `newHeadlessClient` would deadlock. The test's production server instead
proves protocol readiness through observed vanilla packets such as Configuration completion, teleport acknowledgement,
chunk-batch acknowledgement, keepalive response, and client-tick packets. `PLAY_READY` remains available to the Host for
diagnostics/lifecycle observation but is not the creation or connect-operation completion condition.

Provide an explicit fresh-workspace mode that creates an empty game directory but still uses the same Fabric control mod
and normal lifecycle. Do not add a mod-free/raw client mode unless a concrete test later requires it; modified clients
are accepted for these protocol tests.

Client templates remove repeated disk initialization only. They do not remove JVM startup, class loading, or resource
reload cost. Record before/after startup timing and the retained template manifest so the optimization remains evidence
based.

Measure both fixture kinds with the same dimensions: template file count/bytes, clone duration, fresh start-to-ready,
template-backed start-to-ready, and total representative test-task time. Logging these measurements during focused
benchmark runs is sufficient; do not add a flaky performance threshold to the ordinary test gate.

## Public fixture model and naming migration

Keep one creation API per resource with an explicit serialized
`MinecraftFixtureWorkspacePolicy { TEMPLATE, FRESH }` rather than parallel method families. Template mode is the
default; fresh mode is opt-in. Add the policy to both `OfficialMinecraftServerConfiguration` and
`HeadlessMinecraftClientConfiguration`.

Reconcile the current service/value API as follows:

- `MinecraftTestResource` contains only `id`;
- `OfficialMinecraftServer` contains `id` and its listening `endpoint`;
- `HeadlessMinecraftClient` contains only `id`;
- `HeadlessMinecraftClientConfiguration` contains player identity, workspace policy, and relevant startup/stop limits,
  but no server endpoint;
- `newHeadlessClient(configuration)` creates a title-ready client;
- `connectHeadlessClient(client, endpoint)` begins one vanilla connection and returns at `CONNECTING`;
- `close(client)` performs normal mod-directed disconnect/stop, while `awaitClientExit` remains an observation API.

Do not add a public disconnect/reconnect family until a real test needs client reuse across multiple connections. The
Host may retain `DISCONNECT` internally for correct `close` behavior.

Perform the client rename end to end:

| Existing concept                                                       | Target concept                         |
|------------------------------------------------------------------------|----------------------------------------|
| `OfficialMinecraftFixtureOutputs`                                      | `MinecraftTestFixtureOutputs`          |
| `officialClient` fixture files                                         | `headlessClient`                       |
| `requiresOfficialClient`                                               | `requiresHeadlessClient`               |
| `MinecraftTestSupportService.newOfficialClient`                        | `newHeadlessClient`                    |
| `MinecraftTestSupport.newOfficialClient`                               | `newHeadlessClient`                    |
| Host service/resource-registry `newOfficialClient` methods             | `newHeadlessClient`                    |
| `OfficialMinecraftClient` / `OfficialClientPreparation` Host internals | `HeadlessMinecraftClientLayout`        |
| `OfficialClientEndToEndRunner`                                         | `HeadlessClientEndToEndRunner`         |
| `OfficialHeadlessClientInteropTest`                                    | `HeadlessClientInteropTest`            |
| `MinecraftRuntimeKind.OFFICIAL_CLIENT`                                 | `MinecraftRuntimeKind.HEADLESS_CLIENT` |

Server and codec-oracle names retain `Official`.

The Build Service and Fixture Host receive the server artifact/template providers and one `headlessClientDirectory`
provider. Tests continue to receive only serializable remote resource values through `minecraft-test-support`; no host
path, template path, Fabric object, HeadlessMC object, process object, or control-mod endpoint crosses kRPC.

### Standard-test capability filtering

Renaming `OfficialHeadlessClientInteropTest` to `HeadlessClientInteropTest` would bypass the current unsupported-target
filter `excludeTestsMatching("*Official*")`. Replace that accidental vocabulary dependency with capability-aware test
filtering at the standard test-task boundary:

- `requiresHeadlessClient` excludes the stable `*HeadlessClient*` fixture scenario pattern on browser and Wasm/WASI;
- `requiresOfficialServer` and `requiresCodecOracle` retain explicit patterns for their external fixture scenarios;
- supported test tasks receive only the requested fixture input collection and Build Service;
- keep any JS Node transport-specific exclusion explicit where its limitation differs from browser/Wasm capability.

Do not use runtime guesses or fake passing implementations. Add a build-logic test or task-inspection assertion proving
the renamed headless-client scenario is excluded from unsupported test tasks and remains attached to supported ones.

## Implementation sequence

1. **Codify vocabulary and ownership**
    - Update the applicable `AGENTS.md` files with the normative text below.
    - Add `:minecraft-test-fixture-client` to settings with an owning `AGENTS.md` and README.
    - Add pinned Fabric/Loom configuration without duplicating the selected Minecraft version.

2. **Normalize existing Gradle task names**
    - Rename the aggregate convention, output model, extension, root variable, fixture-input property, client download
      task types, task group, and layout producers listed above.
    - Remove `prepareHeadlessMc` and `prepareOfficialMinecraftClient`.
    - Add the empty `prepareHeadlessClient` gate.
    - Rename the Fixture Host runtime `Sync` producer and place an empty `prepareMinecraftTestFixtureHostRuntime` gate
      above it.
    - Rename the client capability flag/field and introduce capability-aware unsupported-target filtering.
    - Do not retain aliases with the old client vocabulary.

3. **Generate the server template**
    - Add the private Fixture Host template-worker entry point and the cacheable buildSrc producer that invokes it using
      validated Java 25-or-newer from `PATH`.
    - Implement deterministic default properties, status readiness, clean `stop`, strict sanitizer, manifest,
      symlink-safe clone/relocation, and immutable output.
    - Make `prepareOfficialMinecraftServer` depend on the terminal server-template producer while preserving the raw
      server artifact for analysis.
    - Remove every analyzer/extractor dependency on that gate and wire raw server inputs directly from the download
      task.
    - Add template/fresh workspace policy, allow safe `level-name` relocation, reject incompatible worldgen overrides,
      and switch the world-io first-run scenario to fresh mode.

4. **Build the fixture-control mod**
    - Implement only lifecycle observation and the private `STATUS`/`CONNECT`/`DISCONNECT`/`STOP` protocol.
    - Verify title readiness, play readiness, disconnect, clean stop, crash/error reporting, and timeout behavior.
    - Ensure no custom payload or protocol registration appears in the remapped JAR.

5. **Assemble and template the headless client**
    - Assemble the launchable Fabric/HeadlessMC version layout, filtered assets, and mod set from declared providers.
    - Implement `generateHeadlessClientTemplate`, structured readiness, normal stop, sanitizer, and manifest.
    - Make `prepareHeadlessClient` the sole public client gate.

6. **Simplify Build Service and Host inputs**
    - Replace separate client-cache, metadata, and HeadlessMC launcher parameters with `headlessClientDirectory`.
    - Add the server-template provider.
    - Update the Build Service parameter type, Host main argument schema, `MinecraftTestLayout`, fixture input
      collections, runtime kind, and process-layout tests as one change.
    - Clone templates into unique resource workspaces before writing runtime configuration; reject symlinks and never
      hard-link mutable files.
    - Keep task-owner cleanup, the bounded process pool, and forced process-tree cleanup as fallbacks.

7. **Migrate RPC/test names and behavior**
   - Apply the end-to-end headless-client renames.
    - Move endpoint ownership off `MinecraftTestResource` and the client configuration, add explicit
      `connectHeadlessClient`, return from creation at `TITLE_READY`, and return from connect at `CONNECTING`.
    - Default server/client creation to template-backed workspaces and expose typed fresh-workspace selection.
    - Replace launcher-log client readiness with structured control-mod readiness.
    - Replace normal client `Process.destroy` cleanup with mod-directed disconnect/stop and exit-code validation.

8. **Update documentation and remove stale vocabulary**
    - Update root/module READMEs (including “headless official client”), KDoc, errors, task descriptions, test names,
      and property names. Use `external-peer` as the general umbrella and retain `official server` only where factual.
    - Search for stale `OfficialClient`, `official client`, `requiresOfficialClient`, `prepareHeadlessMc`, and
      `prepareOfficialMinecraftClient` references, including case and separator variants.

## Required `AGENTS.md` changes

The implementation should add or adapt the following normative content. Keep the prose in English to match the existing
guides; merge it into the most relevant existing sections instead of duplicating nearby rules.

### Root `AGENTS.md`

Use `external-peer` for architecture/test rules that cover both fixture kinds; retain `official server` where the peer
really is the verified Mojang server. This replaces the current blanket `official-peer` wording without weakening the
official-server evidence rules.

Add the new private module to repository architecture:

> `minecraft-test-fixture-client` contains the private, version-matched Fabric client mod used only to report headless
> client lifecycle state and request vanilla connection, disconnection, and shutdown. It is never published and never
> enters consuming test runtime classpaths.

Replace client references that claim an unmodified official peer and add:

> External client protocol tests use a controlled headless client assembled from verified Mojang client artifacts,
> Fabric Loader, the repository-owned fixture-control mod, HeadlessMC, and version-matched resources. The control mod
> may
> observe lifecycle state and request vanilla connection, disconnection, and shutdown, but it never registers protocol
> payloads, changes codecs or registries, or replaces the vanilla network path. Server-observed packets remain the
> protocol evidence.

Add template isolation rules to test architecture/development output policy:

> Gradle produces immutable, sanitized default server and headless-client templates below `build/`. The Fixture Host
> clones a template into a unique workspace for every template-backed resource and writes all endpoint, identity, EULA,
> and runtime configuration only after cloning. A test that needs first-run behavior or incompatible world generation
> explicitly requests a fresh workspace. No process runs in a template directory, no mutable runtime uses hard links to
> a template, and generated templates are never committed.

Replace the exclusive server-JAR execution rule with:

> Root official-analysis/extraction tasks are the only build tasks that inspect the official server JAR. The root
> `generateOfficialMinecraftServerTemplate` producer is the sole additional build task allowed to execute, but not
> inspect or decompile, that JAR; it does so only through the private Fixture Host template worker and publishes only a
> sanitized, normally stopped template. Analysis and codec producers consume the verified server artifact directly and
> never depend on the fixture template gate.

### `buildSrc/AGENTS.md`

Replace the existing lifecycle-task paragraph with:

> Gradle task verbs are semantic. `download*` tasks acquire and verify external bytes, `generate*` tasks create derived
> state or execute a producer, `assemble*` tasks arrange existing inputs into consumer layouts, and every `prepare*`
> task
> is an actionless lifecycle gate. A `prepare*` task owns no output and performs no download, validation, generation,
> copy, `Sync`, or process action; consumers depend on it through `dependsOn` or `builtBy`. Producer relationships use
> lazy file/provider provenance, and producer tasks validate their own outputs.

Add the concrete gates:

> `prepareOfficialMinecraftServer`, `prepareHeadlessClient`, `prepareOfficialMinecraftCodecOracle`, and
> `prepareMinecraftTestFixtureHostRuntime` are the stable fixture gates. `prepareHeadlessClient` covers the assembled
> Mojang client, Fabric runtime, fixture-control mod, HeadlessMC launcher, filtered assets, and sanitized client
> template;
> there is no separate public `prepareHeadlessMc` or `prepareOfficialMinecraftClient` gate.

Add template producer ownership:

> Server and client template generators run their matching process only as cacheable Gradle producers. They require
> structured readiness, normal exit code zero, and deterministic sanitization before publishing an output. A forced
> process termination fails template generation. Template tasks never write outside their declared candidate/output
> directories, and Fixture Host runtime workspaces are not task outputs.

Add the worker boundary and analysis separation:

> Cacheable template task types live in buildSrc and invoke the private non-RPC template-worker entry point provided by
> `minecraft-test-fixture-host`; process, filesystem, status, control-channel, and sanitization behavior remains owned
> by
> the Host module. The server worker launches `java` from `PATH` and requires major version 25 or newer. Consuming test
> tasks never invoke the worker. Official analysis, server-runtime extraction, and codec-oracle tasks take the raw
> verified server producer as a precise input and never depend on `prepareOfficialMinecraftServer`.

Add artifact composition guidance:

> Large libraries and asset trees are not recopied merely to create a monolithic assembly task. Independent producers
> own non-overlapping subdirectories below one logical fixture root; small compatibility layouts may use `Sync`. Fabric
> Maven artifacts use Gradle dependency resolution rather than custom download tasks. Immutable content-addressed asset
> objects may use a verified reflink/hard-link/copy fallback; templates and mutable runtime workspaces never use hard
> links.

Replace the Fixture Host wiring terminology/filter rule with:

> External-fixture capability flags map to exact lazy artifact collections on supported standard KMP test tasks.
> `requiresOfficialServer`, `requiresHeadlessClient`, and `requiresCodecOracle` use capability-specific stable test-name
> exclusions on unsupported browser/Wasm/WASI tasks; filtering never relies on the general word `Official`. A consuming
> task obtains the shared Build Service only in its execution action after its requested immutable fixture inputs and
> assembled Host runtime are ready.

### `minecraft-test-fixture-host/AGENTS.md`

Add:

> The Host consumes one Gradle-provided immutable headless-client root and the Gradle-provided official-server template.
> It never downloads, assembles, repairs, or writes either input. Template-backed creation clones the selected template
> into a unique resource workspace before writing EULA, properties, options, identity, endpoint, or control-channel
> data. Fresh mode starts from an empty unique workspace.

Add controlled-client lifecycle rules:

> Headless-client readiness comes from the fixture-control mod's structured loopback protocol, never from a HeadlessMC
> launcher log marker. Normal cleanup requests vanilla disconnect and `Minecraft.stop()` on the client thread and waits
> for exit code zero. Process-tree termination is an abort fallback and cannot validate or publish a template. The Host
> keeps the mod control endpoint private; public kRPC clients receive only serializable fixture resource values. Client
> creation returns at `TITLE_READY`; explicit connection returns at `CONNECTING`, before Play, so the production test
> server can accept and service the connection. Play is proven by server-observed protocol traffic.

Add template isolation:

> A runtime may mutate only its private clone. The Host never pools mutable server/client processes across task owners
> merely because their source template is shared, and it never reuses a completed runtime directory as a new template.

Replace the current “only the Build Service launches this Host” wording with the precise exception:

> Only the shared Build Service launches the long-lived kRPC Fixture Host. Declared Gradle template producers may invoke
> the module's private non-RPC template worker; the worker is not a user CLI or fixture-launch task and cannot serve
> test
> RPC requests. Both entry points keep process and host-filesystem behavior inside this module.

### `minecraft-test-support/AGENTS.md`

Add/replace client terminology with:

> The public external-client fixture is named `HeadlessMinecraftClient`; it is not described as an official client.
> `MinecraftTestSupport.newHeadlessClient` defaults to an isolated copy of the Gradle-prepared client template and
> accepts
> a typed fresh-workspace policy for first-run cases. Configuration never exposes a host/template path, Fabric object,
> HeadlessMC object, process, control endpoint, or arbitrary JVM/mod argument.

Add the resource/connection split:

> `MinecraftTestResource` carries only its Host-owned ID; only `OfficialMinecraftServer` exposes a listening endpoint.
> `newHeadlessClient` returns a title-ready client and `connectHeadlessClient(client, endpoint)` requests the vanilla
> connection path, returning after connection initiation rather than Play readiness. The client endpoint is neither a
> creation parameter nor persistent resource identity.

Add protocol-evidence separation:

> Control-mod lifecycle states determine when the remote client can be used or normally stopped; they are not protocol
> assertions. Portable test scenarios continue to prove interoperability through packets observed by the production
> protocol client/server APIs.

### `protocol-server/AGENTS.md`

Replace official-client terminology and add:

> The external client interoperability scenario uses the controlled `HeadlessMinecraftClient`, creates it to
> `TITLE_READY`, explicitly initiates its vanilla connection, and then services the production server connection. The
> fixture mod supplies lifecycle coordination only; assertions and readiness for protocol behavior come from packets
> observed by the production server. Unsupported standard test tasks exclude this scenario with the stable
> `*HeadlessClient*` capability pattern rather than relying on `Official` in its name.

### `world-io/AGENTS.md`

Add:

> The official world generation, rewrite, restart, and reload scenario explicitly requests a fresh official-server
> workspace because first-run world creation is part of its evidence. Other official-server scenarios use the sanitized
> template by default unless they change world-generation inputs.

### New `minecraft-test-fixture-client/AGENTS.md`

Create it with at least:

> # minecraft-test-fixture-client
>
> This private Fabric module owns the version-matched headless-client lifecycle control mod. It is build/test
> infrastructure only: never publish it, expose it through `minecraft-test-support`, or place it on consuming test
> runtime classpaths.
>
> The mod observes Minecraft client lifecycle, screens, player/level/chunk readiness, connection failure, disconnect,
> and shutdown. It accepts only `STATUS`, validated loopback `CONNECT`, `DISCONNECT`, and `STOP` over the explicit
> private
> Fixture Host machine protocol and schedules Minecraft operations on the client thread. Prefer maintained Fabric
> lifecycle APIs; use the narrowest Mixin only when no maintained event provides the required state.
>
> The mod never registers custom payloads, modifies packet codecs or transport, adds registry/content/resource entries,
> intercepts protocol bytes, or replaces the vanilla network path. Its state reports are lifecycle signals, not protocol
> evidence. It exposes no arbitrary reflection, command execution, GUI automation, filesystem path, or public network
> listener.
>
> Ordinary diagnostics use the framework logging API. Standard streams are reserved only for an explicitly isolated
> machine/subprocess protocol. A normal stop disconnects as needed and invokes `Minecraft.stop()` on the client thread;
> direct `System.exit`, process destruction, and shutdown hooks are not normal lifecycle mechanisms.

## Verification

Run verification incrementally, preserving the build cache:

1. Build and test the new control-mod module with the selected Minecraft/Fabric versions.
2. Run `./gradlew prepareOfficialMinecraftServer prepareHeadlessClient`.
3. Repeat the same invocation unchanged and confirm task up-to-date/build-cache reuse.
4. Run the same gates with configuration cache enabled twice and confirm store/reuse.
5. Inspect sanitized template manifests: no endpoint, identity, control correlation value, log, crash, lock,
   `server.properties`, save, or process-specific file may remain.
6. Force template producers with `--rerun-tasks` when validating determinism; compare declared manifests rather than
   adding a permanent freshness/comparison workflow.
7. Run `./gradlew :minecraft-test-fixture-client:build` and its applicable Fabric client test task.
8. Run `./gradlew :minecraft-test-fixture-host:test :minecraft-test-support:jvmTest`.
9. Run `./gradlew :protocol-server:jvmTest` and verify the controlled headless client completes the existing packet
   probes through the production server.
10. Run `./gradlew :minecraft-test-fixture-host:test jvmTest` for the repository JVM gate.
11. Inspect task dependencies and confirm analysis/runtime extraction/codec requests do not schedule
    `generateOfficialMinecraftServerTemplate`, while the external-server fixture input does.
12. Inspect every registered `prepare*` task and confirm it is an actionless lifecycle task with no outputs.

Add focused coverage for:

- server template generation, normal stop, sanitizer, copy isolation, and fresh mode;
- acceptance and safe relocation of `level-name`, rejection of true template-incompatible world-generation overrides,
  and explicit fresh mode in the world-io scenario;
- client title/play readiness state transitions and structured failure diagnostics;
- creation completing at `TITLE_READY` and connect completing at `CONNECTING` before the server reaches Play, proving
  there is no create/accept deadlock;
- clean disconnect/stop with exit code zero and forced-cleanup fallback;
- client template sanitizer, private-copy isolation, and fresh mode;
- task-owner abort cleanup and bounded pool-slot release;
- current kRPC service/value serialization after moving endpoint ownership off the client resource;
- renamed capability wiring on supported standard KMP test tasks and exclusion of `HeadlessClientInteropTest` from every
  unsupported browser/Wasm/WASI task;
- Host main argument/layout validation with one headless-client root and one server-template directory;
- mod artifact/source inspection showing no custom payload, codec, transport, or registry integration;
- unchanged rerun and configuration-cache reuse for every changed producer/gate.

## Completion criteria

The work is complete only when:

- every `prepare*` task in the repository is actionless;
- `prepareHeadlessClient` is the sole public Gradle gate for the complete controlled client fixture;
- no runnable client API, task, class, test, or documentation calls the fixture an official client;
- default server and client creation clone sanitized immutable templates into unique workspaces;
- explicit fresh-workspace creation remains available for both fixtures;
- client creation/connect readiness and normal shutdown use the structured control-mod lifecycle rather than launcher
  log matching or ordinary process destruction, without waiting for Play before the test server can accept;
- template generation rejects forced shutdown and publishes only normally stopped outputs;
- official analysis/runtime extraction does not pull the server template into its graph;
- renamed headless-client tests remain excluded on unsupported standard test tasks;
- consumers see only the KMP support/RPC boundary and stable prepare gates;
- all required JVM, cache-reuse, and configuration-cache checks pass; and
- no generated template, downloaded artifact, runtime directory, or agent research checkout is committed.
