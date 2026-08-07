# Template-backed Minecraft fixtures using upstream HMC-Specifics

## Status and decision

This document is the implementation plan for simplifying the repository's external Minecraft fixtures. It was revised on
2026-08-07 after HeadlessHQ published HMC-Specifics for Minecraft 26.2. It describes future work only; changing this
plan does not authorize implementing or executing any step.

The selected repository inputs remain:

- Minecraft 26.2, selected only by MinecraftTarget.MINECRAFT_VERSION;
- HeadlessMC 2.10.0, selected only by HeadlessMcTarget.HEADLESS_MC_VERSION;
- Java major 25 from BuildVersions and java from PATH for launched JVMs;
- the upstream HeadlessHQ HMC-Specifics 26.2 Fabric artifact described below.

The revised decisions are:

- Do not create minecraft-test-fixture-client or any repository-owned Minecraft mod.
- Do not add Loom, Mojang mappings, a remap task, Fabric API, Client GameTest, a private mod socket, a JSON control
  protocol, or a duplicate client lifecycle state machine.
- Use the upstream HMC-Specifics command line as the narrow control adapter for title-screen detection, vanilla
  connection initiation, disconnection, normal shutdown, and optional test actions.
- Keep protocol truth outside HMC-Specifics. A client command acknowledgement is only process-control evidence; Login,
  Configuration, Play, reconfiguration, and packet behavior are proven by packets observed through this repository's
  production client or server.
- Preserve the previously selected Gradle preparation architecture: precise verified producers, immutable sanitized
  server and client templates, actionless prepare gates, lazy Build Service startup, private runtime copies, and no
  mutable process pooling across test-task owners.
- Keep Gradle preparation and test execution as separate lifecycles. Gradle produces immutable inputs. The Fixture Host
  alone creates, controls, observes, and removes mutable runtime resources while tests execute.

The runnable client is a headless Minecraft client assembled from Mojang client artifacts, Fabric Loader, upstream
HMC-Specifics, HeadlessMC, libraries, and filtered assets. It is not an official or unmodified Minecraft client.
Official remains reserved for the exact Mojang server and official codec evidence.

## Goals and non-goals

The fixture needs to provide only enough control for deterministic basic interoperability:

- start an official server and know when it accepts complete Status requests;
- start HeadlessMC with the selected client and know when HMC-Specifics can execute commands at the title screen;
- ask the vanilla client to connect to a loopback endpoint without Quick Play;
- let repository protocol code determine when Login, Configuration, and Play are actually complete;
- send server-console or supported HMC-Specifics commands when a concrete scenario needs an action;
- stop normally, distinguish a clean exit from forced cleanup, and wait for complete resource removal when required;
- retain bounded diagnostics on every failure.

The fixture does not attempt to provide:

- a perfect client lifecycle oracle or a typed mirror of every internal Minecraft state;
- a general GUI automation framework;
- a public abstraction over all HMC-Specifics commands;
- live Microsoft account testing;
- protocol assertions based only on HMC-Specifics text;
- a gameplay server, authoritative ticking world, or long-lived shared client;
- repository-owned compatibility code for every future Minecraft release.

## Upstream HMC-Specifics input

Use the release and source evidence at:

- https://github.com/headlesshq/hmc-specifics/releases/tag/26.2-latest
- https://github.com/headlesshq/hmc-specifics/pull/60
- https://github.com/headlesshq/hmc-specifics/blob/26.2-latest/README.md
- https://github.com/headlesshq/hmc-specifics/blob/26.2-latest/hmc-specifics-api/src/main/java/me/earth/headlessmc/mc/commands/ConnectCommand.java
- https://github.com/headlesshq/hmc-specifics/blob/26.2-latest/hmc-specifics-api/src/main/java/me/earth/headlessmc/mc/commands/DisconnectCommand.java
- https://github.com/headlesshq/hmc-specifics/blob/26.2-latest/hmc-specifics-api/src/main/java/me/earth/headlessmc/mc/commands/QuitCommand.java
- https://github.com/headlesshq/hmc-specifics/blob/26.2-latest/26_2/src/main/java/me/earth/headlessmc/mc/mixins/MixinMinecraft.java

The currently selected Fabric asset is:

| Fact                            | Value                                                            |
|---------------------------------|------------------------------------------------------------------|
| Release tag                     | 26.2-latest                                                      |
| Asset                           | hmc-specifics-26.2-fabric-latest.jar                             |
| Embedded implementation version | 2.4.0                                                            |
| Source commit at audit time     | bd0373f4751fd977a337943ee31f7a735aa00bbb                         |
| Size                            | 5,631,766 bytes                                                  |
| SHA-256                         | eb6993436e535d220d65069f9559c3402444294b2a484d1430c56507fddfc190 |

The 26.2-latest tag and asset URL are intentionally mutable prerelease aliases. They are not sufficient identities.
Build logic must declare the exact expected size and SHA-256 and fail closed if upstream replaces the bytes. It must not
query for the newest release during a build. An intentional update changes the recorded digest, size, source commit, and
any affected command markers together.

Keep HeadlessMC automatic version, Java, asset, and specifics downloads disabled. Gradle downloads and verifies all
inputs before the Host starts. Do not run the HeadlessMC specifics installer command at test execution time.

The audited Fabric metadata requires Fabric Loader and Minecraft 26.2 but does not require Fabric API. Resolve and pin a
compatible Fabric Loader and its launch libraries through Gradle. Do not add Fabric API unless a future audited
HMC-Specifics artifact explicitly requires it.

HMC-Specifics supplies a broader command surface than this repository initially needs. That broader implementation is
upstream-owned and does not justify recreating a smaller mod locally. The Host initially relies on only:

- gui, for title-screen and optional GUI observation;
- connect, for vanilla connection initiation;
- disconnect, for an explicit return to a menu when a scenario requires it;
- quit, for normal client shutdown;
- selected non-lifecycle commands described under extensible test control.

The selected source schedules connect and disconnect commands on Minecraft's main thread and uses the vanilla
ConnectScreen path. It does not replace this repository's packet evidence. Each future HMC-Specifics update must recheck
these source paths and the absence of custom payload, packet codec, transport, and registry changes relevant to the
interoperability scenario.

## Two strictly separated phases

| Concern           | Gradle preparation phase                                             | Test execution phase                                               |
|-------------------|----------------------------------------------------------------------|--------------------------------------------------------------------|
| Owner             | Root/buildSrc producers                                              | MinecraftTestFixtureService and minecraft-test-fixture-host        |
| Inputs            | Version metadata, verified external bytes, resolved Fabric artifacts | Immutable prepared roots supplied by Gradle                        |
| Mutable processes | Only declared template-generation candidates                         | Fixture Host plus per-resource server or in-memory HeadlessMC JVM  |
| Output            | Cacheable immutable artifacts, templates, manifests                  | Temporary private workspaces and bounded logs                      |
| Readiness         | Producer-specific readiness before publishing a template             | Per-resource readiness before returning an RPC value               |
| Cleanup           | Synchronous clean stop before a task output is accepted              | Resource cleanup, task-owner fallback, then Host shutdown fallback |
| Network API       | None exposed to tests                                                | KMP kRPC through minecraft-test-support                            |

Gradle tasks never become fixture launch APIs. Tests never discover downloads, invoke Gradle tasks, receive Gradle
objects, or open immutable artifact roots. The Host never downloads, repairs, verifies, or assembles Gradle inputs.

## Gradle preparation logic

This section preserves the prior plan's task vocabulary, ownership, template policy, and producer ordering. The only
client-side substitution is downloadHmcSpecifics plus the upstream JAR in assembleHeadlessClientMods instead of a
repository-owned remapped mod.

### Task vocabulary

- download tasks acquire and verify external bytes. They do not arrange a runnable layout.
- generate tasks create derived state, including a normally stopped template.
- assemble tasks arrange existing inputs into a consumer-facing layout.
- prepare tasks are actionless lifecycle gates. They own no outputs and perform no work.

Every prepare task:

- is an ordinary actionless task;
- declares no output;
- has no task action, Sync behavior, copy, download, validation, generation, or process execution;
- depends only on its terminal producers;
- is consumed through dependsOn or builtBy provider provenance;
- does not resolve providers during configuration.

Producer tasks validate their own outputs. Do not add separate verification or freshness tasks.

### Shared release metadata

Keep the existing metadata chain:

~~~text
downloadVersionManifest
└── downloadVersionMetadata
~~~

MinecraftTarget.MINECRAFT_VERSION remains the only manually selected Minecraft release. No client, Fabric, or
HMC-Specifics build script duplicates 26.2 as an independent target selection.

### Official server preparation

Keep this graph and its ownership:

~~~text
downloadVersionMetadata
└── downloadOfficialMinecraftServer
    ├── generateOfficialMinecraftServerTemplate
    │   └── prepareOfficialMinecraftServer
    ├── extractOfficialServerRuntime
    ├── analyzeOfficialMinecraftTarget
    └── analyzeOfficialMinecraftReports
         └── analyzeOfficialMinecraftConfiguration
~~~

The verified server JAR is a direct precise input to runtime extraction and every official analyzer. Those tasks must
not depend on prepareOfficialMinecraftServer because that gate includes a generated world template. Requesting analysis
or the codec oracle must not start an unrelated server-template process.

Only the external-server fixture collection is built by prepareOfficialMinecraftServer and contains the immutable server
template. The codec-oracle collection keeps its separate prepareOfficialMinecraftCodecOracle gate.

generateOfficialMinecraftServerTemplate remains the sole additional root producer allowed to execute, but not inspect or
decompile, the official server JAR. Its declared worker entry point remains owned by minecraft-test-fixture-host.

### Headless client preparation

Keep the independently prepared Mojang and HeadlessMC inputs and replace only the custom-mod node:

~~~text
downloadVersionMetadata
├── downloadMinecraftClientJar
├── downloadMinecraftClientLibraries
└── downloadMinecraftClientAssetIndex
    └── downloadMinecraftClientAssetObjects

HeadlessMcTarget.HEADLESS_MC_VERSION
├── downloadHeadlessMcLauncher
├── downloadHeadlessMcAssetReplacements
└── generateHeadlessMcJsonReplacement

HmcSpecificsTarget
└── downloadHmcSpecifics

pinned Fabric Loader configuration
└── resolved Fabric launch metadata and libraries

downloaded and resolved inputs
├── assembleHeadlessClientAssets
├── assembleHeadlessClientVersionLayout
└── assembleHeadlessClientMods

all assembled client components
└── generateHeadlessClientTemplate
    └── prepareHeadlessClient
~~~

The precise producer relationships remain:

- client JAR, client libraries, asset index, HeadlessMC launcher, HeadlessMC binary replacements, and HMC-Specifics are
  independent verified inputs after the minimum metadata each needs;
- downloadMinecraftClientAssetObjects consumes only the asset index and owns the immutable original object store;
- assembleHeadlessClientAssets consumes the index, original objects, verified binary replacements, and generated JSON
  replacement;
- assembleHeadlessClientVersionLayout consumes the client JAR, Mojang metadata, pinned Fabric launch metadata, and
  resolved launch libraries;
- assembleHeadlessClientMods consumes only the verified HMC-Specifics Fabric JAR and any future dependency proven
  necessary by its metadata;
- generateHeadlessClientTemplate consumes all assembled client inputs plus the HeadlessMC launcher;
- prepareHeadlessClient depends only on the terminal template producer.

There is no minecraft-test-fixture-client project, remapJar input, Loom plugin, mappings dependency, or locally built
mod artifact.

The HMC-Specifics download is a cacheable verified HTTP producer following the repository's existing Ktor download
policy. Fabric Loader and Maven libraries use Gradle dependency resolution rather than custom HTTP tasks.

### Fixture Host runtime preparation

Keep the Host runtime producer and actionless gate separate:

~~~text
assembleMinecraftTestFixtureHostRuntime
└── prepareMinecraftTestFixtureHostRuntime
~~~

The assembled Host classpath is an immutable test input. Starting the Host is never a Gradle task action.

### Stable task and build-logic naming

Keep the prior naming migration because the aggregate client is not an official client:

| Existing name                                       | Target name                                                                |
|-----------------------------------------------------|----------------------------------------------------------------------------|
| downloadOfficialMinecraftClient                     | downloadMinecraftClientJar                                                 |
| downloadOfficialMinecraftClientLibraries            | downloadMinecraftClientLibraries                                           |
| downloadOfficialMinecraftAssetIndex                 | downloadMinecraftClientAssetIndex                                          |
| downloadOfficialMinecraftAssets                     | downloadMinecraftClientAssetObjects plus assembleHeadlessClientAssets      |
| downloadHeadlessMc                                  | downloadHeadlessMcLauncher                                                 |
| downloadHeadlessMcDummyFiles                        | downloadHeadlessMcAssetReplacements plus generateHeadlessMcJsonReplacement |
| createHeadlessMcClientLayout                        | assembleHeadlessClientVersionLayout                                        |
| prepareHeadlessMc                                   | removed and subsumed by prepareHeadlessClient                              |
| prepareOfficialMinecraftClient                      | prepareHeadlessClient                                                      |
| current prepareMinecraftTestFixtureHostRuntime Sync | assembleMinecraftTestFixtureHostRuntime                                    |
| applyOfficialDownloadsConvention                    | applyMinecraftFixtureArtifactsConvention                                   |
| OfficialMinecraftFixtureOutputs                     | MinecraftTestFixtureOutputs                                                |
| officialMinecraftFixtureOutputs                     | minecraftTestFixtureOutputs                                                |
| root officialMinecraftFixtures                      | minecraftTestFixtures                                                      |
| test input officialMinecraftFixtures                | minecraftTestFixtures                                                      |

Add downloadHmcSpecifics and HmcSpecificsTarget without creating another public prepare gate. prepareHeadlessClient is
the single complete client capability.

Use the neutral minecraft fixtures task group for mixed client and Host preparation. Retain official minecraft analysis
for official-server evidence.

### Prepared artifact roots

Expose one immutable logical root for each capability. Producers own non-overlapping children; no producer claims a
parent also written by another producer.

The headless-client root is conceptually:

~~~text
headless-client/26.2/
├── runtime/
│   ├── headlessmc/
│   │   └── headlessmc-launcher.jar
│   ├── minecraft/
│   │   ├── versions/
│   │   ├── libraries/
│   │   └── assets/
│   └── mods/
│       └── hmc-specifics-26.2-fabric.jar
├── template/
└── manifest.json
~~~

The client manifest records:

- selected Minecraft and HeadlessMC versions;
- Fabric Loader profile identity and resolved library identities;
- HMC-Specifics release tag, embedded version, source commit recorded at selection time, size, and SHA-256;
- relative launcher, Minecraft cache, Fabric profile, mods, template, and game-directory paths;
- template policy revision and sanitization facts;
- the exact HMC-Specifics startup and command markers expected by the Host.

It contains no absolute path, runtime endpoint, player identity, account token, log cursor, PID, timestamp-only
identity, or mutable release lookup result.

The server root equivalently contains the verified server artifact, sanitized default-world template, and manifest with
the selected Minecraft version, fixed world policy, clean-stop result, and default world name.

Large libraries and assets are not bulk-copied merely to create one broad output. Immutable content-addressed objects
may use a verified reflink, hard-link, or copy fallback. Templates and mutable runtime workspaces never use hard links.
Small manifests, profiles, mod directories, and compatibility layouts may use Sync.

### Official server template generation

generateOfficialMinecraftServerTemplate:

1. creates a private candidate below build;
2. writes eula=true and deterministic default server properties;
3. uses a fixed seed, flat world, disabled structures, low distances, offline mode, and an ephemeral loopback port;
4. launches java from PATH and concurrently observes process exit;
5. waits for a complete Minecraft Status response and matching pong, not a log line;
6. sends the vanilla stop console command;
7. waits for process exit, complete output-pipe EOF, and exit code zero;
8. sanitizes the stopped candidate into the task output;
9. publishes no output if graceful stop or sanitization fails.

The sanitized template excludes at least server.properties, eula.txt, logs, crash reports, locks, caches, operator and
ban state, player identity, endpoint, MOTD, and other runtime configuration. It primarily contains the generated world.

At runtime, template mode clones this world into a unique private directory. The Host then writes the current eula and
complete server.properties. A validated relative level-name may relocate the cloned default world. World-generation
overrides that conflict with the template require the explicit FRESH workspace policy. The world-io first-generation
scenario always selects FRESH.

### Headless client template generation

generateHeadlessClientTemplate uses the same HMC-Specifics command path planned for runtime:

1. creates private HeadlessMC home, Minecraft cache view, and game-directory candidates below build;
2. stages the verified HMC-Specifics JAR into the candidate game mods directory;
3. writes deterministic client options and offline identity placeholders;
4. launches the exact Fabric profile through HeadlessMC 2.10.0 with lwjgl and inmemory, without Quick Play;
5. keeps hmc.jline.enabled=false so the worker controls the plain line-oriented stdin;
6. waits for new output containing HMC-Specifics initialized!;
7. sends gui through the command context and requires new output identifying
   net.minecraft.client.gui.screens.TitleScreen while the process remains alive;
8. sends quit, waits for new output containing Quitting Minecraft..., process exit, complete output EOF, and exit code
   zero;
9. sanitizes the normally stopped candidate and publishes the template and manifest;
10. fails without publishing an output if forced termination was necessary.

Operational title readiness is deliberately modest: the HMC-Specifics command context exists and gui observes the 26.2
title screen. The fixture does not invent TITLE_READY, PLAY_READY, or FAILED events. If this signal stops being
sufficient, first verify an upstream command or marker before considering repository-owned Minecraft code.

The client sanitizer excludes at least:

- logs, crash reports, screenshots, thread dumps, locks, and temporary files;
- worlds, saves, servers.dat, Quick Play data, resource-pack server state, and endpoint history;
- account tokens, player identity, telemetry identifiers, and user-specific options;
- writable HeadlessMC cache/configuration state that contains machine-specific paths;
- a copied HMC-Specifics JAR when the immutable runtime root is its owner.

The immutable assembled runtime is never launched in place. Template creation and every test launch use a private
candidate or clone. The producer and Host must prove that launch and shutdown leave the assembled input unchanged.

## Test execution topology

The runtime path is:

~~~text
standard Gradle test task
└── declared fixture inputs finish preparation
    └── task execution obtains MinecraftTestFixtureService
        └── Build Service lazily starts minecraft-test-fixture-host
            └── Host prints its one machine-readable RPC-ready announcement
                └── test receives RPC URL and owner ID through environment
                    └── minecraft-test-support calls kRPC
                        └── Host allocates a slot and private workspace
                            └── Host starts and controls an official server or in-memory HeadlessMC process
~~~

There is no explicit launch task, helper CLI used by tests, system property containing fixture paths, or process object
crossing kRPC.

### Build Service and Fixture Host lifecycle

The Build Service starts the Host only when an executing supported test first requests a connection. It waits for the
Host's existing MINECRAFT_TEST_FIXTURE_READY announcement while concurrently observing Host exit. That announcement
means only that kRPC is reachable; it says nothing about any server or client resource.

Each test task gets an owner ID. Every created resource belongs to that owner. Normal scenario cleanup closes its own
resource; task-finish owner cleanup handles aborted tests; Build Service shutdown is the final fallback.

The existing bounded slot pool remains. A slot covers process startup, running, stopped-with-workspace-retained,
workspace deletion, and final release. Templates do not justify sharing mutable processes or workspaces between owners.

At build shutdown the service:

1. stops accepting new owners and resources;
2. asks the Host to close every remaining owner;
3. sends the Host shutdown command;
4. waits for all resource creations, cleanup jobs, process registrations, and directories to become quiescent;
5. waits for Host process exit and output EOF;
6. forcibly terminates remaining process trees only after the bounded graceful path fails.

### Common process-control primitive

MinecraftTestProcess remains the single JVM-process primitive for the Host and template worker. Extend it only as needed
for reliable HMC command correlation; do not build another transport.

It owns:

- one process handle and its descendant cleanup fallback;
- serialized line writes to stdin;
- a bounded merged stdout/stderr log;
- a monotonically increasing internal output sequence that is not reset when old bounded text is dropped;
- process exit and output-reader completion;
- one configured graceful shutdown command;
- bounded graceful and forced termination.

Every command-and-observe operation:

1. acquires the resource command mutex;
2. records the current output sequence;
3. verifies that the process is alive;
4. writes exactly one validated line and flushes it;
5. waits only for output newer than the recorded sequence, process exit, or output-reader failure;
6. releases the mutex after its required acknowledgement or terminal result.

This prevents an old HMC or server log line from satisfying a later command. It is not a new wire protocol and no
sequence is sent into Minecraft. Public tests need not understand the internal cursor for lifecycle operations.

Process exit is always observed concurrently with readiness and command waits. No startup or shutdown path uses sleep,
delay, fixed post-command waiting, or unspecified test method order.

Define two completion levels:

- Process stopped: the graceful command was attempted, the OS process exited, the output reader reached EOF, and the
  exit code is available. Exit code zero is required for a clean stop.
- Resource fully released: the process is stopped, the private workspace is deleted, the registry entry is removed, and
  the bounded slot is released.

closeProcess waits for the first level and retains the workspace. deleteWorkingDirectory waits for the second level.
Asynchronous close schedules both idempotently. A test that must prove complete shutdown uses the two synchronous
operations or a structured helper that awaits both; it does not infer completion from close returning.

## Official server execution

### Start and ready signal

newOfficialServer:

1. acquires a Host slot and creates a unique workspace;
2. clones the default template or creates an empty FRESH workspace;
3. safely relocates the cloned default world for a validated level-name when requested;
4. writes current eula.txt and the complete sorted server.properties;
5. selects an ephemeral loopback port;
6. starts java -jar server.jar nogui with shutdown command stop;
7. concurrently monitors exit and performs a complete Status handshake plus pong;
8. retries only diagnosed bind failures within maximumBindAttempts;
9. returns OfficialMinecraftServer only after the Status probe succeeds.

The official server's ready signal remains network behavior, never Done log text. The returned endpoint belongs to the
server resource.

### Control during tests

MinecraftTestSupport.sendCommand writes one validated server-console line. Sending the line means only that stdin was
flushed. A scenario requiring command completion either:

- uses a command-and-await helper with an expected new server log marker;
- observes the resulting protocol packet or connection state;
- or, for filesystem behavior, stops the server and inspects the consistent workspace through the documented
  same-filesystem backdoor.

Typical controlled operations include save-all, stop, whitelist or operator setup for a dedicated scenario, data or
world commands, and other exact-version official commands. Tests keep command use narrow and deterministic.

restartServer synchronously:

1. sends stop to the current process;
2. requires exit code zero and output EOF;
3. retains the same private workspace;
4. chooses a new valid endpoint if necessary;
5. starts the server again;
6. waits for the full Status and pong ready signal before returning the updated endpoint.

Restart never silently reclones a template or resets the world.

### Clean and forced shutdown

Normal server shutdown sends stop and waits for process-stopped completion. Exit code zero is required when shutdown is
part of the test evidence or template generation.

If the server fails to stop within configuration.stopTimeout, runtime cleanup destroys the process tree, captures the
abnormal result, and still proceeds to directory and slot cleanup. Forced shutdown is never considered a successful
template producer or clean lifecycle assertion.

## Headless client execution

HeadlessMC and Minecraft run in the same JVM because the selected launch uses inmemory. Therefore OS process exit plus
output EOF is the definitive termination signal for both HeadlessMC and the client; the Host does not need to discover
or track an opaque child JVM.

### Start to title screen

newHeadlessClient:

1. acquires a Host slot and creates a unique workspace;
2. clones the sanitized client template or creates a FRESH game directory;
3. copies the immutable HMC-Specifics JAR into the private mods directory;
4. writes the requested offline player identity and deterministic options only in the private workspace;
5. gives HeadlessMC private writable home/configuration and game directories while keeping the assembled root read-only;
6. launches the exact Fabric profile with lwjgl, inmemory, and offline, without Quick Play;
7. waits for new output containing HMC-Specifics initialized!;
8. issues gui and waits for a new TitleScreen result through the serialized command path;
9. verifies that the process remains alive;
10. returns a HeadlessMinecraftClient containing only its Host-owned resource ID.

The old Launching with simple in-memory launcher marker is retained only as a diagnostic. It cannot make creation
succeed because it proves neither HMC-Specifics initialization nor title-screen availability.

### Connection initiation and Play evidence

connectHeadlessClient validates a loopback MinecraftTestEndpoint, records the output cursor, and sends:

~~~text
connect <host> <port>
~~~

It returns after new output contains the exact HMC-Specifics connection acknowledgement and the process is still alive.
This means that the main-thread vanilla ConnectScreen path accepted the request. It does not mean Login, Configuration,
or Play succeeded.

The split between creation and connection prevents a deadlock in the production-server scenario:

1. the production server binds first;
2. the fixture client reaches the title screen;
3. connectHeadlessClient initiates connection and returns before Play;
4. the test calls MinecraftServer.accept and services Login and Configuration;
5. server-observed packets prove Play.

The existing protocol-server scenario remains the Play oracle. It requires the real client to negotiate successfully and
observes such evidence as teleport confirmation, chunk-batch acknowledgement, keepalive response, client tick,
bidirectional Play packets, respawn, and reconfiguration. Do not replace those assertions with gui output or a client
log marker.

### Disconnect and reconnect

disconnectHeadlessClient sends disconnect through the correlated command path, then issues gui and waits for a stable
TitleScreen result while monitoring process exit. It returns only after the client has left the current connection and
is commandable at the menu.

This operation supports explicit connection-failure, disconnect-screen, and reconnect scenarios without restarting the
JVM. Reuse remains inside one ordered scenario and one test task. Do not pool that mutable client across independent
tests or task owners.

### Additional HMC-Specifics actions

Expose one narrow pass-through operation for concrete tests rather than modeling the whole upstream command set:

~~~text
sendHeadlessClientCommand(client, command, expectedNewOutput?, timeout?)
~~~

The Host validates a single line, captures an internal output cursor, serializes commands for that client, and returns
only after the optional marker appears in new output. Lifecycle verbs connect, disconnect, quit, stop, exit, and login
are rejected by this generic operation; their typed lifecycle APIs or the no-live-account policy own them.

Initially allow only audited test-action verbs:

- gui and render for screen or rendered-text observation;
- click, text, menu, and close for a concrete GUI-flow test;
- key for deterministic input that should produce observable protocol behavior;
- msg or . for a chat-message action;
- / for a server command action after the test server grants the required state.

Keep HMC runtime/reflection commands disabled and do not expose arbitrary JVM, filesystem, account, or launcher
configuration.

Examples of additional tests enabled without new fixture infrastructure:

| Scenario              | Client action                                                                       | Authoritative assertion                                                               |
|-----------------------|-------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| Chat packet           | msg or .                                                                            | Production server receives and validates the serverbound chat packet                  |
| Command packet        | /                                                                                   | Production server receives the command and exercises command suggestions or responses |
| Movement/input        | key                                                                                 | Production server observes the expected movement or player-action packets             |
| Container interaction | gui plus click                                                                      | Production server observes container click/close packets                              |
| Disconnect prompt     | connect to a controlled rejection then gui                                          | Server proves rejection packet; gui may confirm the visible result                    |
| Reconnect             | disconnect, then typed connect                                                      | Second server negotiation and packet evidence complete                                |
| Clientbound UI        | server sends title, resource-pack, or other modeled packets; render or gui observes | Packet send is primary; HMC output is supplemental UI evidence                        |

Do not add all these cases merely because the commands exist. Add a typed helper or scenario only when it closes a
specific protocol coverage gap. A self-reported HMC string is sufficient for UI-specific observation but never replaces
wire evidence for protocol behavior.

### Clean and forced shutdown

Normal client shutdown uses the typed close path:

1. prevents new commands for the resource;
2. records the current output cursor;
3. sends quit to HMC-Specifics;
4. waits for new output containing Quitting Minecraft...;
5. waits for the in-memory HeadlessMC/Minecraft JVM to exit;
6. waits for output EOF;
7. requires exit code zero for a clean stop;
8. deletes the workspace and releases the slot when full cleanup is requested.

The selected upstream implementation schedules normal quit on Minecraft's thread and calls Minecraft.stop. The Host does
not use System.exit as its normal path.

If HMC-Specifics never initializes, its command reader fails, Minecraft crashes, or quit times out, runtime cleanup
forcibly terminates the process tree and retains bounded diagnostics. That fallback releases resources but does not turn
the run into a clean lifecycle result and cannot validate a client template.

## Public fixture model

Keep minecraft-test-support as the only dependency visible to consuming tests. It contains serializable values and the
kRPC client, not HMC, Fabric, process, Gradle, or filesystem implementations.

Target resource values:

- MinecraftTestResource contains only id.
- OfficialMinecraftServer contains id and its listening endpoint.
- HeadlessMinecraftClient contains only id.

Target configurations:

- OfficialMinecraftServerConfiguration retains properties, startup and stop timeouts, bind attempts, and adds the typed
  TEMPLATE or FRESH workspace policy.
- HeadlessMinecraftClientConfiguration contains offline player identity, startup and stop limits, and TEMPLATE or FRESH
  workspace policy. It contains no endpoint, artifact path, launcher flag, HMC command, or mod object.

Target operations and their exact meaning:

| Operation                    | Completion meaning                                                                  |
|------------------------------|-------------------------------------------------------------------------------------|
| newOfficialServer            | Complete Status response and pong succeeded                                         |
| newHeadlessClient            | HMC-Specifics initialized and gui observed TitleScreen                              |
| connectHeadlessClient        | Vanilla connection initiation command was accepted; not Play                        |
| disconnectHeadlessClient     | gui observed TitleScreen after disconnect                                           |
| sendCommand(server, command) | One server-console line was flushed                                                 |
| sendHeadlessClientCommand    | One allowed action was flushed and optional new output was observed                 |
| status or isAlive            | Current OS-process observation                                                      |
| logText                      | Current bounded Host-owned diagnostic text                                          |
| waitForLog                   | Matching diagnostic output, with command helpers using an internal post-send cursor |
| closeProcess                 | Process exited and output reached EOF; workspace retained                           |
| awaitExit                    | Observed exit without requesting it                                                 |
| restartServer                | Old server stopped cleanly and new server passed Status readiness                   |
| hostWorkingDirectory         | Same-host absolute-path backdoor, only for documented filesystem scenarios          |
| deleteWorkingDirectory       | Stopped workspace deleted and slot released                                         |
| close                        | Idempotent asynchronous process-and-directory cleanup request                       |

Provide a structured close-and-await helper for callers that need resource-fully-released semantics. Ordinary use may
retain the existing asynchronous close plus task-owner fallback.

No HMC-Specifics object, Fabric object, launch profile, process handle, PID, template path, control cursor, or arbitrary
JVM argument crosses kRPC.

## Standard test flows

### Production client against official server

The protocol-client commonTest scenario:

1. requests an official server;
2. receives an endpoint only after Status readiness;
3. exercises Status, Login, Configuration, compression, and Play through MinecraftClient;
4. uses official server commands only when required by the scenario;
5. closes the production connection;
6. stops and releases the official server through structured cleanup.

This flow does not involve HeadlessMC or HMC-Specifics.

### Headless client against production server

The protocol-server commonTest scenario:

1. binds MinecraftServer on loopback;
2. creates a title-ready HeadlessMinecraftClient;
3. explicitly connects it to the production endpoint;
4. accepts and services the real vanilla connection;
5. proves Play and further behavior from packets observed by the production server;
6. optionally uses allowed HMC-Specifics actions for a concrete additional packet path;
7. disconnects or quits normally;
8. captures bounded HMC/client logs on failure and fully releases the resource.

HMC-Specifics coordinates actions. It is not the protocol oracle.

### Official codec and serialization

Codec differential fixtures continue to use the exact extracted official runtime and compiled bridge. Their Gradle
inputs do not depend on either server or client templates. Network serialization scenarios that need an official server
use the ordinary official-server flow and Status readiness.

### Official world storage

The world-io scenario explicitly selects a FRESH official-server workspace because first-run world generation is part of
its evidence. It may issue deterministic console commands, save, stop, inspect the Host-owned directory, rewrite data,
restart the same server workspace, and require Status readiness again.

Before opening world files it calls closeProcess and requires a clean exit. After filesystem assertions it calls
deleteWorkingDirectory or the full close-and-await helper. Thin annotated entries remain only in standard source sets
that share the Host filesystem namespace.

## Failure handling and diagnostics

Every readiness or command wait fails on the earliest of:

- expected new output or network evidence;
- process exit;
- output-reader failure;
- caller cancellation;
- configured timeout.

Failures include:

- resource kind and operation;
- selected Minecraft, HeadlessMC, Fabric Loader, and HMC-Specifics identities where relevant;
- whether the process exited and its code;
- the bounded output observed after the operation cursor when available;
- the tail of the complete bounded process log;
- the last Status or bind failure for an official server.

Never include account tokens or unredacted secrets. Offline fixtures must not create them.

Do not write successful standalone report files. Logs remain Host-owned and cross kRPC only when requested or attached
to an exception.

## Implementation sequence

1. **Update terminology and target metadata**
    - Add HmcSpecificsTarget with the exact audited asset identity.
    - Remove every planned minecraft-test-fixture-client, Loom, mappings, Fabric API, remap, custom control protocol,
      and Client GameTest requirement.
    - Apply headless-client and external-fixture naming without weakening official-server evidence terminology.

2. **Normalize the existing Gradle vocabulary**
    - Perform the previously planned task and build-logic renames.
    - Make every prepare task actionless.
    - Keep analysis and codec producers connected directly to the raw verified server.
    - Add downloadHmcSpecifics as a verified producer and no new public gate.

3. **Generate the official server template**
    - Keep deterministic properties, Status readiness, normal stop, strict sanitization, manifest, clone isolation, and
      FRESH policy.
    - Keep world-io on FRESH and other compatible server scenarios on TEMPLATE.

4. **Assemble the upstream-controlled headless client**
    - Resolve the pinned Fabric profile and libraries.
    - Assemble Mojang client inputs, filtered assets, HeadlessMC, and the verified HMC-Specifics JAR.
    - Keep automatic launcher downloads disabled.
    - Record every identity and relative path in the client manifest.

5. **Generate the client template**
    - Use HMC-Specifics initialized output plus gui TitleScreen as readiness.
    - Use quit plus exit code zero and output EOF as clean-stop evidence.
    - Sanitize and prove immutable-input isolation.

6. **Consolidate Build Service inputs**
    - Replace separate client-cache, version metadata, and launcher parameters with one immutable headless-client root.
    - Add the official-server template root.
    - Update Host argument/layout validation and exact test-task inputs together.

7. **Implement correlated process commands**
    - Add the internal monotonically increasing output sequence and post-send matching.
    - Serialize commands per resource.
    - Test stale-marker rejection, process-exit races, output EOF, graceful timeout, and forced fallback.

8. **Migrate server and client runtime APIs**
    - Add workspace policies.
    - Move endpoint ownership to OfficialMinecraftServer only.
    - Rename newOfficialClient to newHeadlessClient.
    - Add typed connect and disconnect operations.
    - Add the narrow allowed-action HMC-Specifics pass-through.
    - Make normal client cleanup use quit and full exit validation.

9. **Migrate scenarios and documentation**
    - Keep protocol readiness packet-based.
    - Rename client tests and capability filters so unsupported targets do not rely on the word Official.
    - Update root and module guides, READMEs, KDoc, diagnostics, and task descriptions.
    - Remove all stale custom-mod and structured-lifecycle language.

## Required guide changes

When this plan is implemented, update the applicable AGENTS.md files in English and merge the rules into existing
sections rather than duplicating them.

### Root guide

- Use external fixture or external peer for rules covering both fixture kinds; retain official server only for Mojang
  server evidence.
- Describe the headless client as Mojang client artifacts plus Fabric Loader, upstream HMC-Specifics, HeadlessMC, and
  verified resources.
- State that HMC-Specifics controls lifecycle and actions but server-observed packets remain protocol evidence.
- Add immutable template clone and FRESH workspace rules.
- Preserve the narrow exception allowing only the declared server-template producer to execute the official server JAR
  outside official analysis.
- Do not add minecraft-test-fixture-client to repository architecture.

### buildSrc guide

- Preserve semantic download, generate, assemble, and actionless prepare verbs.
- Name prepareOfficialMinecraftServer, prepareHeadlessClient, prepareOfficialMinecraftCodecOracle, and
  prepareMinecraftTestFixtureHostRuntime as the stable gates.
- State that prepareHeadlessClient covers the verified upstream HMC-Specifics artifact rather than a local mod.
- Require immutable HMC-Specifics size and SHA verification because the upstream latest tag is mutable.
- Preserve lazy external-fixture capability wiring and capability-specific unsupported-target filtering.

### Fixture Host guide

- State that the Host consumes immutable server and client roots and never repairs them.
- Define server ready as complete Status plus pong.
- Define client command readiness as HMC-Specifics initialization plus gui TitleScreen.
- Define Play only through production-server packet evidence.
- Define normal client stop as HMC-Specifics quit plus process exit, output EOF, and code zero.
- Preserve forced process-tree termination solely as fallback.
- Document post-send output correlation, process-stopped versus resource-fully-released completion, and private
  workspace isolation.

### Test-support guide

- Expose HeadlessMinecraftClient rather than an official-client claim.
- Keep Host paths, Fabric, HMC-Specifics, HeadlessMC, processes, and launch flags behind kRPC.
- Document title-ready creation, explicit connect, optional disconnect, narrow action commands, and exact close
  semantics.
- Keep the Host working-directory backdoor limited to same-filesystem storage tests.

### protocol-server guide

- Describe the external headless client and its upstream HMC-Specifics coordination.
- Require packet-observed Play and behavior rather than HMC text.
- Keep the scenario in commonTest and apply stable capability filtering on unsupported targets.

### world-io guide

- Require FRESH official-server creation for first-generation storage evidence.
- Require a synchronously stopped server before filesystem access and complete cleanup afterward.

## Verification to perform during implementation

Do not execute these checks merely while editing this plan. They are future implementation gates.

1. Download HMC-Specifics from the declared URL and verify exact size and SHA-256.
2. Inspect the selected JAR metadata and matching source for Minecraft/Fabric compatibility, vanilla connect,
   disconnect, quit, and relevant network-boundary changes.
3. Run prepareOfficialMinecraftServer and prepareHeadlessClient.
4. Repeat both gates unchanged and confirm up-to-date or build-cache reuse.
5. Run both gates twice with configuration cache and confirm store then reuse.
6. Confirm analysis, runtime extraction, and codec-only requests do not schedule server-template generation.
7. Confirm no task or dependency references minecraft-test-fixture-client, Loom, mappings, Fabric API, or remapJar.
8. Inspect both template manifests and sanitizers for endpoint, identity, token, log, lock, path, and mutable-release
   leakage.
9. Verify client template generation observes HMC-Specifics initialization, gui TitleScreen, quit, output EOF, and code
   zero.
10. Verify server template generation observes Status plus pong, stop, output EOF, and code zero.
11. Run minecraft-test-fixture-host tests for command serialization, stale-output rejection, clean exit, forced
    fallback, slot release, template isolation, and Host shutdown.
12. Run minecraft-test-support JVM serialization and lifecycle tests.
13. Run protocol-server JVM tests and require the existing packet probes through the HMC-Specifics-controlled client.
14. Run protocol-client, protocol-serialization, and world-io affected JVM tests.
15. Run the repository JVM gate, then applicable standard platform tasks.
16. Inspect published runtime classpaths and confirm no fixture, Fabric, HMC-Specifics, HeadlessMC, or Host dependency
    enters a published module.

## Completion criteria

The implementation is complete only when:

- no repository-owned Minecraft client mod, Loom build, mappings input, remap task, private mod socket, or structured
  mod lifecycle protocol exists;
- the exact HMC-Specifics bytes are verified and represented in the client manifest;
- the existing server and client preparation ownership remains precise and every prepare task is actionless;
- official analysis and codec preparation remain independent of the server template;
- every runtime uses a unique private workspace and immutable templates are never launched or mutated in place;
- official-server creation returns only after Status and pong;
- headless-client creation returns only after HMC-Specifics initialization and gui TitleScreen;
- connection initiation returns before Play and Play is proven by production-server packet evidence;
- normal server and client shutdown require exit, output EOF, and code zero;
- forced termination is reported as abnormal and used only to guarantee cleanup;
- callers can distinguish process-stopped from resource-fully-released completion;
- optional HMC-Specifics actions can drive concrete additional scenarios without exposing launcher, process, filesystem,
  account, or reflection control;
- unsupported standard targets exclude external-fixture scenarios through capability-specific stable patterns;
- all focused JVM, cache-reuse, configuration-cache, and applicable platform checks pass; and
- no generated template, downloaded artifact, runtime workspace, or research checkout is committed.
