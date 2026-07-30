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
- Network access on the first exhaustive verification run so Gradle can acquire normal build dependencies and
  hash-verified Minecraft reference artifacts.

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

The exhaustive, version-bound decision for every property generated by the matching official server is checked in at
[
`protocol-specification/server-properties-compatibility.json`](protocol-specification/server-properties-compatibility.json).
`checkOfficialServerPropertiesCompatibility` starts the verified official server in settings-initialization mode under
`build/`, regenerates the inventory, and fails on any unreviewed property or default-value drift. For example, a
server-properties adapter would map a negative `network-compression-threshold` to `compressionThreshold = null`; all
non-negative values map directly. Set `enforcesSecureChat` only after the consuming server really validates secure
profiles and signed chat.

## Verification

From the repository root, the single canonical command is:

```shell
./gradlew test
```

On Windows use `.\gradlew.bat test`. It runs every module's multiplatform `check` task and the complete deterministic
Minecraft library gate. That includes:

- shared, JVM, Android host, JS, Wasm, Linux Native, and host-supported native compilation/tests;
- model invariants, primitive/composite codecs, golden payloads, malformed input, and registry-wide round trips;
- framing, partial I/O, compression, encryption, sessions, authentication, and production Ktor sockets;
- finite-registry, nullability, source-freshness, official packet-codec, and exhaustive `server.properties`
  compatibility audits;
- production-client interoperability with the matching official server;
- a matching official client against the production server, headlessly and in offline mode;
- official-server world generation, library decode/rewrite, and official-server reload.

The official-client test downloads the exact Mojang client, libraries, natives, and assets into `build/`, validates the
published sizes and hashes, and launches it through a pinned SHA-256-verified HeadlessMC adapter. It verifies
Status/Login/Configuration/Play, initial chunks and entities, teleport/chunk-batch/player-loaded acknowledgements,
client ticks and keepalives, broad clientbound Play packet families, cookies and pings, Respawn followed by another
world projection, a Play-to-Configuration round trip, and a third Play/world synchronization. Its JSON report records
the exact live packet types and separates business probes from liveness-barrier traffic. All services are started and
stopped by Gradle.

The first run is intentionally heavier; verified artifacts are reused from `build/`. The optional
`officialClientToServerEndToEndTest` launches the desktop client directly and is not part of `test`, because it requires
a graphical environment.

Useful focused commands while developing are:

```shell
./gradlew buildLogicTest
./gradlew protocolLayeredTest
./gradlew protocolJvmTest
./gradlew worldStorageLayeredTest
./gradlew headlessOfficialClientToServerEndToEndTest
./gradlew officialServerInteropTest
./gradlew officialWorldStorageInteropTest
./gradlew verifyProtocolUpdate
./gradlew verifyWorldStorageUpdate
./gradlew verifyMinecraftLibrary
```

All generated servers, clients, worlds, logs, reports, downloads, and process working directories remain under
`build/`.

## Source authority

Wire and storage behavior follows the matching official server JAR. The revision-matched Minecraft Wiki is the secondary
descriptive source; exact-version MCProtocolLib and Minestom are auxiliary references in that order. Nullability follows
the same evidence order, with unresolved values kept nullable and marked `@UnknownNullability`.

Refresh and verification tasks derive changing versions, IDs, inventories, hashes, and evidence rather than embedding
them in documentation.

## Updating for a Minecraft release

The actual development workflow is the source tree plus Gradle. Start with the refresh/preparation task for the area,
make the required code and evidence changes, and finish with the corresponding aggregate:

```shell
./gradlew refreshProtocolSpecification
./gradlew prepareProtocolUpdate
./gradlew verifyProtocolUpdate

./gradlew prepareWorldStorageUpdate
./gradlew verifyWorldStorageUpdate
```

Run refresh and preparation as separate invocations so preparation consumes the newly written target snapshot. A pinned
target can be passed to refresh as `-PprotocolTarget=<release>` or `-PprotocolTarget=protocol:<id>`.

The playbooks indexed in `.agents/skills/README.md` are optional instructions for coding agents performing the same
human development work. They may call Gradle, but Gradle never reads those skills or their scratch output; deleting the
skill directory does not change the project.
