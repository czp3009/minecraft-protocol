# minecraft-protocol

A Kotlin Multiplatform toolkit for Minecraft: Java Edition, from typed packets and client/server connections to mutable
world data and files. This is an experimental project, not yet ready for production use.

- **Typed connections:** coroutine-based client/server negotiation through Play, with release-matched vanilla defaults
  and explicit extension points for custom packets, registries and loader negotiation. Resource-pack offers and
  application-owned loading fit into the same negotiation flow.
- **World data you can compute on:** mutable `Chunk`, `EntityChunk`, `PoiChunk` and nested values, with shared dynamic
  properties that application code can read, wrap and modify directly.
- **Disk, memory and network conversion:** directional codecs with explicit contexts, NBT/SNBT serialization, Anvil
  regions, coordinated world writes and live read-only access. Network projections carry only transmitted data.
- **Launcher building blocks:** Microsoft/Xbox/Minecraft authentication, game Login and signed chat, distribution
  metadata and streaming downloads.

The library provides data and protocol capabilities. Applications own gameplay, ticking, world authority and persistence
policy. Vanilla defaults are generated from the repository-selected official release.

## Choose an entry point

| Task                                   | Modules                                                                                                                                                                                                    |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Connect or accept players              | [protocol-client](protocol-client/README.md), [protocol-server](protocol-server/README.md)                                                                                                                 |
| Work with worlds                       | [world-format](world-format/README.md), [world-io](world-io/README.md), [protocol-world](protocol-world/README.md)                                                                                         |
| Resolve data packs and registries      | [protocol-configuration](protocol-configuration/README.md), [protocol-configuration-vanilla](protocol-configuration-vanilla/README.md), [datapack-vanilla](datapack-vanilla/README.md)                     |
| Own lower-level protocol behavior      | [protocol-model](protocol-model/README.md), [protocol-serialization](protocol-serialization/README.md), [protocol-session](protocol-session/README.md), [protocol-transport](protocol-transport/README.md) |
| Build authentication or launcher flows | [account-auth](account-auth/README.md), [protocol-auth](protocol-auth/README.md), [distribution-metadata](distribution-metadata/README.md)                                                                 |
| Use NBT independently                  | [nbt](nbt/README.md), [nbt-serialization](nbt-serialization/README.md)                                                                                                                                     |

Target support follows capability: portable layers include browser targets, socket layers use Node on JS/WasmJS, and
world filesystem access supports JS Node but not browser or Wasm. Each module's build script lists its exact targets.

## Client: connect to a server

Create a caller-owned Ktor `SelectorManager(Dispatchers.Default)` and keep it open until its connections close. Supply
the server address; the example constructs an offline identity. `handlePacket` receives the open connection, negotiation
result and each incoming packet. Vanilla protocol and registry defaults are provided:

```kotlin
suspend fun runClient(
    selectorManager: SelectorManager,
    host: String,
    handlePacket: suspend (MinecraftClientConnection, MinecraftClientNegotiationResult, ClientboundPacket) -> Unit,
) {
    MinecraftClientConnection.connect(selectorManager, host).use { minecraftClientConnection ->
        val minecraftClientNegotiationResult = minecraftClientConnection.negotiate(MinecraftOfflineIdentity("Player"))
        for (clientboundPacket in minecraftClientConnection.incoming) {
            handlePacket(minecraftClientConnection, minecraftClientNegotiationResult, clientboundPacket)
        }
    }
}
```

After negotiation, the application's packet loop applies world updates and sends teleport and Chunk-batch
acknowledgements. See [the client guide](protocol-client/README.md#receive-the-initial-world) for that loop and online
Login.

## Server: accept client connections

Bind a listener with vanilla offline defaults, then handle each connection in a child coroutine:

```kotlin
suspend fun runServer(
    selectorManager: SelectorManager,
    handlePlay: suspend (MinecraftServerConnection, MinecraftServerNegotiationResult) -> Unit,
) = coroutineScope {
    MinecraftServer.bind(selectorManager).use { minecraftServer ->
        while (minecraftServer.isOpen) {
            val minecraftServerConnection = minecraftServer.accept()
            launch {
              minecraftServerConnection.use connectionUse@{
                val minecraftServerNegotiationResult = minecraftServerConnection.negotiate() ?: return@connectionUse
                handlePlay(minecraftServerConnection, minecraftServerNegotiationResult)
                }
            }
        }
    }
}
```

`negotiate()` answers Status requests and returns `null`, or completes Login and Configuration and returns an open Play
connection's result. `handlePlay` then owns world synchronization and the packet loop. The
[server guide](protocol-server/README.md) covers online authentication, configuration and Chunk flow control.

## Read and send world data

Continue inside `handlePlay` above, with its connection and negotiation result. Open the world once with
`MinecraftWorldAccess.open("world".toPath())`, keeping it open until all handlers finish. Prepare these application
inputs:

- `dimensionChunkReads`: select
  `world.dimensions[minecraftServerNegotiationResult.minecraftDimensionContext.dimensionId]`
  and call `.chunks(chunkNbtDecoderContext)` with the [NBT context](world-format/README.md#decode-and-encode-nbt) for
  that
  dimension and game-time base; `world` is the opened `MinecraftWorldAccess`.
- `chunkPacketWriteMappings`: construct `ChunkPacketWriteMappings(blockEntityUpdateTag)` with the application's public
  update-tag callback. Construct `chunkPacketRequiredDataProvider` as shown in the
  [server example](protocol-server/README.md#convert-semantic-chunks-to-packets) to supply missing counts/heights/light.
- `minecraftInitialWorldBootstrap`: construct the player's spawn, abilities and position using the
  [bootstrap example](protocol-server/README.md#send-only-the-bootstrap). `handlePacket` handles incoming serverbound
  packets.

This example chooses air/plains defaults for both the NBT context and packet encoder. The shortcut takes dimension and
registry facts directly from the negotiation result:

```kotlin
val chunkPacketEncoder = minecraftServerNegotiationResult.chunkPacketEncoder(
  defaultBlockState = BlockState(BlockId("minecraft:air")),
  defaultBiome = BiomeId("minecraft:plains"),
  chunkPacketWriteMappings = chunkPacketWriteMappings,
  chunkPacketRequiredDataProvider = chunkPacketRequiredDataProvider,
)
coroutineScope {
  launch {
    val decoded = requireNotNull(dimensionChunkReads.readChunk(ChunkPosition(0, 0)))
    check(decoded.chunk.isFullyGenerated)
    minecraftServerConnection.synchronizeInitialWorld(
      MinecraftInitialWorld(minecraftInitialWorldBootstrap, listOf(decoded.chunk), chunkPacketEncoder),
    )
    minecraftServerConnection.requestFlush()
  }
  for (serverboundPacket in minecraftServerConnection.incoming) {
    handlePacket(serverboundPacket)
    }
}
```

`handlePacket` receives teleport and Chunk-batch acknowledgements while the sender enqueues the bootstrap and one Chunk
batch. The decoded Chunk is ordinary mutable data, so application code can modify
it before sending. The [complete disk/memory/network flow](world-io/README.md#disk-to-memory-to-packets) shows the
conversion boundaries in more detail. The linked module guides show both explicit codec construction and shortcuts.

## Demos

- [Launcher](demo/launcher/README.md): terminal account management, official-version installation and game launch.
- [Web map](demo/web-map/README.md): live world inspection with a browser map and official block textures.

## Build and test

Use the checked-in Gradle wrapper and a `java` on `PATH` matching `BuildVersions.JAVA_VERSION` or newer. Android builds
also require the Android SDK. Initial builds and official-peer tests may download prepared artifacts.

```shell
./gradlew -q minecraftVersion
./gradlew :protocol-serialization:jvmTest
./gradlew allTests
```

On Windows use `.\gradlew.bat`. [buildSrc](buildSrc/README.md) documents artifact preparation and test infrastructure;
[AGENTS.md](AGENTS.md) contains contributor rules. Runtime consumers do not need the private generators or fixtures.
