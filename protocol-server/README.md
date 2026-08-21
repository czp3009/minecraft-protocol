# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

`MinecraftServer` binds a Ktor TCP listener. `accept` returns a raw typed `MinecraftServerConnection`; it does not run
negotiation or install per-connection callbacks. The application owns the accept loop and concurrency:

```kotlin
suspend fun runMinecraftServer(
    selectorManager: SelectorManager,
    handlePlayPacket: suspend (MinecraftServerConnection, ClientboundPacket) -> Unit,
) {
    coroutineScope {
        MinecraftServer.bind(selectorManager = selectorManager).use { minecraftServer ->
            while (minecraftServer.isOpen) {
                val minecraftServerConnection = minecraftServer.accept()
                launch {
                    minecraftServerConnection.use {
                        val minecraftServerNegotiationResult = minecraftServerConnection.negotiate()
                        if (minecraftServerNegotiationResult != null) {
                            for (clientboundPacket in minecraftServerConnection.incoming) {
                                handlePlayPacket(minecraftServerConnection, clientboundPacket)
                            }
                        }
                    }
                }
            }
        }
    }
}
```

The preset `negotiate` extension supports Status or Login, synchronizes `ProtocolDataSet`, installs the negotiated
registry context, and returns `MinecraftServerNegotiationResult`. A status ping is answered and closed completely before
`negotiate` returns null, so the caller has nothing left to do. A non-null result contains the exact Play Login and
application-level negotiation facts; `connection.registries` is the authoritative installed registry context. The
extension is the library's preset orchestration path over the same typed packet connection returned by `accept`.

`negotiate` runs sequentially in the calling coroutine. It neither launches a negotiation scope nor selects a
`Dispatcher`, and it exclusively borrows the connection's `incoming` and `outgoing` channels until return. The caller
must guarantee that no other coroutine reads or writes those channels while negotiation is active. There is no
negotiator lock that arbitrates competing users; packet theft, ordering failures, and other races caused by concurrent
application access are the application's responsibility.

## Writing your own negotiation

Applications can write their own `negotiate` function on the typed connection returned by `accept`. Read the maintained
[server
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerProtocol.kt)
for the complete Status, Login, Configuration, and Play-entry ordering. It is built from the same public channels,
connection operations, authentication primitives, registry functions, and profile hooks available to callers. The
ownership contract is unchanged: one coroutine owns both packet channels for the complete sequence, and the library does
not add locks, a scope, or a dispatcher to caller code.

For vanilla offline Login, the server reads Handshake and Login Start, sends Login Success, receives Client Information
and Known Packs, then sends its registry and tag snapshot. Before Finish Configuration it resolves that exact snapshot
with `resolveSynchronizedRegistryContext`, applies the Play Login dimension with `withPlayLoginDimension`, invokes
`ServerNegotiationProfile.resolveRegistryContext`, and installs the result. The Finish acknowledgement performs the real
wire-state transition to Play; the server then sends the same Play Login value that it retains for later explicit world
synchronization.

The portable
[public-primitives end-to-end test](src/commonTest/kotlin/com/hiczp/minecraft/protocol/server/ClientToServerEndToEndTest.kt)
executes complete client and server implementations without calling either preset. It covers Status, compression,
offline Login, complete Configuration, public profile hooks and routes, active-dimension context, Play Login, chunks,
teleport/chunk acknowledgements, and a keepalive. The matching official-client suite continues to verify the preset and
the same public initial-world primitives.

## Shared definitions and modded profiles

Build immutable protocol data at the application lifetime you need, then pass references into a shareable connection and
profile definition. `MinecraftServer` reuses its `MinecraftConnectionDefinition` for every accepted connection:

```kotlin
val connectionDefinition = NeoForgeProtocol.connectionDefinition(
    extensionCodecs = myModPacketCodecs,
    registries = myResolvedRegistryContext,
)
val profileDefinition = NeoForgeServerProfileDefinition(
    network = myNetworkConfiguration,
    frozenRegistries = myFrozenRegistrySync,
    resolvedRegistryContext = myResolvedRegistryContext,
)
val server = MinecraftServer.bind(
    selectorManager = selector,
    definition = connectionDefinition,
)
```

Create only the small mutable profile state per connection:

```kotlin
val result = connection.negotiate(
    profile = NeoForgeServerProfile(profileDefinition),
    options = serverOptions,
    policy = applicationPolicy,
)
```

Fabric and Forge equivalents are in [`protocol-session`](../protocol-session/README.md). All three compose caller packet
codecs and activate only negotiated Configuration/Play routes; custom Login queries and unknown mod payloads remain
available through the same public packet channels.

## Authentication

Offline mode is the default. It derives the vanilla offline UUID and performs no Session Server I/O or stream
encryption. Online mode receives a caller-owned `HttpClient`:

```kotlin
val authentication = MinecraftServerAuthentication.online(
    sessionHttpClient = applicationHttpClient,
)
val server = MinecraftServer.bind(
    selectorManager = selector,
    authentication = authentication,
)
```

The server validates Encryption Response, enables encryption at the official boundary, and uses the Session Server for
`/hasJoined`. Authentication failure never downgrades to offline mode. The application owns the `HttpClient` and its
timeout, retry, engine, and lifetime policies.

## Application policy

This module does not read `server.properties` or implement game services. `MinecraftServerNegotiationOptions` owns
protocol-visible defaults and `MinecraftServerNegotiationPolicy` supplies status, admission, Play Login, optional
Configuration packets/tasks, and an unknown-query decision; whitelists, operators, rate limits, permissions, worlds,
ticking, and management services remain application responsibilities. The library never sends a disconnect merely
because negotiation, encoding, or decoding failed; rejection exceptions expose a ready-to-send failure packet that the
caller chooses to send.

## Initial world projection

`MinecraftInitialWorld` projects a finite initial chunk/entity view; it is not an authoritative world or game loop. Use
its registry-aware snapshot overloads so block-state, biome, and entity-type IDs come from the installed
`ProtocolRegistryContext`. Once preset negotiation reaches Play, one call sends the stateless bootstrap a client needs
to place the player and accept chunks and entities:

```kotlin
suspend fun synchronizeFlatInitialWorld(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
): MinecraftInitialWorldSynchronization {
    val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
        options = minecraftServerNegotiationOptions,
        chunkRadius = 0,
    )
    return minecraftServerConnection.synchronizeInitialWorld(
        world = minecraftInitialWorld,
        login = minecraftServerNegotiationResult.playLogin,
    )
}
```

`login` is explicit state, not a hidden connection marker. An application-defined negotiation passes the exact
`PlayLoginPacket` it sent; preset callers pass `MinecraftServerNegotiationResult.playLogin`. Reconfiguration and respawn
likewise pass the currently active Play Login, so synchronization never guesses from stale connection history.

### In-memory Chunk to packet

An application that already has a semantic `Chunk` in memory does not need `world-io`. Create one immutable
`MinecraftChunkPacketEncoder` for the active registry/dimension context and reuse it for every Chunk. Conversion is the
direct receiver-oriented call `chunk.toMinecraftChunkSnapshot(...)`:

```kotlin
fun createMinecraftChunkSnapshots(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftDimensionLayout: MinecraftDimensionLayout,
    chunksByPosition: Map<ChunkPosition, Chunk<ProtocolBlockState, ProtocolRegistryEntry>>,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
): List<MinecraftChunkSnapshot> {
    val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
        registries = minecraftServerConnection.registries,
        isAir = isAir,
        hasFluid = hasFluid,
        hasSkyLight = minecraftDimensionLayout.hasSkyLight,
    )
    return chunksByPosition.map { (chunkPosition, chunk) ->
        chunk.toMinecraftChunkSnapshot(
            position = chunkPosition,
            encoder = minecraftChunkPacketEncoder,
        )
    }
}
```

Put the returned snapshot in `MinecraftInitialWorld.chunks`; `synchronizeInitialWorld` sends its
`ChunkDataAndUpdateLightPacket`. A snapshot's `packet()` method also exposes that packet directly. Palette values are
packed from the semantic Chunk without first constructing a dense 4096-element block list.

This is the normal server path: an authoritative server usually sends the Chunk already held by its in-memory world or
Chunk cache. Persistence is only a fallback when that value is absent.

### Disk Chunk to in-memory Chunk to packet

`protocol-server` does not open world files. An application that wants to send stored Chunks adds `world-io` alongside
`protocol-server`. The path is `MinecraftWorldAccess` → `RegionHandle.readChunk` → `Chunk` →
`MinecraftChunkSnapshot`; `ChunkNbtCodec` performs the disk NBT-to-semantic-Chunk conversion, then the same packet
encoder used by the in-memory path performs the network projection.

The following is a complete disk-backed synchronization example. It receives an already-open
`MinecraftWorldAccess`, so acquiring `session.lock` is not coupled to a player joining:

```kotlin
suspend fun synchronizeStoredInitialWorld(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionIdentifier: Identifier,
    spawnPosition: Vector3d,
    chunkPositions: List<ChunkPosition>,
    expectedDataVersion: Int,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
): MinecraftInitialWorldSynchronization {
    require(chunkPositions.distinct().size == chunkPositions.size)
    val minecraftDimensionLayout = MinecraftDimensionLayout.from(
        minecraftServerNegotiationOptions.protocolData,
        dimensionIdentifier,
    )
    val chunkLayout = minecraftDimensionLayout.toChunkLayout()
    val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
        registries = minecraftServerConnection.registries,
        isAir = isAir,
        hasFluid = hasFluid,
        hasSkyLight = minecraftDimensionLayout.hasSkyLight,
    )
    val chunkNbtContext = ChunkNbtContext(
        layout = chunkLayout,
        registries = minecraftChunkPacketEncoder.chunkDataRegistries,
        expectedDataVersion = expectedDataVersion,
    )
    val chunkNbtCodec = ChunkNbtCodec(chunkNbtContext)
    val chunkPositionsByRegion = chunkPositions.groupBy(ChunkPosition::region)

    val minecraftChunkSnapshots = buildList {
        for ((regionPosition, regionChunkPositions) in chunkPositionsByRegion) {
            minecraftWorldAccess.openRegion(regionPosition).use { regionHandle ->
                for (chunkPosition in regionChunkPositions) {
                    val chunk = regionHandle.readChunk(chunkPosition, chunkNbtCodec) ?: continue
                    add(
                        chunk.toMinecraftChunkSnapshot(
                            position = chunkPosition,
                            encoder = minecraftChunkPacketEncoder,
                        ),
                    )
                }
            }
        }
    }
    val minecraftInitialWorld = MinecraftInitialWorld(
        dimension = dimensionIdentifier,
        dimensionType = minecraftDimensionLayout,
        spawnPosition = spawnPosition,
        viewDistance = minecraftServerNegotiationOptions.viewDistance,
        simulationDistance = minecraftServerNegotiationOptions.simulationDistance,
        difficulty = minecraftServerNegotiationOptions.difficulty,
        difficultyLocked = minecraftServerNegotiationOptions.difficultyLocked,
        playerAbilities = MinecraftInitialWorld.vanillaPlayerAbilities(minecraftServerNegotiationOptions.gameMode),
        chunks = minecraftChunkSnapshots,
    )
    return minecraftServerConnection.synchronizeInitialWorld(
        world = minecraftInitialWorld,
        login = minecraftServerNegotiationResult.playLogin,
    )
}
```

A real server normally opens the world once during application startup, holds its `session.lock` lease for the server
process lifetime, and closes it during shutdown. The whole server can remain inside one `use` scope:

```kotlin
suspend fun runMinecraftServerWithWorld(
    worldPath: Path,
    runMinecraftServer: suspend (MinecraftWorldAccess) -> Unit,
) {
    MinecraftWorldAccess.open(worldPath).use { minecraftWorldAccess ->
        runMinecraftServer(minecraftWorldAccess)
    }
}
```

The disk function above is an integration example, not a recommendation to read every player-visible Chunk from disk on
every join. A typical application first looks up each requested position in its in-memory world or Chunk cache, converts
every hit with `chunk.toMinecraftChunkSnapshot(...)`, and calls the disk path only for the missing positions. Grouping
those misses by `ChunkPosition.region`, as the example does, lets one `RegionHandle` serve all requested Chunks from
that Region.

`openRegion` is lazy and does not fail merely because a Region is missing; a missing Chunk is skipped by the nullable
`readChunk` result. The encoder's `chunkDataRegistries` maps persisted block descriptors and biome names through the
exact registry context installed on this connection, including loader-resolved values. Registry IDs alone do not say
whether a state is air or contains fluid, so `isAir` and `hasFluid` come from the same selected-release vanilla/mod data
catalogue used by the application. The encoder uses them to calculate the two section counts required by the wire
format.

By default, persisted block-entity `id`/`x`/`y`/`z` fields become the packet's separate metadata and the remaining
compound becomes its update tag. A block-entity implementation with a type-specific client update tag can supply
`blockEntityUpdateTag` when constructing `MinecraftChunkPacketEncoder`.
