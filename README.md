# minecraft-protocol

`minecraft-protocol` is an idiomatic Kotlin Multiplatform library for the Minecraft Java Edition protocol and world
storage formats. It provides kotlinx.serialization packet models and codecs, typed vanilla Configuration data, Ktor
transport and sessions, authentication helpers, client/server connection orchestration, binary NBT, and Anvil world I/O.

The implemented release and protocol number are exposed by `MinecraftProtocol` in `protocol-model`. Checked-in
version-dependent evidence lives in `protocol-specification`.

This is infrastructure for Minecraft applications, not a complete gameplay server. `protocol-server` can admit an
offline client through Play and project a finite initial set of chunks and entities; authoritative worlds, ticking, game
rules, persistence policy, and gameplay remain application concerns.

## Requirements

- JDK 25. Gradle and every JVM/Android compilation target use Java 25.
- An Android SDK configured through the usual Gradle mechanisms, including `local.properties`, when running the full
  multiplatform gate.
- Network access on the first exhaustive verification run so Gradle can acquire normal build dependencies and standard
  JVM tests can acquire hash-verified Minecraft reference artifacts.

Use the checked-in wrapper; a separate Gradle installation is unnecessary. Gradle provisions the Node/Yarn and Kotlin
Native tooling used by the non-JVM targets. The checked-in daemon JVM criteria selects a discoverable JDK 25, so
`JAVA_HOME` does not need to point at it explicitly. JS and Wasm tests run with Gradle-provisioned Node/D8 and do not
require a machine-installed browser. The official-client E2E does not require Minecraft, a launcher, an account, a
display server, or a manually started process.

## Modules

| Module                   | Purpose |
|--------------------------|---|
| `compression`            | Portable raw DEFLATE shared by network zlib and world-storage zlib/gzip |
| `nbt`                    | Named and unnamed binary NBT over `kotlinx.io`, including modified UTF and hostile-input limits |
| `protocol-model`         | Packet payloads, shared values, sealed variants, and logical serializers |
| `protocol-serialization` | `MinecraftFormat`, physical wire rules, and packet lookup by state/direction/ID |
| `protocol-vanilla-data`  | Typed Known Packs, feature flags, registries, tags, and finite vanilla catalogues |
| `protocol-transport`     | Ktor sockets, VarInt21 framing, zlib compression, and AES/CFB8 encryption |
| `protocol-session`       | Typed packet dispatch, direction checks, and protocol-state transitions |
| `protocol-auth`          | Offline identities, session-service calls, server hashes, and cryptography |
| `protocol-client`        | Status, Login/Configuration, Play context, and direct Ktor client API |
| `protocol-server`        | Connection orchestration and finite initial chunk/entity projection |
| `world-format`           | Filesystem-independent Anvil regions, compression modes, external chunks, and NBT composition |
| `world-io`               | World paths, standalone NBT, and atomic chunk/entity/POI region storage |

The published modules above are the runtime library layer. Build-time preparation has two mechanisms:
non-source-driven generators are cacheable task types in `buildSrc`, registered only by the module that owns their
output, while source-to-source generation uses the private `protocol-symbol-processor` KSP module. Neither mechanism is
part of the published runtime API.

`minecraft-test-support` is a private, unpublished JVM fixture library used only by repository tests. Calling it from a
standard `jvmTest` acquires and verifies official artifacts and manages external test processes without adding Gradle
preparation tasks or command-line helper applications.

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

The official default inventory is generated into
[`protocol-specification/generated/server-properties.json`](protocol-specification/generated/server-properties.json)
for review. It is evidence, not a runtime configuration file. For example, a consuming adapter would map a negative
`network-compression-threshold` to `compressionThreshold = null`; all non-negative values map directly. Set
`enforcesSecureChat` only after the consuming server really validates secure profiles and signed chat.

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
SHA-256-verified HeadlessMC adapter. It verifies
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

[`MinecraftTarget.version`](buildSrc/src/main/kotlin/com/hiczp/minecraft/protocol/buildScript/MinecraftTarget.kt) is the
single manually selected Minecraft release. Print it with:

```shell
./gradlew -q minecraftVersion
```

Gradle downloads that official server, reads its `version.json`, runs its data generator and codecs, captures its
Configuration packets, and generates runtime Kotlin under module `build/generated` directories. KSP generates the
dispatch tables derived from Kotlin annotations; cacheable `buildSrc` tasks own generation driven by official non-source
inputs. Published source JARs include those generated files. The checked-in `protocol-specification/generated`
directory contains canonical official evidence for reviewing release diffs; its hand-written README describes those
files. Compilation, tests, and production code do not read it.

## Updating for a Minecraft release

Change `MinecraftTarget.version`, then regenerate the deterministic evidence:

```shell
./gradlew refreshProtocolSpecification
```

Review the specification diff, update hand-modeled semantics that cannot be derived mechanically, run affected
`jvmTest` tasks, then run the applicable standard platform tests or `./gradlew allTests`. Root `clean` preserves the
hand-written overview and checked-in generated evidence; only `refreshProtocolSpecification` replaces
`protocol-specification/generated`.

The playbooks indexed in `.agents/skills/README.md` are optional instructions for coding agents performing the same
human development work. They may call Gradle, but Gradle never reads those skills or their scratch output; deleting the
skill directory does not change the project.
