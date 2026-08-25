# minecraft-protocol

> This is an early-stage experimental project and is not ready for production use.

`minecraft-protocol` is a Kotlin Multiplatform toolkit for Minecraft: Java Edition. It covers the network protocol from
packet models and wire encoding through client/server negotiation, and it also provides NBT, data-pack, Anvil, and
filesystem-backed world APIs.

Use it to build tools such as:

- protocol clients, bots, proxies, and specialized servers;
- launchers that authenticate Microsoft accounts and start official game versions;
- map editors, converters, analyzers, and live world inspectors;
- mod-aware integrations with custom packets and dynamic registries.

It is infrastructure rather than a complete game server. Gameplay, ticking, permissions, player management,
authoritative world state, and application persistence policy remain application concerns.

## What the project provides

- Typed packet models and `kotlinx.serialization` codecs for the repository-selected Minecraft release.
- Ktor-based TCP transport with Minecraft framing, compression, and encryption.
- Typed client and server connections for Status, Login, Configuration, and entry into Play.
- Vanilla, Fabric API, NeoForge, and Forge negotiation profiles with composable custom packet registrations.
- Offline and online game authentication, profile-key verification, and signed-chat primitives.
- Separate launcher APIs for Microsoft OAuth, Xbox/XSTS, Minecraft Services tokens, entitlements, and profiles.
- Generated release-matched vanilla registries, block states, Configuration data, and complete official data packs.
- Immutable NBT values, streaming binary NBT, and SNBT.
- Filesystem-independent Anvil, Chunk, Entity, coordinate, palette, level, statistics, advancement, and data-pack
  formats.
- Okio-backed mutable world access and non-locking live reads of worlds owned by another process.

## Choose the modules you need

The project is split by capability, so applications can start at the appropriate layer:

| Area           | Module                                                             | Use it for                                                          |
|----------------|--------------------------------------------------------------------|---------------------------------------------------------------------|
| NBT            | [`nbt`](nbt/README.md)                                             | Constructing and inspecting NBT values                              |
| NBT            | [`nbt-serialization`](nbt-serialization/README.md)                 | Binary NBT, SNBT, and serializable Kotlin models                    |
| Protocol       | [`protocol-model`](protocol-model/README.md)                       | Packet payloads and shared protocol values                          |
| Protocol       | [`protocol-serialization`](protocol-serialization/README.md)       | Packet payload encoding and custom packet registries                |
| Data packs     | [`protocol-datapack`](protocol-datapack/README.md)                 | Projecting caller-supplied packs into Configuration data            |
| Data packs     | [`protocol-datapack-vanilla`](protocol-datapack-vanilla/README.md) | Release-matched vanilla packs, registries, and defaults             |
| Networking     | [`protocol-transport`](protocol-transport/README.md)               | Low-level frames, compression, encryption, and sockets              |
| Networking     | [`protocol-session`](protocol-session/README.md)                   | Typed packet channels, state transitions, and loader profiles       |
| Authentication | [`account-auth`](account-auth/README.md)                           | Launcher-side Microsoft, Xbox, and Minecraft Services login         |
| Authentication | [`protocol-auth`](protocol-auth/README.md)                         | Game Login, Session Server, profile keys, and signed chat           |
| Connections    | [`protocol-client`](protocol-client/README.md)                     | Connecting to a server and entering Play                            |
| Connections    | [`protocol-server`](protocol-server/README.md)                     | Accepting clients and sending an initial world view                 |
| Worlds         | [`world-format`](world-format/README.md)                           | Chunk/Entity values, coordinates, compression, and Anvil containers |
| Worlds         | [`world-io`](world-io/README.md)                                   | Reading and writing actual world directories                        |

## Demo

The [launcher demo](demo/launcher/README.md) is a terminal application that combines account management,
official-version installation, and game launch.

## Connect a client

`queryStatus()` performs the server-list Status request followed by Ping/Pong. A Status connection cannot continue into
Login, so use a fresh connection when joining:

```kotlin
suspend fun queryServer(
    selectorManager: SelectorManager,
    host: String,
): MinecraftStatusExchange = MinecraftClientConnection.connect(
    selectorManager = selectorManager,
    host = host,
).use { connection ->
    connection.queryStatus()
}
```

For a game connection, `negotiate()` handles Handshake, Login, Configuration, dynamic registries, and entry into Play.
This example uses an offline identity; online identities are shown in the [
`protocol-client` guide](protocol-client/README.md#online-login):

```kotlin
suspend fun runClient(
    selectorManager: SelectorManager,
    host: String,
    handlePacket: suspend (ClientboundPacket) -> Unit,
) {
    MinecraftClientConnection.connect(selectorManager, host).use { connection ->
        val negotiation = connection.negotiate(MinecraftOfflineIdentity("Player"))
        val initialDimension = negotiation.dimensionLayout

        for (packet in connection.incoming) {
            handlePacket(packet)
        }
    }
}
```

The caller owns the packet loop after negotiation returns. See [`protocol-client`](protocol-client/README.md) for custom
Configuration data, chunk/entity projection, loader profiles, and online Login.

## Accept clients on a server

`MinecraftServer` supplies the listener and typed connection. The application owns the accept loop and chooses one
coroutine per connection:

```kotlin
suspend fun runServer(
    selectorManager: SelectorManager,
    handlePacket: suspend (MinecraftServerConnection, ServerboundPacket) -> Unit,
) = coroutineScope {
    MinecraftServer.bind(selectorManager).use { server ->
        while (server.isOpen) {
            val connection = server.accept()
            launch {
                connection.use connectionUse@{
                    connection.negotiate() ?: return@connectionUse
                    for (packet in connection.incoming) {
                        handlePacket(connection, packet)
                    }
                }
            }
        }
    }
}
```

The default is an offline, vanilla-compatible negotiation. [`protocol-server`](protocol-server/README.md) shows online
authentication, application policy, custom data packs, loader profiles, and finite initial Chunk/entity synchronization.

## Read a world

Use `MinecraftWorldAccess` when your process owns the world directory. It acquires `session.lock`, and its suspend `use`
helpers close Region handles before releasing the world lease:

```kotlin
suspend fun readChunk(
    worldPath: Path,
    chunkPosition: ChunkPosition,
    codec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Chunk<BlockStateDescriptor, String>? = MinecraftWorldAccess.open(worldPath).use { world ->
    world.openRegion(chunkPosition.region).use { region ->
        region.readChunk(chunkPosition, codec)
    }
}
```

Use `LiveMinecraftWorldAccess` for non-locking read-only observation of a world that another process may be changing.
See [`world-format`](world-format/README.md) for constructing the codec and [`world-io`](world-io/README.md) for writes,
Entity Regions, standalone files, and data packs.

## Build and test

Requirements:

- a JDK whose `java` command is on `PATH`; the required major version is selected in `BuildVersions.JAVA_VERSION`;
- an Android SDK only when building Android targets;
- network access for the first dependency download and for official-peer fixture preparation.

Use the checked-in Gradle wrapper:

```shell
./gradlew build
./gradlew :protocol-serialization:jvmTest
./gradlew jvmTest
./gradlew allTests
```

On Windows, use `./gradlew.bat` or `.\gradlew.bat` from PowerShell.

Repository contributors can find the private development layers in the [`buildSrc`](buildSrc/README.md),
[`protocol-symbol-processor`](protocol-symbol-processor/README.md),
[`minecraft-test-support`](minecraft-test-support/README.md), and
[`minecraft-test-fixture-host`](minecraft-test-fixture-host/README.md) guides. They are build and test infrastructure,
not application dependencies.

## Minecraft release

The repository aligns all Minecraft-dependent modules to one selected release. Generated code exposes its release and
protocol number:

```kotlin
val minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION
val protocolVersion = MinecraftProtocol.PROTOCOL_VERSION
```

Print the selected release without reading build source:

```shell
./gradlew -q minecraftVersion
```

Each runtime-module README documents its own public entry points and examples.
