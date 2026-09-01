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

## Choose the modules you need

The project is split by capability, so applications can start at the appropriate layer:

| Area           | Module                                                             | Use it for                                                     |
|----------------|--------------------------------------------------------------------|----------------------------------------------------------------|
| NBT            | [`nbt`](nbt/README.md)                                             | Constructing and inspecting NBT values                         |
| NBT            | [`nbt-serialization`](nbt-serialization/README.md)                 | Binary NBT, SNBT, and serializable Kotlin models               |
| Protocol       | [`protocol-model`](protocol-model/README.md)                       | Packet payloads and shared protocol values                     |
| Protocol       | [`protocol-serialization`](protocol-serialization/README.md)       | Packet payload encoding and custom packet registries           |
| Data packs     | [`protocol-datapack`](protocol-datapack/README.md)                 | Pack projection, Configuration views, and world Chunk adapters |
| Data packs     | [`protocol-datapack-vanilla`](protocol-datapack-vanilla/README.md) | Release-matched vanilla packs, registries, and defaults        |
| Networking     | [`protocol-transport`](protocol-transport/README.md)               | Low-level frames, compression, encryption, and sockets         |
| Networking     | [`protocol-session`](protocol-session/README.md)                   | Typed packet channels, state transitions, and loader profiles  |
| Authentication | [`account-auth`](account-auth/README.md)                           | Launcher-side Microsoft, Xbox, and Minecraft Services login    |
| Authentication | [`protocol-auth`](protocol-auth/README.md)                         | Game Login, Session Server, profile keys, and signed chat      |
| Connections    | [`protocol-client`](protocol-client/README.md)                     | Connecting to a server and entering Play                       |
| Connections    | [`protocol-server`](protocol-server/README.md)                     | Accepting clients and sending an initial world view            |
| Worlds         | [`world-format`](world-format/README.md)                           | Semantic Chunk/Entity/POI values and Anvil formats             |
| Worlds         | [`world-io`](world-io/README.md)                                   | Reading and writing actual world directories                   |

Use `protocol-client` or `protocol-server` for the maintained connection lifecycle; drop to `protocol-session`,
`protocol-serialization`, or `protocol-transport` only when the application needs to own that lower-level boundary.
Launcher authentication in `account-auth` ends with caller-managed tokens and profiles. Game-connection authentication
in `protocol-auth` consumes those values; neither authentication module depends on the other.

Target availability follows capability. The model, serialization, authentication, data-pack, and portable world-format
layers include configured browser targets; real-socket modules use JS/WasmJS on Node; `world-io` supports JS Node but
does not expose partial browser or Wasm filesystem APIs. Each module's build script is the exact target list.

## Demo

The [launcher demo](demo/launcher/README.md) is a terminal application that combines account management,
official-version installation, and game launch.

The [web map demo](demo/web-map/README.md) composes live world-file reads, vanilla data-pack projection, kRPC, and a
Kotlin/JS browser map that prepares official block textures before displaying the world.

## Quick starts

### Connect a client

For a game connection, `negotiate()` handles Handshake, Login, Configuration, dynamic registries, and the first
`PlayLoginPacket`. The initial-world bootstrap and Chunk batches arrive afterwards through `incoming`. The vanilla
packet definition, transport settings, negotiation profile, Known Packs, registries, and client options are all
defaults. This example therefore supplies only the server address and player identity; online identities are shown in
the
[`protocol-client` guide](protocol-client/README.md#online-login):

```kotlin
suspend fun runClient(
    selectorManager: SelectorManager,
    host: String,
    handlePacket: suspend (
        MinecraftClientConnection,
        MinecraftClientNegotiationResult,
        ClientboundPacket,
    ) -> Unit,
) {
    MinecraftClientConnection.connect(selectorManager, host).use { minecraftClientConnection ->
        val minecraftClientNegotiationResult = minecraftClientConnection.negotiate(MinecraftOfflineIdentity("Player"))

        for (clientboundPacket in minecraftClientConnection.incoming) {
            handlePacket(minecraftClientConnection, minecraftClientNegotiationResult, clientboundPacket)
        }
    }
}
```

The caller owns the single packet loop after negotiation returns. That loop applies the initial player position before
replying with `ConfirmTeleportationPacket`, decodes each Chunk batch, and replies to every
`ChunkBatchFinishedPacket` with `ChunkBatchReceivedPacket`. See
[`protocol-client`](protocol-client/README.md#receive-the-initial-world) for that progressive flow, custom Configuration
data, status queries, Chunk/Entity projection, loader profiles, and online Login. Direct official KeepAlive requests are
answered by the client endpoint and do not appear in this application packet loop.

### Accept clients on a server

`MinecraftServer` supplies the listener and typed connection. `negotiate()` stops after the first `PlayLoginPacket`, so
the server then sends a finite initial world before it starts its application packet loop. The application owns the
accept loop and chooses one coroutine per connection:

```kotlin
suspend fun runServer(
    selectorManager: SelectorManager,
    handlePacket: suspend (
        MinecraftServerConnection,
        MinecraftServerNegotiationResult,
        ServerboundPacket,
    ) -> Unit,
) = coroutineScope {
    MinecraftServer.bind(selectorManager).use { minecraftServer ->
        while (minecraftServer.isOpen) {
            val minecraftServerConnection = minecraftServer.accept()
            launch {
                minecraftServerConnection.use minecraftServerConnectionUse@{
                    val minecraftServerNegotiationResult =
                        minecraftServerConnection.negotiate() ?: return@minecraftServerConnectionUse
                    val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
                        minecraftServerNegotiationResult = minecraftServerNegotiationResult,
                        chunkRadius = 1,
                    )
                    minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
                    minecraftServerConnection.requestFlush()

                    for (serverboundPacket in minecraftServerConnection.incoming) {
                        handlePacket(
                            minecraftServerConnection,
                            minecraftServerNegotiationResult,
                            serverboundPacket,
                        )
                    }
                }
            }
        }
    }
}
```

`bind()` and `negotiate()` default to the vanilla packet definition, transport behavior, offline authentication,
negotiation profile, Configuration data, and negotiation policy. `synchronizeInitialWorld()` enqueues the bootstrap, one
complete Chunk batch, and the finite Entity view; `requestFlush()` publishes them. It does not wait for
`ConfirmTeleportationPacket` or `ChunkBatchReceivedPacket`, which arrive through the application packet loop. The
[`protocol-server` guide](protocol-server/README.md#enter-play-and-send-a-finite-world) shows the exact boundary and how
a long-running server replaces this finite example with feedback-controlled Chunk batches across its own ticks. Preset
negotiation also starts the official server KeepAlive service; matching replies are validated and consumed before the
application packet loop.

### Read a world

Use `MinecraftWorldAccess` when your process owns the world directory:

```kotlin
suspend fun readChunk(
    worldPath: Path,
    chunkPosition: ChunkPosition,
    chunkNbtCodec: ChunkNbtCodec<BlockStateDescriptor, String>,
): Chunk<BlockStateDescriptor, String>? = MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
    minecraftWorldAccess.dimensions.overworld.openRegion(chunkPosition.regionPosition).use { regionHandle ->
        regionHandle.readChunk(chunkPosition, chunkNbtCodec)
    }
}
```

Use `LiveMinecraftWorldAccess` for read-only observation of a world that another process may be changing. The live world
access itself has no close lifecycle, but each live Chunk, Entity, or POI Region handle is a synchronous `use` resource
that independently retains its `.mca` file for consecutive reads. See [`world-format`](world-format/README.md) for
constructing the codec and [`world-io`](world-io/README.md) for scopes, consistency limits, locking, writes, Entity and
POI Regions, stateless stores, exact-path access, standalone files, and data packs.

### Move world data between disk, memory, and packets

The three paths compose at public values while keeping filesystem, semantic formats, packet projection, and transport in
their owning modules:

1. **Server disk → computational values.** Open one `MinecraftWorldAccess`, read the enabled data packs and
   `WorldGenSettingsData`, and call `resolveMinecraftWorld()`. The resulting per-dimension `MinecraftChunkContext`
   supplies the ordinary Region's `chunkNbtCodec`; Entity and POI Region handles directly return
   `EntityChunk<NbtCompound>` and `PoiChunk`. The complete code is in
   [`world-io`](world-io/README.md#read-computational-world-values-from-disk).
2. **Server memory → clientbound network.** Encode each semantic Chunk with
   `minecraftChunkContext.packetEncoder(isAir, hasFluid)`, then send the snapshots through
   `synchronizeInitialWorld()` or send later packets inside explicit Chunk batch boundaries. Persisted Entities first
   need connection-local runtime IDs and tracking state before `toMinecraftEntitySnapshot()` can create pairing bundles.
   See the [`protocol-server` Chunk path](protocol-server/README.md#convert-semantic-chunks-to-packets) and
   [Entity path](protocol-server/README.md#send-entity-pairing-bundles). `PoiChunk` has no vanilla clientbound packet.
3. **Client network → computational values.** `MinecraftClientConnection.incoming` yields typed packets. After
   negotiation, build a decoder with
   `minecraftDimensionContext.createMinecraftChunkContext().packetDecoder()` and decode each complete
   `ChunkDataAndUpdateLightPacket`; use `MinecraftEntityPacketDecoder` for Entity pairing bundles. The
   [`protocol-client` examples](protocol-client/README.md#decode-chunk-packets) show both operations and the
   [current projection boundary](protocol-client/README.md#know-the-client-projection-boundary): later incremental Chunk
   and Entity packets are typed but must be applied to application-owned state.

Only the semantic `Chunk` has a direct full-payload projection on both disk and network paths. `EntityChunk` is a
persistent grouping whereas the client receives runtime `Entity` state, and POI remains server-side storage. A
packet-derived Chunk is computationally usable but not a complete persistent record: its storage metadata is absent and
its Block Entity tags contain only the server's update payload. Writing it to disk therefore requires an explicit merge
or reconstruction of the omitted persistent state.

Callers working directly between a packet value and an encoded payload can use
[`PacketRegistry`](protocol-serialization/README.md#compose-a-packet-registry); framing, compression envelopes,
encryption, and sockets remain in `protocol-transport` and `protocol-session`.

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
