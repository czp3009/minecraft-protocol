# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

`MinecraftServer` binds a Ktor TCP listener. `accept` returns a raw typed `MinecraftServerConnection`; it does not run
negotiation or install per-connection callbacks. The application owns the accept loop and concurrency:

```kotlin
suspend fun runMinecraftServer(
    selectorManager: SelectorManager,
    handlePlayPacket: suspend (MinecraftServerConnection, ServerboundPacket) -> Unit,
) {
    coroutineScope {
        MinecraftServer.bind(
            selectorManager = selectorManager,
        ).use { minecraftServer ->
            while (minecraftServer.isOpen) {
                val minecraftServerConnection = minecraftServer.accept()
                launch {
                    minecraftServerConnection.use {
                        val minecraftServerNegotiationResult = minecraftServerConnection.negotiate()
                        if (minecraftServerNegotiationResult != null) {
                            for (serverboundPacket in minecraftServerConnection.incoming) {
                                handlePlayPacket(minecraftServerConnection, serverboundPacket)
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

`negotiate` runs sequentially in the calling coroutine and exclusively borrows the connection's `incoming` and
`outgoing` channels until return. Give that coroutine sole ownership of both channels for the complete negotiation.

The preset does not impose a phase packet count or timeout. It waits for the required response until the connection
fails or the calling coroutine is cancelled; application-specific admission deadlines belong around `negotiate`.

Every accepted connection's reader, writer, and requested flushes use the dispatcher from `selectorManager` by default.
Override `connectionDispatcher` on `bind` when those per-connection jobs should use a different dispatcher. A server
tick can enqueue immutable packet or bundle snapshots with `outgoing.send`/`trySend`, continue its world simulation, and
call `requestFlush()` once at tick end. Socket backpressure stays on the connection dispatcher. Channel capacity and the
response to a failed `trySend` remain application policy; the single writer preserves the order of packets accepted by a
non-dropping outgoing channel.

## Writing your own negotiation

Applications can write their own `negotiate` function on the typed connection returned by `accept`. Read the maintained
[server
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerProtocol.kt)
for the complete Status, Login, Configuration, and Play-entry ordering. It is built from the same public channels,
connection operations, authentication primitives, registry functions, and profile hooks available to callers. The
ownership contract is unchanged: one coroutine owns both packet channels for the complete sequence and retains its
current dispatcher.

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

In the following example, `myResolvedRegistryContext`, `myModPacketCodecs`, `myNetworkConfiguration`, and
`myFrozenRegistrySync` are immutable values produced by the application's NeoForge integration. `selectorManager` is the
Ktor selector manager passed to the server's application-lifetime setup, as in the first example.

```kotlin
val minecraftProtocolFormat = MinecraftProtocolFormat(
    MinecraftProtocolFormat.configuration.copy(registries = myResolvedRegistryContext),
)
val minecraftConnectionDefinition = NeoForgeProtocol.connectionDefinition(
    extensionCodecs = myModPacketCodecs,
    format = minecraftProtocolFormat,
)
val neoForgeServerProfileDefinition = NeoForgeServerProfileDefinition(
    network = myNetworkConfiguration,
    frozenRegistries = myFrozenRegistrySync,
    resolvedRegistryContext = myResolvedRegistryContext,
)
val minecraftServer = MinecraftServer.bind(
    selectorManager = selectorManager,
    definition = minecraftConnectionDefinition,
)
```

Create only the small mutable profile state per connection:

Here `minecraftServerConnection` is returned by `minecraftServer.accept()`, while `serverOptions` and
`applicationPolicy` are the application's protocol-visible options and admission policy.

```kotlin
val minecraftServerNegotiationResult = minecraftServerConnection.negotiate(
    profile = NeoForgeServerProfile(neoForgeServerProfileDefinition),
    options = serverOptions,
    policy = applicationPolicy,
)
```

Fabric and Forge equivalents are in [`protocol-session`](../protocol-session/README.md). All three compose caller packet
codecs and activate only negotiated Configuration/Play routes; custom Login queries and unknown mod payloads remain
available through the same public packet channels.

## Authentication

Offline mode is the default. It derives the vanilla offline UUID and performs no Session Server I/O or stream
encryption. Online mode receives a caller-owned `HttpClient`. In the example, `applicationHttpClient` is that configured
client and `selectorManager` is the application-lifetime Ktor selector manager used by `MinecraftServer.bind`:

```kotlin
val minecraftServerAuthentication = MinecraftServerAuthentication.online(
    sessionHttpClient = applicationHttpClient,
)
val minecraftServer = MinecraftServer.bind(
    selectorManager = selectorManager,
    authentication = minecraftServerAuthentication,
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

Supply `compressionThreshold`, player counts, view distance, simulation distance, and custom `PlayLoginPacket` values
according to the server policy you intend to advertise. The preset forwards those protocol values and does not recreate
`server.properties` range policy inside the library. Supply a `ProtocolDataSet` for the repository-selected Minecraft
release so its registries and Configuration payloads match the packet codecs.

The default is `VanillaDataPacks.protocolData` from
[`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md). Applications can instead construct a final
`DataPackProtocolDataSet`, use an explicit generic `DataPackProtocolProjection`, or convert a parsed stack with
`toVanillaProtocolDataSet`, then pass that value as `MinecraftServerNegotiationOptions.protocolData`. The server
orchestration sends its feature flags, Known Packs, selected registry snapshot, and tags in the existing Configuration
order; it does not read a world or data-pack path and never depends on `world-io`.

`configurationPackets` and `configurationTasks` are the application's extension traffic. Keep the framework-owned
Feature Flags, Known Packs, registry data, tags, and Finish Configuration packets out of those lists; the preset already
sends them in protocol order and trusts the policy result without rescanning it.

## Load world data packs and negotiate

The following is the complete application-side path from a world's `level.dat` and `datapacks` directory to the
Configuration packets sent by `negotiate`. The application using this example depends on `world-io` and
`protocol-datapack-vanilla`; `protocol-server` itself deliberately does not depend on `world-io`.

`WorldDataPackStore` reads only `file/...` references. The resolver below fills the other enabled references from
caller-supplied in-memory packs first, then from the generated official packs. This preserves the low-to-high priority
order stored in `DataPacks.Enabled` and leaves the caller free to supply loader or application-defined packs without
putting them on disk:

`suppliedPacks` is specifically the optional map of built-in packs supplied in memory by a mod or loader. A vanilla
server, including one using ordinary `file/...` packs from the world's `datapacks` directory, should leave it empty or
omit the argument. Its default is `emptyMap()`; the generated `VanillaDataPacks` entry supplies `vanilla` automatically.
When a mod uses this argument, each map key is the exact enabled reference from `DataPacks.Enabled` and its value is the
corresponding parsed or programmatically constructed `DataPack`.

The extension receiver is the `LoadedWorldDataPacks` returned by `world.readEnabledDataPacks()`. Its
`enabledReferences` property is copied from `level.dat` at `Data.DataPacks.Enabled`, and its `packs` property contains
the `file/...` entries that `world-io` has already read and parsed from the world's `datapacks` directory.

```kotlin
fun LoadedWorldDataPacks.resolveEnabledStack(
    suppliedPacks: Map<DataPackId, DataPack> = emptyMap(),
): DataPackStack {
    val diskPacks = packs.associateBy(DataPack::id)
    return DataPackStack(
        enabledReferences.map { reference ->
            val id = DataPackId(reference)
            diskPacks[id]
                ?: suppliedPacks[id]
                ?: when {
                    id == VanillaDataPacks.coreId -> VanillaDataPacks.core
                    id in VanillaDataPacks.packIds -> VanillaDataPacks.parsePack(id)
                    else -> error("No data pack was supplied for enabled reference $reference")
                }
        },
    )
}

suspend fun loadWorldProtocolData(
    world: MinecraftWorldAccess,
    suppliedPacks: Map<DataPackId, DataPack> = emptyMap(),
    registryProjectors: List<DataPackSynchronizedRegistryProjector> = emptyList(),
): DataPackProtocolDataSet {
    val loaded = world.readEnabledDataPacks()
    val stack = loaded.resolveEnabledStack(suppliedPacks)
    return stack.toVanillaProtocolDataSet(registryProjectors)
}
```

Load and project the immutable data once when the world is opened, then use it when constructing each connection's
options. Calling `negotiate` is the step that actually sends Feature Flags, Known Packs, synchronized registries, and
tags to the client. In the call site below, `openedWorld` is the application's already-open `MinecraftWorldAccess`, and
`minecraftServerConnection` is one connection returned by `MinecraftServer.accept()`:

```kotlin
suspend fun negotiateWorldConnection(
    connection: MinecraftServerConnection,
    protocolData: ProtocolDataSet,
): MinecraftServerNegotiationResult? = connection.negotiate(
    options = MinecraftServerNegotiationOptions(protocolData = protocolData),
)

val protocolData = loadWorldProtocolData(
    world = openedWorld,
)
val negotiationResult = negotiateWorldConnection(minecraftServerConnection, protocolData)
```

A modded server can instead provide its loader-owned packs explicitly, without placing them in the world directory. Here
`modDataPack` is a parsed or programmatically constructed `DataPack` owned by the loader, and
`modRegistryProjectors` contains that loader's required synchronized-registry conversions:

```kotlin
val loaderPacks = mapOf(modDataPack.id to modDataPack)
val protocolData = loadWorldProtocolData(
    world = openedWorld,
    suppliedPacks = loaderPacks,
    registryProjectors = modRegistryProjectors,
)
```

The last two values in the snippet belong at different lifetimes: compute `protocolData` once per loaded pack stack, and
call `negotiateWorldConnection` for each accepted connection. A custom pack that changes a synchronized registry must
supply its disk-JSON-to-network-NBT `DataPackSynchronizedRegistryProjector`; packs that only change tags or server-only
resources need no registry projector. Applications can replace the resolver, edit the resulting
`DataPackStack`, use a generic `DataPackProtocolProjection`, or construct the final `ProtocolDataSet` directly.

## Initial world projection

`MinecraftInitialWorldBootstrap` contains only the fixed initial Play values. It does not contain Chunks or Entities, so
a tick-driven server can enqueue the bootstrap before it begins caller-controlled AOI synchronization:

```kotlin
suspend fun bootstrapTickDrivenWorld(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    dimensionIdentifier: Identifier,
    defaultSpawnPosition: Vector3d,
    playerPosition: Vector3d,
): MinecraftInitialWorldBootstrap {
    val chunkPosition = MinecraftCoordinates.block(
        playerPosition.x,
        playerPosition.y,
        playerPosition.z,
    ).chunk
    val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
        options = minecraftServerNegotiationOptions,
        dimension = dimensionIdentifier,
        defaultSpawnPosition = defaultSpawnPosition,
        playerPosition = playerPosition,
        centerChunk = chunkPosition,
    )
    minecraftServerConnection.sendInitialWorldBootstrap(minecraftInitialWorldBootstrap)
    return minecraftInitialWorldBootstrap
}
```

The default spawn, current player position, and Chunk center are independent values. The `vanilla` factory derives the
omitted values from one position for simple worlds, while its named parameters allow a returning player to enter at a
different position from the world's default spawn. `minecraftInitialWorldBootstrap.teleportId` remains available when
the tick loop handles `ConfirmTeleportationPacket`. Play Login belongs to negotiation and is not repeated here.

`MinecraftInitialWorld` adds a finite list of detached Chunk and Entity snapshots to that bootstrap. It is a one-shot
convenience for tests and simple servers, not an authoritative world or game loop:

```kotlin
suspend fun synchronizeFlatInitialWorld(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
) {
    val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
        options = minecraftServerNegotiationOptions,
        chunkRadius = 0,
    )
    minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
    minecraftServerConnection.requestFlush()
}
```

Neither sending function performs an implicit flush. `MinecraftInitialWorldBootstrap.packets()` exposes the same fixed
packet sequence when the application needs the detached packets without immediately enqueueing them.

`synchronizeInitialWorld` submits exactly one complete Chunk batch and does not wait for `ChunkBatchReceivedPacket`. For
incremental initial loading and later movement loading, the application tick loop owns that backpressure directly:
after it submits `ChunkBatchStartPacket`, the selected complete Chunk packets, and `ChunkBatchFinishedPacket`, that
tick's batch work is finished. Later ticks drain and dispatch available `incoming.tryReceive()` results and submit no
next batch until the acknowledgement arrives. Other Play traffic and updates for already-submitted Chunks continue in
the meantime. If the acknowledgement never arrives, no later Chunk batch is sent; the library does not suspend the tick
waiting for it or invent a Chunk-specific timeout, and the application may disconnect through its ordinary connection
health policy.

### In-memory Chunk to packet

An application that already has a semantic `Chunk` in memory does not need `world-io`. Create one immutable
`MinecraftChunkPacketEncoder` for the active registry/dimension context and reuse it for every Chunk. Conversion is the
direct receiver-oriented call `chunk.toMinecraftChunkSnapshot(...)`:

```kotlin
fun createMinecraftChunkSnapshots(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftDimensionLayout: MinecraftDimensionLayout,
    chunks: Iterable<Chunk<ProtocolBlockState, ProtocolRegistryEntry>>,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
): List<MinecraftChunkSnapshot> {
    val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
        registries = minecraftServerConnection.registries,
        isAir = isAir,
        hasFluid = hasFluid,
        hasSkyLight = minecraftDimensionLayout.hasSkyLight,
    )
    return chunks.map { chunk ->
        chunk.toMinecraftChunkSnapshot(minecraftChunkPacketEncoder)
    }
}
```

Put the returned snapshots in `MinecraftInitialWorld.chunks`; `synchronizeInitialWorld` sends each snapshot's
`ChunkDataAndUpdateLightPacket`. A snapshot's `packet()` method also exposes that packet directly. Palette values are
packed from the semantic Chunk without first constructing a dense 4096-element block list.

This is the normal server path: an authoritative server usually sends the Chunk already held by its in-memory world or
Chunk cache. Persistence is only a fallback when that value is absent.

Construct the encoder and Chunk from the same installed registry context and active dimension layout. The projection
uses their IDs directly and does not rescan every palette entry merely to enforce that application-level convention; the
packet serializer still enforces physical wire widths and declared payload boundaries.

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
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionIdentifier: Identifier,
    defaultSpawnPosition: Vector3d,
    playerPosition: Vector3d,
    chunkPositions: List<ChunkPosition>,
    expectedDataVersion: Int,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
) {
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
                        chunk.toMinecraftChunkSnapshot(minecraftChunkPacketEncoder),
                    )
                }
            }
        }
    }
    val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
        options = minecraftServerNegotiationOptions,
        dimension = dimensionIdentifier,
        defaultSpawnPosition = defaultSpawnPosition,
        playerPosition = playerPosition,
    )
    val minecraftInitialWorld = MinecraftInitialWorld(
        bootstrap = minecraftInitialWorldBootstrap,
        chunks = minecraftChunkSnapshots,
    )
    minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
    minecraftServerConnection.requestFlush()
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

### Entity to pairing bundle

`MinecraftEntitySnapshot` represents the detached state consumed by the selected-release vanilla Entity pairing
sequence. `bundle(registries)` returns one logical `ClientboundBundlePacket`; `packets(registries)` retains the raw
opening delimiter, ordered payload packets, and closing delimiter for callers that need the physical sequence. The
matching official server constructs and sends one such bundle for each newly tracked Entity. Its client accepts several
complete pairing sequences in one bundle as well, so a collection of snapshots also supports `bundle(registries)`.
Within each sequence, the payload order is:

1. spawn;
2. non-empty metadata, when supplied;
3. attributes, when supplied;
4. equipment, when supplied;
5. this Entity's passengers, when supplied;
6. its vehicle's complete passenger relationship when this Entity is itself a passenger;
7. the leash relationship, when supplied.

The snapshot is a projection, not an Entity rules engine. The caller supplies official-valid numeric IDs, relationships,
attributes, and equipment; the constructor does not add gameplay-policy validation or connection-state locks.

The same pairing bundle is used when an Entity first enters a joining player's already-synchronized area and when it
later enters an established player's tracked area. Movement and other later changes use their ordinary delta packets.
Player-list visibility required before spawning a player Entity remains application state and is sent separately.
Subtype-specific synchronization that vanilla performs after the delimiter-bounded pairing bundle is likewise
caller-owned and is enqueued after this bundle.

A semantic `world-format` `Entity` contains persistent common state, but it cannot invent connection-local or
registry-resolved values. Supply those values when creating the snapshot:

```kotlin
fun <E : Any> createMinecraftEntitySnapshot(
    entity: Entity<E>,
    entityId: Int,
    entityMetadata: EntityMetadata?,
    entityAttributes: List<AttributeSnapshot>,
    entityEquipment: List<EquipmentUpdate>,
    passengerEntityIds: List<Int>,
    vehiclePassengerRelation: MinecraftEntityPassengersSnapshot?,
    leashHolderEntityId: Int?,
): MinecraftEntitySnapshot = entity.toMinecraftEntitySnapshot(
    entityId = entityId,
    metadata = entityMetadata,
    attributes = entityAttributes,
    equipment = entityEquipment,
    passengerEntityIds = passengerEntityIds,
    vehiclePassengerRelation = vehiclePassengerRelation,
    leashHolderEntityId = leashHolderEntityId,
)
```

The helper copies the Entity's position, velocity, and rotation and snapshots the supplied collection structure before
returning. Build this value on the tick thread while the authoritative Entity state is stable, then enqueue its bundle
before continuing that tick. The network writer may send it later without observing subsequent mutation of the semantic
Entity.

`sendEntitySnapshot` is the suspending convenience for sending the complete logical bundle:

```kotlin
suspend fun sendEntityPairing(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftEntitySnapshot: MinecraftEntitySnapshot,
) {
    minecraftServerConnection.sendEntitySnapshot(minecraftEntitySnapshot)
}
```

It enqueues one `ClientboundBundlePacket` through the connection's ordered `outgoing.send` path and therefore suspends
when the configured queue is full. A tick loop that must make its own slow-client decision calls
`minecraftEntitySnapshot.bundle(minecraftServerConnection.registries)` and passes the result to `outgoing.trySend`. The
writer expands an accepted logical bundle without channel-level interleaving. `packets(registries)` remains available
when an application deliberately manages the delimiter sequence itself.

Several detached snapshots can share one bundle and one queue entry while preserving their iteration order:

```kotlin
suspend fun sendEntityPairings(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftEntitySnapshots: List<MinecraftEntitySnapshot>,
) {
    minecraftServerConnection.sendEntitySnapshots(minecraftEntitySnapshots)
}
```

For caller-owned semantic Entities, the projection lambda supplies the connection-local state that `Entity` cannot
contain. The shortcut creates each detached snapshot on the calling tick thread and places all resulting pairing
sequences in one logical bundle:

```kotlin
fun <E : Any> createEntityBundle(
    entities: List<Entity<E>>,
    protocolRegistryContext: ProtocolRegistryContext,
    entityIds: Map<Uuid, Int>,
): ClientboundBundlePacket = entities.toMinecraftEntityBundle(protocolRegistryContext) { entity ->
    entity.toMinecraftEntitySnapshot(entityId = entityIds.getValue(entity.uuid))
}
```

### Disk Entity Chunk to Entity to pairing bundle

`protocol-server` already depends on `world-format` for the semantic Entity model, but it does not depend on
`world-io`. Add `world-io` in the application when Entity Chunks must be loaded from disk. Entity storage is random
access by Region and Chunk; it is not stored in `level.dat`.

This complete example loads one requested Entity from its Entity Chunk, adds caller-owned runtime pairing state, and
sends its complete bundle:

```kotlin
suspend fun sendStoredEntity(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftWorldAccess: MinecraftWorldAccess,
    chunkPosition: ChunkPosition,
    entityUuid: Uuid,
    expectedDataVersion: Int,
    entityId: Int,
    entityMetadata: EntityMetadata?,
    entityAttributes: List<AttributeSnapshot>,
    entityEquipment: List<EquipmentUpdate>,
    passengerEntityIds: List<Int>,
    vehiclePassengerRelation: MinecraftEntityPassengersSnapshot?,
    leashHolderEntityId: Int?,
): Boolean {
    val entityDataRegistry = NbtEntityDataRegistry()
    val entityChunkNbtCodec = EntityChunkNbtCodec(expectedDataVersion, entityDataRegistry)
    return minecraftWorldAccess.openEntityRegion(chunkPosition.region).use entityRegionUse@{ entityRegionHandle ->
        val entityChunk = entityRegionHandle.readChunk(chunkPosition, entityChunkNbtCodec)
            ?: return@entityRegionUse false
        val entity = entityChunk.entity(entityUuid) ?: return@entityRegionUse false
        val minecraftEntitySnapshot = entity.toMinecraftEntitySnapshot(
            entityId = entityId,
            metadata = entityMetadata,
            attributes = entityAttributes,
            equipment = entityEquipment,
            passengerEntityIds = passengerEntityIds,
            vehiclePassengerRelation = vehiclePassengerRelation,
            leashHolderEntityId = leashHolderEntityId,
        )
        minecraftServerConnection.sendEntitySnapshot(minecraftEntitySnapshot)
        true
    }
}
```

For all Entities in the Chunk, iterate `entityChunk.allEntities()` and assign each UUID its current numeric Entity ID.
Passenger relationships use those runtime IDs; persisted passenger nesting alone is not a protocol ID allocator. A
long-running server normally reads Entity Chunks into its in-memory world before ticking and sends snapshots from that
memory. The direct disk path remains available for applications that deliberately need it.

AOI policy is outside this module. A correct application tracks Entities only for positions inside the client's
requested view whose full Chunk snapshot has already been submitted to that client's ordered outgoing queue. When an
Entity leaves that intersection, send its removal; when it enters again, send a fresh pairing bundle and then resume
deltas.
