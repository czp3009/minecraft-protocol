# minecraft-protocol

> This is an early-stage experimental project. It is not ready for production use.

`minecraft-protocol` is a Kotlin Multiplatform library for the Minecraft Java Edition network protocol and world-storage
formats. It provides typed packet models, `kotlinx.serialization` wire codecs, Ktor transport and connection
orchestration, authentication helpers, version-matched vanilla data, binary NBT, and Anvil world I/O.

The library is infrastructure for Minecraft applications, not a complete game: gameplay, authoritative worlds, ticking,
persistence policy, permissions, and operations remain application responsibilities.

## Modules

| Module                                                       | Purpose                                                                           |
|--------------------------------------------------------------|-----------------------------------------------------------------------------------|
| [`nbt`](nbt/README.md)                                       | Format-independent NBT values and logical serializers                             |
| [`nbt-serialization`](nbt-serialization/README.md)           | Binary NBT and NBT tree conversion through `kotlinx.serialization`                |
| [`protocol-model`](protocol-model/README.md)                 | Format-independent packet payloads and shared protocol values                     |
| [`protocol-serialization`](protocol-serialization/README.md) | Minecraft wire encodings and composable packet registries                         |
| [`protocol-vanilla-data`](protocol-vanilla-data/README.md)   | Version-matched Known Packs, registries, tags, and vanilla catalogues             |
| [`protocol-transport`](protocol-transport/README.md)         | Ktor sockets, framing, compression, and encryption                                |
| [`protocol-session`](protocol-session/README.md)             | Typed connections, state transitions, and loader profiles                         |
| [`account-auth`](account-auth/README.md)                     | Microsoft OAuth, Xbox, and Minecraft Services token/entitlement/profile HTTP APIs |
| [`protocol-auth`](protocol-auth/README.md)                   | Game identities, Session Server HTTP, and Login key exchange                      |
| [`protocol-client`](protocol-client/README.md)               | Status, Login, Configuration, and a Play-ready client connection                  |
| [`protocol-server`](protocol-server/README.md)               | Connection admission and finite initial chunk/entity projection                   |
| [`world-format`](world-format/README.md)                     | Anvil formats and selected-release structured world-file models                   |
| [`world-io`](world-io/README.md)                             | Layered Region/Chunk, standalone NBT/JSON, live, and filesystem-backed world I/O  |

The project is fully modular: depend on exactly the modules you need. Higher layers reuse the lower layers their APIs
require, so a focused consumer never pulls in unrelated capabilities.

## Highlights

- Channel-first typed packet connections over standard coroutine channels.
- Public primitives for application-defined Status, Login, Configuration, Play entry, and initial-world synchronization;
  client/server presets are optional orchestration conveniences.
- Immutable, composable packet registries for vanilla and modded protocols, with preset Fabric, NeoForge, and Forge
  negotiation profiles.
- Offline and online Login with Session Server calls and Login key exchange; Microsoft OAuth and Xbox account HTTP APIs
  are available separately.
- Streaming binary NBT, selected-release level/advancement/statistics models, and filesystem-independent Anvil
  containers, plus Okio-based typed, full-value, and streaming world I/O—including live reads of official-server worlds.

Usage examples are documented at each owning layer: binary NBT in
[`nbt-serialization`](nbt-serialization/README.md), compression and Anvil containers in
[`world-format`](world-format/README.md), and filesystem-backed JSON, NBT, MCA, and MCC access in
[`world-io`](world-io/README.md#one-api-shape). Every published module in the table above documents its
own key entry points rather than requiring consumers to infer them from a higher layer.

Together these blocks cover applications such as:

- map editors that read, render, and rewrite Anvil worlds directly, including worlds owned by a running official server;
- Minecraft launchers, combining Microsoft/Xbox account authentication with server sessions;
- clients and servers built entirely on this library's protocol implementation, without depending on official Minecraft
  code.

Unknown top-level packet IDs, Login queries, and custom-payload routes stay lossless as direction-correct
`UnknownPacket` values; malformed wire data and invalid packet order propagate instead of being swallowed.

## Client example

Ping a server as the multiplayer server list does. `queryStatus()` performs the Status handshake, obtains the server's
Status response, and completes the Ping/Pong exchange; it does not run Login negotiation:

```kotlin
SelectorManager(Dispatchers.Default).use { selector ->
  MinecraftClientConnection.connect(
    selectorManager = selector,
    host = "127.0.0.1",
  ).use { connection ->
    val status = connection.queryStatus()
    val description = status.response.jsonResponse
    val echoedPingPayload = status.pong.timestamp
  }
}
```

Status has no continuation into Login. Close this connection after the ping, then create a fresh connection and call
`negotiate()` only when joining the server.

Or log in and enter Play, then take over the packet loop:

```kotlin
connection.negotiate(MinecraftOfflineIdentity("Player"))
for (packet in connection.incoming) {
  handlePlayPacket(packet)
}
```

The preset runs in the calling coroutine and exclusively owns `incoming` and `outgoing` until it returns. No other
coroutine may read or write either channel during that interval. Applications can write the negotiation sequence
themselves under the same single-coroutine ownership precondition; the library assumes that ownership and does not lock
or arbitrate application-created races. Read the maintained [client
`negotiate` implementation](protocol-client/src/commonMain/kotlin/com/hiczp/minecraft/protocol/client/MinecraftClientProtocol.kt)
and [server
`negotiate` implementation](protocol-server/src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerProtocol.kt)
as the source-level packet-order references. The [
`protocol-client`](protocol-client/README.md#writing-your-own-negotiation)
and [`protocol-server`](protocol-server/README.md#writing-your-own-negotiation) guides identify the public primitives
used by those implementations.

## Server example

```kotlin
MinecraftServer.bind(selectorManager = selector).use { server ->
  while (server.isOpen) {
    val connection = server.accept()
    launch {
      connection.use {
        connection.negotiate() ?: return@use
        for (packet in connection.incoming) {
          handlePlayPacket(connection, packet)
        }
      }
    }
  }
}
```

## Building and testing

Requirements:

- A JDK with `java` on `PATH`; the project's Java major version follows the Java version required by the matching
  Minecraft release. See `BuildVersions.JAVA_VERSION` in the Gradle configuration for the current value.
- An Android SDK configured through the standard Gradle mechanisms, only when building or testing Android targets.
- Network access for the first build so Gradle can download dependencies; tests that verify against official Minecraft
  peers additionally download exact-version fixtures.

Use the checked-in Gradle wrapper; a separate Gradle installation is unnecessary. Gradle provisions Node and the other
non-JVM toolchains automatically.

```shell
# Assemble everything
./gradlew build

# Focused feedback loop during development
./gradlew :protocol-serialization:jvmTest

# Every module's JVM suite
./gradlew jvmTest

# All configured multiplatform tests
./gradlew allTests
```

On Windows, replace `./gradlew` with `.\gradlew.bat`.

## Minecraft release

The repository aligns to one Minecraft release at a time. In code, the matching release and protocol number are exposed
by `MinecraftProtocol`:

```kotlin
val release = MinecraftProtocol.MINECRAFT_VERSION
val protocolVersion = MinecraftProtocol.PROTOCOL_VERSION
```

Print the currently selected release from the command line:

```shell
./gradlew -q minecraftVersion
```

Changing the target is a single-constant change in the build configuration, followed by the affected build or test
tasks.

See each module's README for its API and examples.
