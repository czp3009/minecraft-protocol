# minecraft-protocol

> This is a very early-stage experimental project; please do not use it in a production environment.

`minecraft-protocol` is an idiomatic Kotlin Multiplatform library for the Minecraft Java Edition protocol and world
storage formats. It provides `kotlinx.serialization` packet models and codecs, typed vanilla Configuration data, Ktor
transport and sessions, authentication helpers, client/server connection orchestration, binary NBT, and Anvil world I/O.

The implemented release and protocol number are exposed by `MinecraftProtocol` in `protocol-model`. Deterministic
version-dependent analysis and generated source stay under Gradle-managed `build/` directories.

This is infrastructure for Minecraft applications, not a complete gameplay server. `protocol-server` can admit an
offline client through Play and project a finite initial set of chunks and entities; authoritative worlds, ticking, game
rules, persistence policy, and gameplay remain application concerns.

## Requirements

- A JDK with Java major version 25 or newer, with its `java` command available on `PATH`. The project deliberately
  standardizes its Gradle JVM toolchain and JVM/Android bytecode target on Java 25 as one convenient, uniform baseline.
- An Android SDK configured through the usual Gradle mechanisms, including `local.properties`, when running the full
  multiplatform gate.
- Network access on the first exhaustive verification run so Gradle can acquire normal build dependencies and standard
  tests can acquire hash-verified Minecraft reference artifacts.

Use the checked-in wrapper; a separate Gradle installation is unnecessary. The configured Java 25 toolchain and bytecode
target are a project policy for convenience and consistency, not a claim that the Kotlin sources intrinsically depend on
Java 25-only APIs. Standard tests launch official Minecraft processes through the `java` command on `PATH`; that command
may report any Java major version of 25 or newer, and no exact minor or patch release is required. Gradle provisions the
Node/Yarn and Kotlin Native tooling used by the non-JVM targets. JS and Wasm tests run with Gradle-provisioned Node/D8
and do not require a machine-installed browser. The official-client E2E does not require Minecraft, a launcher, an
account, a display server, or a manually started process.

## Modules

| Module                   | Purpose                                                                                         |
|--------------------------|-------------------------------------------------------------------------------------------------|
| `compression`            | Portable raw DEFLATE shared by network zlib and world-storage zlib/gzip                         |
| `nbt`                    | Named and unnamed binary NBT over `kotlinx.io`, including modified UTF and hostile-input limits |
| `protocol-model`         | Packet payloads, shared values, sealed variants, and logical serializers                        |
| `protocol-serialization` | `MinecraftFormat`, physical wire rules, and packet lookup by state/direction/ID                 |
| `protocol-vanilla-data`  | Typed Known Packs, feature flags, registries, tags, and finite vanilla catalogues               |
| `protocol-transport`     | Ktor sockets, VarInt21 framing, zlib compression, and AES/CFB8 encryption                       |
| `protocol-session`       | Typed packet dispatch, direction checks, and protocol-state transitions                         |
| `protocol-auth`          | Offline identities, session-service calls, server hashes, and cryptography                      |
| `protocol-client`        | Status, Login/Configuration, Play context, and direct Ktor client API                           |
| `protocol-server`        | Connection orchestration and finite initial chunk/entity projection                             |
| `world-format`           | Filesystem-independent Anvil regions, compression modes, external chunks, and NBT composition   |
| `world-io`               | World paths, standalone NBT, and atomic chunk/entity/POI region storage                         |

The published modules above are the runtime library layer. Root official-analysis tasks first turn the matching JAR into
Gradle-managed data artifacts. Non-source-driven generators are cacheable task types in `buildSrc`, registered only by
the runtime module that owns their generated source, while source-to-source generation uses the private
`protocol-symbol-processor` KSP module and its standard output. None of this build preparation is part of the published
runtime API.

`minecraft-test-support` is a private, unpublished Kotlin Multiplatform fixture library used only by repository tests.
Its ordinary resource APIs acquire and verify official artifacts, allocate unique workspaces and endpoints, wait for
readiness, retain bounded logs, and close directly owned peer processes from standard JVM, desktop Native, and Node
tests; no Gradle preparation task or command-line helper is involved.

`nbt` and `world-format` expose `Source`/`Sink` APIs on stream-capable targets. `world-io` targets JVM, Android, and
Native platforms with filesystem support; browser-like consumers use the stream modules directly. Portable JS/Wasm tests
load complete in-memory region byte arrays and streams through compression into chunk NBT under Node/D8. They do not
assume browser filesystem or listening-server support.

## Encoding packet payloads

```kotlin
val encoded = MinecraftPacketRegistry.encodePayload(packet)

val decoded = MinecraftPacketRegistry.decodePayload(
    state = encoded.key.state,
    direction = encoded.key.direction,
    id = encoded.key.id,
    payload = encoded.payload,
)
```

Use a configured `MinecraftFormat` for values whose physical representation depends on negotiated connection context,
such as chunk section counts. The client and server module READMEs contain their higher-level connection examples.

## Integrating `server.properties`

This library deliberately does not read or own `server.properties`: a complete server may use that file, another
configuration format, or dynamic administration. It does expose the protocol and storage controls needed to map the
official settings without patching library internals:

- `MinecraftServer.bind` controls the Minecraft bind address and port.
- `MinecraftServerConfiguration` controls Status availability, transfer admission, proxy-aware session verification,
  authentication mode, compression, player-count metadata, distances, MOTD, hardcore, game mode, difficulty, and the
  secure-chat claim.
- `MinecraftServerHandler` controls status JSON, profile admission, per-player Play Login, and optional Configuration
  packets such as resource packs, server links, cookies, custom payloads, and a code of conduct. Client responses before
  Finish Configuration are delivered back to the handler; ordered `configurationTasks` can hold Play entry until a code
  of conduct or required resource pack reaches the application's accepted terminal state.
- `MinecraftInitialWorld` makes difficulty, lock state, player abilities, chunks, and entities explicit. In particular,
  `allow-flight` is an application-side movement policy; it must not be confused with granting flight abilities.
- `MinecraftWorldPaths` accepts an arbitrary world root, while `WorldRegionStore.writeChunkNbt` accepts the region
  compression mode.

The consuming full server still owns gameplay and operations settings: whitelist and operator data, spam/idle/rate
limits, permissions, world generation, ticking and watchdogs, entity tracking, spawn protection, text filtering, Query,
RCON, JMX, and the JSON-RPC management service. These are not silently emulated, but the library does not install fixed
values that prevent the application from implementing them.

For example, a consuming adapter would map a negative `network-compression-threshold` to
`compressionThreshold = null`; all non-negative values map directly. Set `enforcesSecureChat` only after the consuming
server really validates secure profiles and signed chat.

## Verification

Use the affected module's standard JVM test task for the normal development loop:

```shell
./gradlew :protocol-serialization:jvmTest
```

For a repository-wide JVM pass, use Gradle's standard task selector:

```shell
./gradlew jvmTest
```

After the JVM path is stable, select every module's standard Kotlin Multiplatform aggregate with:

```shell
./gradlew allTests
```

On Windows replace `./gradlew` with `.\gradlew.bat`. These are standard KMP tasks and task selectors; the root project
does not define an additional `test` task. Together the applicable platform suites cover:

- shared, JVM, Android host, JS, Wasm, and host-supported Native tests;
- model invariants, primitive/composite codecs, golden payloads, malformed input, and registry-wide round trips;
- framing, partial I/O, compression, encryption, sessions, authentication, and production Ktor sockets;
- direct execution of official packet codecs;
- production-client interoperability with the matching official server;
- a matching official client against the production server, headlessly and in offline mode;
- official-server world generation, library decode/rewrite, and official-server reload.

The official-client JVM test calls the private test-support library, which downloads the exact Mojang client, libraries,
natives, and assets into `build/`, validates the published sizes and hashes, and launches it through a pinned
SHA-256-verified HeadlessMC adapter. The test verifies
Status/Login/Configuration/Play, initial chunks and entities, teleport/chunk-batch/player-loaded acknowledgements,
client ticks and keepalives, broad clientbound Play packet families, cookies and pings, Respawn followed by another
world projection, a Play-to-Configuration round trip, and a third Play/world synchronization. All services are started
and stopped by standard JVM tests. GUI client testing is not part of the repository.

The first run is intentionally heavier. Production tasks and the private test-support library key the verified server,
client, libraries, assets, reports, generated sources, and test outputs by the selected Minecraft version, so unchanged
follow-up runs avoid downloads and expensive generation.

Examples of focused JVM suites are:

```shell
./gradlew :protocol-serialization:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
./gradlew :world-io:jvmTest
```

Only after JVM verification is stable should you run the applicable platform tasks or `allTests`. All generated source,
servers, clients, worlds, logs, reports, downloads, and process working directories remain under `build/`.

## Source authority

Wire and storage behavior follows the matching official server JAR. The revision-matched Minecraft Wiki is the secondary
descriptive source; exact-version MCProtocolLib and Minestom are auxiliary references in that order. Nullability follows
the same evidence order, with unresolved values kept nullable and marked `@UnknownNullability`.

[
`MinecraftTarget.MINECRAFT_VERSION`](buildSrc/src/main/kotlin/com/hiczp/minecraft/protocol/buildScript/MinecraftTarget.kt)
is the single manually selected Minecraft release. Print it with:

```shell
./gradlew -q minecraftVersion
```

Gradle downloads that official server and runs a root official-analysis task group. Each analyzer owns one distinct
subdirectory below `build/generated/official-minecraft/<version>/`: target facts, official data-generator reports, or a
complete capture of both Configuration Known Packs branches. These outputs are exposed as Gradle artifacts.

The owning runtime modules consume those artifacts through cacheable data-to-source tasks; those generators never read
the official JAR. KSP uses its standard generated-source location for dispatch tables derived from Kotlin annotations.
Published source JARs include all generated Kotlin. No target-dependent JSON or generated Kotlin is checked into the
source tree.

## Updating for a Minecraft release

Change `MinecraftTarget.MINECRAFT_VERSION`, then let the normal build regenerate what its task graph needs. To run the
whole official-analysis layer explicitly:

```shell
./gradlew officialMinecraftAnalysis
```

Update hand-modeled semantics that cannot be derived mechanically, run affected `jvmTest` tasks, then run the applicable
standard platform tests or `./gradlew allTests`. Gradle derives reuse entirely from declared inputs, outputs, task
implementation, and artifact provenance; there is no refresh/copy/freshness-comparison workflow.

The playbooks indexed in `.agents/skills/README.md` are optional instructions for coding agents performing the same
human development work. They may call Gradle, but Gradle never reads those skills or their scratch output; deleting the
skill directory does not change the project.
