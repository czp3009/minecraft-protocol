# minecraft-protocol

An idiomatic Kotlin Multiplatform implementation of the Minecraft Java Edition protocol. It includes
kotlinx.serialization-based packet models and codecs, official-derived Configuration data, Ktor transport, typed
sessions, authentication helpers, client/server connection orchestration, binary NBT, and Anvil world-file support.

The implemented target is exposed by `MinecraftProtocol` in
`protocol-model`. Version-dependent source evidence is stored separately in
`protocol-specification`.

## Modules

| Module                   | Purpose                                                                                                                    |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `nbt`                    | Named and unnamed binary NBT over `kotlinx.io` streams, with modified UTF and hostile-input limits                         |
| `protocol-model`         | Packet payloads, shared protocol values, sealed variants, and logical serializers                                          |
| `protocol-serialization` | `MinecraftFormat`, physical wire rules, and packet lookup by state, direction, and ID                                      |
| `protocol-vanilla-data`  | Typed block/entity catalogues, Known Packs, feature flags, registries, and tags captured from the matching official server |
| `protocol-transport`     | Ktor sockets, VarInt21 framing, zlib compression, and AES/CFB8 encryption                                                  |
| `protocol-session`       | Typed packet dispatch, direction checks, and protocol state transitions                                                    |
| `protocol-auth`          | Offline identities, session-service calls, server hashes, and cryptography abstractions                                    |
| `protocol-client`        | Status, Login/Configuration, Play-context, and direct Ktor socket client API                                               |
| `protocol-server`        | Connection orchestration plus finite initial chunk/entity projection with direct Ktor socket access                        |
| `world-format`           | Filesystem-independent Anvil regions, vanilla compression modes, external chunks, and NBT composition                      |
| `world-io`               | `kotlinx.io.files` paths, standalone NBT files, and atomic chunk/entity/POI region storage                                 |

`protocol-vanilla-data` is compiled Kotlin protocol data rather than a runtime Datapack or gameplay implementation. The
server module can project an initial flat chunk set and entity snapshots into Play packets, then returns the live
session to its caller. Authoritative worlds, ticking, persistence, and gameplay remain application concerns.

`nbt` and `world-format` expose `Source`/`Sink` APIs on stream-capable targets.
`world-io` is published for JVM, Android, and Native targets with a supported filesystem. Browser-like JS consumers use
the stream modules directly.

## Source authority

The matching official server JAR is the primary source and behavioral authority. The Minecraft Wiki is the secondary
descriptive source; exact-version MCProtocolLib and Minestom are auxiliary references in that order. Target discovery
may use the Wiki's current stable release, but every implemented wire rule is checked against the matching official JAR.

Nullability is determined from official codecs, constructors, access paths, annotations, optionals, and sentinels first.
If official code is inconclusive, the fallback order is Wiki, MCProtocolLib, then Minestom. Unresolved values remain
nullable and carry `@UnknownNullability`.

## Encoding a packet payload

```kotlin
val encoded = MinecraftPacketRegistry.encodePayload(packet)

val decoded = MinecraftPacketRegistry.decodePayload(
    state = encoded.key.state,
    direction = encoded.key.direction,
    id = encoded.key.id,
    payload = encoded.payload,
)
```

Use a configured `MinecraftFormat` when decoding values that depend on active connection context, such as chunk section
counts.

## Verification

```powershell
.\gradlew.bat buildLogicTest
.\gradlew.bat protocolJvmTest
.\gradlew.bat protocolLayeredTest
.\gradlew.bat checkOfficialNetworkRegistries
.\gradlew.bat checkOfficialCodecConformance
.\gradlew.bat officialServerInteropTest
.\gradlew.bat verifyProtocolUpdate
.\gradlew.bat verifyWorldStorageUpdate
.\gradlew.bat verifyMinecraftLibrary
.\gradlew.bat headlessOfficialClientToServerEndToEndTest
.\gradlew.bat officialClientToServerEndToEndTest
```

`buildLogicTest` verifies the deterministic parsers, hashes, atomic writes, and path checks used by `buildSrc`.
`protocolJvmTest` and `worldStorageJvmTest` include it. The JVM and layered suites are display-free and do not read a
Minecraft installation, launcher profile, account, or machine-specific path.

The official-server gate exercises both low-level codecs and the production Ktor client through Status, Login,
Configuration, compression, and Play. The headless official-client gate downloads and verifies the matching client,
libraries, natives, assets, and a pinned HeadlessMC launcher under `build/`. It launches an isolated offline profile
without a display server, connects it to the production server, and requires chunk/entity synchronization, teleportation
and chunk-batch acknowledgements, client ticks, and a Play KeepAlive round trip. `verifyProtocolUpdate` includes this
headless gate. It does not read a launcher installation or use Minecraft account credentials.

`officialClientToServerEndToEndTest` runs the same scenario by launching the desktop client directly. It is an
additional graphical-environment acceptance test and is not required by the headless CI aggregate.

The world-storage interoperability gate asks the exact official server to generate a world, rewrites its standalone NBT
and region containers through this library, then requires the official server to load and save those files.

Override the prepared client directory or analysis Java for diagnostics:

```powershell
.\gradlew.bat officialClientToServerEndToEndTest `
  "-PminecraftClientDirectory=C:\path\to\.minecraft" `
  "-PminecraftClientJavaExecutable=C:\path\to\java.exe"
```

By default, all official server/client runtime files remain under Gradle's
`build` directory.

## Updating the protocol

The index at `.agents/skills/README.md` describes the network, world-storage, and full-library closed-loop update
skills. They discover the latest stable Wiki target by default and accept an explicit Minecraft release or protocol
target. Deterministic acquisition, inventory, source indexing, registry generation, audits, and tests are exposed as
Gradle tasks.
