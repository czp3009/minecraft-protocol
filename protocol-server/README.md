# protocol-server

`protocol-server` accepts Minecraft: Java Edition connections and provides preset orchestration for Status or Login
through entry into Play.

It is intended for applications that want to control their own server behavior. The module supplies protocol negotiation
and optional finite initial-world packets, but it does not implement ticking, gameplay, persistence, permissions, player
management, or an authoritative world.

## Accept connections

`MinecraftServer` binds a Ktor TCP listener. The application owns the accept loop and connection concurrency; the
repository's [server quick start](../README.md#accept-clients-on-a-server) shows that complete outer lifetime.

`accept()` returns a typed connection without starting negotiation. `negotiate()` answers a Status exchange and returns
`null` after closing that Status connection, or completes Login and Configuration and returns
`MinecraftServerNegotiationResult` for an open Play connection after sending the first `PlayLoginPacket`. It
deliberately does not send the initial-world bootstrap, Chunks, or Entities.

The root quick start uses the built-in vanilla, offline, transport, Configuration, and policy defaults, then separately
chooses a finite `MinecraftInitialWorld.flatVanilla` view. That world choice is not a negotiation default.

Negotiation exclusively uses both packet channels until it returns. Run it in one coroutine without concurrent readers
or writers. The preset has no built-in admission timeout; apply the application's own deadline around the call.

## Enter Play and send a finite world

The shortest complete Login path negotiates first, creates a small initial world view from the resulting dimension and
Play settings, sends that view, and then transfers the packet channels to the application:

```kotlin
suspend fun serveFlatWorld(
    minecraftServerConnection: MinecraftServerConnection,
    handlePacket: suspend (
        MinecraftServerConnection,
        MinecraftServerNegotiationResult,
        ServerboundPacket,
    ) -> Unit,
) {
    val minecraftServerNegotiationResult = minecraftServerConnection.negotiate() ?: return
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
```

The `null` branch is a completed Status exchange, so it has no world to send. A non-null result means that
`PlayLoginPacket` was sent and supplies the exact dimension context, game mode, and distances reused by the
initial-world factory.
`synchronizeInitialWorld()` then enqueues the fixed bootstrap, one complete Chunk batch, and every supplied Entity
pairing bundle. It neither flushes nor waits for acknowledgements; `ConfirmTeleportationPacket` and
`ChunkBatchReceivedPacket` arrive through `incoming` and remain application state. The flat factory is a finite
convenience for examples, tests, and simple views, not a tick loop or authoritative world.

### Send only the bootstrap

A server that loads or generates its own Chunks can send only the fixed initial Play packets first. The result overload
reuses the exact dimension, game mode, and distances sent in Play Login; difficulty and player position remain explicit
initial-world choices:

```kotlin
suspend fun sendBootstrap(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
    difficulty: Difficulty,
    playerPosition: Vector3d,
) {
    val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
        minecraftServerNegotiationResult = minecraftServerNegotiationResult,
        difficulty = difficulty,
        playerPosition = playerPosition,
    )
    minecraftServerConnection.sendInitialWorldBootstrap(minecraftInitialWorldBootstrap)
    minecraftServerConnection.requestFlush()
}
```

`MinecraftInitialWorldBootstrap` sends difficulty, default spawn, player abilities, render and simulation distances, the
initial player position, the start-loading game event, and the center Chunk, but no Chunk or Entity data. Its
`teleportId` is the value the application expects in `ConfirmTeleportationPacket`. Use the direct overload to supply
dimension, game mode, distances, and positions independently when no negotiation result is available.

## Stream Chunk batches over ticks

A long-running server normally keeps a per-connection queue of visible Chunks instead of placing the entire view in one
`MinecraftInitialWorld`. On an application tick, when that connection has send quota and room for another in-flight
batch, it selects nearby pending Chunks and sends one batch in protocol order:

```kotlin
suspend fun sendChunkBatch(
    minecraftServerConnection: MinecraftServerConnection,
    chunkDataAndUpdateLightPackets: List<ChunkDataAndUpdateLightPacket>,
) {
    require(chunkDataAndUpdateLightPackets.isNotEmpty())
    minecraftServerConnection.outgoing.send(ChunkBatchStartPacket)
    chunkDataAndUpdateLightPackets.forEach { chunkDataAndUpdateLightPacket ->
        minecraftServerConnection.outgoing.send(chunkDataAndUpdateLightPacket)
    }
    minecraftServerConnection.outgoing.send(
        ChunkBatchFinishedPacket(chunkDataAndUpdateLightPackets.size),
    )
    minecraftServerConnection.requestFlush()
}
```

The client answers each finished batch with `ChunkBatchReceivedPacket.desiredChunksPerTick`. The application's single
`incoming` consumer validates that response, reduces its outstanding-batch count, and uses a finite, policy-bounded form
of the requested rate when granting later tick quotas. A simple controller can allow only one outstanding batch and wait
for its acknowledgement before sending the next one. An official-style controller may later permit a bounded number of
in-flight batches, but it still uses acknowledgements for flow control instead of sending the complete view without
feedback.

Do not add a second `incoming.receive()` loop inside the tick or batch sender. Let the connection's packet handler
update or signal its Chunk-flow state, and let the next eligible tick consume that state. This module supplies the batch
packet models and Chunk encoders; pending visibility, tick scheduling, rate policy, and acknowledgement deadlines belong
to the server application. The [semantic Chunk encoder](#convert-semantic-chunks-to-packets) below produces the batch
payloads, and the [`protocol-client` flow](../protocol-client/README.md#receive-the-initial-world) shows the matching
consumer and response order.

## Configure the advertised server

`MinecraftServerNegotiationOptions` contains only values used from Handshake through the first Play Login: compression,
Status and transfer behavior, authentication checks, player limits, advertised dimensions, view and simulation distance,
game mode, secure-chat claim, and the `ProtocolData` sent during Configuration. Initial-world difficulty, difficulty
locking, player abilities, and semantic Chunk defaults are separate concerns. The
`minecraftServerConnection` parameter below is a value returned by `MinecraftServer.accept()`:

```kotlin
suspend fun negotiateConfigured(
    minecraftServerConnection: MinecraftServerConnection,
): MinecraftServerNegotiationResult? {
    val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
        statusDescription = "A Kotlin Minecraft server",
        maximumPlayers = 50,
        viewDistance = 12,
        simulationDistance = 8,
        gameMode = GameMode.CREATIVE,
    )
    return minecraftServerConnection.negotiate(
        minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
    )
}
```

The defaults provide offline vanilla negotiation with the repository-selected release's generated vanilla protocol data.

Preset negotiation also manages the official server KeepAlive lifecycle. It starts a Configuration run after Login is
acknowledged, replaces it with a fresh Play run before the first Play packet, and defaults each run to a 15-second
interval. Matching replies are validated and consumed. A pending challenge at the next interval, an unsolicited reply,
or a mismatched reply closes the connection; closing the connection also cancels the run. Applications using
`negotiate()` need neither start this service nor handle its reply packets themselves.

Use `MinecraftServerNegotiationPolicy` for decisions that vary by connection: server status, profile rejection, Play
Login, extra Configuration packets, response-gated Configuration tasks, and unknown query handling. Every method has a
default implementation, so a policy can override only what it needs. Here `allowedNames` is supplied by the
application's admission service:

```kotlin
fun admissionPolicy(allowedNames: Set<String>): MinecraftServerNegotiationPolicy =
    object : MinecraftServerNegotiationPolicy {
        override suspend fun profileRejection(
            gameProfile: GameProfile,
            transferred: Boolean,
            minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        ): JsonTextComponent? = if (gameProfile.name in allowedNames) {
            null
        } else {
            JsonTextComponent.literal("Not allowed")
        }
    }
```

Call `admissionPolicy(allowedNames)` with the application's current set, then pass the result to
`minecraftServerConnection.negotiate(minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
minecraftServerNegotiationPolicy = minecraftServerNegotiationPolicy)`. The module does not read `server.properties` or
provide a whitelist, operator, or permissions system.

If negotiation throws `MinecraftLoginRejectedException`, its `failurePacket` is ready to send. The library leaves the
connection open so the application can decide whether to send that packet and when to close.

The status policy returns the shared `ServerStatus` model; `protocol-serialization` alone turns it into the bounded JSON
protocol string. Override `onlinePlayerCount(...)` for a live count. The default `serverStatus(...)` calls that method
through the policy instance, so an override that delegates to `super.serverStatus(...)` still receives the customized
count:

```kotlin
fun statusPolicy(
    currentOnlinePlayers: suspend () -> Int,
    description: JsonTextComponent,
): MinecraftServerNegotiationPolicy = object : MinecraftServerNegotiationPolicy {
    override suspend fun onlinePlayerCount(
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): Int = currentOnlinePlayers()

    override suspend fun serverStatus(
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        onlineMode: Boolean,
    ): ServerStatus = super.serverStatus(minecraftServerNegotiationOptions, onlineMode).copy(
        description = description,
    )
}
```

`DefaultMinecraftServerNegotiationPolicy.createServerStatus(...)` and `createPlayLoginPacket(...)` expose the default
builders for a policy that wants to construct either response directly. Keeping them on the existing default-policy
object avoids unscoped builder names and leaves the options class as negotiation data only.

## Online authentication

Offline mode is the default. For online mode, provide a caller-owned Ktor `HttpClient` and a generated or restored
server key pair:

```kotlin
suspend fun bindOnlineServer(
    selectorManager: SelectorManager,
    httpClient: HttpClient,
): MinecraftServer {
    val minecraftServerAuthentication = MinecraftServerAuthentication.online(httpClient)
    return MinecraftServer.bind(
        selectorManager = selectorManager,
        minecraftServerAuthentication = minecraftServerAuthentication,
    )
}
```

The preset validates Encryption Response, enables stream encryption, and calls the Session Server `/hasJoined` endpoint.
The caller configures and closes the HTTP client. Authentication failure never falls back to offline mode.

## Resolve a stored world for negotiation

A disk-backed server needs both the enabled pack selection and `world_gen_settings`. Resolve them together so
Configuration, Play Login, disk Chunk decoding, and later packet encoding use the same registry order. The
[`world-io` disk path](../world-io/README.md#read-computational-world-values-from-disk) produces the
`ResolvedMinecraftWorld`; this module consumes it without taking a filesystem dependency.

Choose the initial dimension and expose the resolved protocol data through negotiation options:

```kotlin
fun negotiationOptions(
    resolvedMinecraftWorld: ResolvedMinecraftWorld,
    dimensionId: DimensionId,
): MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
    protocolData = resolvedMinecraftWorld.protocolData,
    initialDimensionId = dimensionId,
    dimensionIds = resolvedMinecraftWorld.dimensions.keys,
)
```

Pass the resulting options to `negotiate()`. This explicit construction is intentional: the resolved world owns registry
and codec facts, while Status policy, limits, distances, and the selected initial dimension belong to the server
application. The default Play Login derives the selected dimension-type raw ID from the supplied protocol data.
Negotiation returns a connection-specific `MinecraftDimensionContext`; the resolved world's per-dimension
`MinecraftChunkContext` remains the raw-ID-free disk decoder and Chunk packet encoder.

Unknown enabled packs, inline dimension types, missing references, and invalid layouts fail before a partial server
world is returned. Negotiation independently rejects an inconsistent Play Login or active registry context before
entering Play.

The pack bridge preserves persisted core/built-in/file priority, supplies release-matched bundled packs, and carries the
world's enabled feature configuration into the generated vanilla projection base. Recipes, functions, loot tables, and
other server-only resources remain server data and are not emitted as Configuration values.

Pass any other constructible `ProtocolData` directly through `MinecraftServerNegotiationOptions.protocolData` when no
stored-world resolution is needed. Add application negotiation choices directly alongside the world facts:

```kotlin
fun configuredNegotiationOptions(
    resolvedMinecraftWorld: ResolvedMinecraftWorld,
    dimensionId: DimensionId,
): MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
    protocolData = resolvedMinecraftWorld.protocolData,
    initialDimensionId = dimensionId,
    dimensionIds = resolvedMinecraftWorld.dimensions.keys,
    viewDistance = 12,
    simulationDistance = 8,
)
```

For a mod registry or a registry whose modded network codec differs from vanilla, pass only its projector. A matching
registry ID replaces the vanilla default; a new ID extends it:

```kotlin
fun resolveModdedProtocolData(
    dataPackStack: DataPackStack,
    modDataPackRegistryProjector: DataPackRegistryProjector,
): ResolvedProtocolData = dataPackStack.toVanillaProtocolData(
    dataPackRegistryProjectorOverrides = listOf(modDataPackRegistryProjector),
)
```

For full control, construct a `DataPackProtocolProjector` or `ResolvedProtocolData` directly.
[`protocol-datapack`](../protocol-datapack/README.md) explains the stages, and
[`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md) documents the release-matched defaults.

## Convert semantic Chunks to packets

`Chunk<ProtocolBlockState, ProtocolRegistryEntry>` is the common in-memory value produced by the resolved disk path and
accepted by the server encoder. A `MinecraftChunkContext` creates a validated encoder without making the caller pass its
registry context, layout, and skylight flag back separately:

```kotlin
fun encodeChunks(
    minecraftChunkContext: MinecraftChunkContext,
    chunks: Iterable<Chunk<ProtocolBlockState, ProtocolRegistryEntry>>,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
): List<ChunkDataAndUpdateLightPacket> {
    val minecraftChunkPacketEncoder = minecraftChunkContext.packetEncoder(
        isAir = isAir,
        hasFluid = hasFluid,
    )
    return chunks.map(minecraftChunkPacketEncoder::encodePacket)
}
```

`isAir` and `hasFluid` are game-content policies and remain caller-supplied; they cannot be inferred from numeric
registry IDs. The optional block-entity update-tag policy is supplied at the same boundary. Palette packing, lighting,
block-entity update tags, and the semantic Chunk-to-clientbound-packet projection remain owned by this module. Encoding
uses the common heightmaps and lighting in `ChunkMetadata`; it neither needs nor invents optional
`ChunkStorageMetadata`, so generated and client-derived semantic Chunks can use the same path.

For the initial view, use `encode()` to create the snapshots consumed by `MinecraftInitialWorld`. The selected
`minecraftChunkContext` must be the same dimension advertised by `minecraftServerNegotiationResult`:

```kotlin
suspend fun sendInitialSemanticWorld(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
    minecraftChunkContext: MinecraftChunkContext,
    chunks: List<Chunk<ProtocolBlockState, ProtocolRegistryEntry>>,
    minecraftEntitySnapshots: List<MinecraftEntitySnapshot>,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
) {
    require(
        minecraftChunkContext.dimensionId ==
                minecraftServerNegotiationResult.minecraftDimensionContext.dimensionId,
    )
    val minecraftChunkPacketEncoder = minecraftChunkContext.packetEncoder(isAir, hasFluid)
    val minecraftInitialWorld = MinecraftInitialWorld(
        minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
            minecraftServerNegotiationResult,
        ),
        chunks = chunks.map(minecraftChunkPacketEncoder::encode),
        entities = minecraftEntitySnapshots,
    )
    minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
    minecraftServerConnection.requestFlush()
}
```

This enqueues the bootstrap, a correctly delimited complete Chunk batch, and the Entity pairing bundles, then publishes
them at the connection's flush boundary. For later batches, map the same encoder's `encodePacket()` over the selected
Chunks and pass those packets to the earlier [`sendChunkBatch`](#stream-chunk-batches-over-ticks) example. Sending a
bare `ChunkDataAndUpdateLightPacket` through `outgoing` is available for custom sequencing, but batch boundaries and
flow-control acknowledgements then remain entirely the caller's responsibility.

`protocol-server` never opens a world path. Load the semantic values with the
[`world-io` disk path](../world-io/README.md#read-computational-world-values-from-disk), keep them in application-owned
world state, and pass them across this projection boundary.

## Send Entity pairing bundles

`MinecraftEntitySnapshot` holds the client-facing state for one Entity's spawn and optional metadata, attributes,
equipment, passengers, and leash relationship:

```kotlin
suspend fun sendEntity(
    minecraftServerConnection: MinecraftServerConnection,
    entity: Entity<NbtCompound>,
    runtimeEntityId: Int,
) {
    val minecraftEntitySnapshot = entity.toMinecraftEntitySnapshot(entityId = runtimeEntityId)
    minecraftServerConnection.sendEntitySnapshot(minecraftEntitySnapshot)
}
```

An `EntityChunk` is the persistence grouping rather than a packet. Its Entities can enter the same projection after the
application assigns connection-local runtime IDs:

```kotlin
suspend fun sendEntityChunk(
    minecraftServerConnection: MinecraftServerConnection,
    entityChunk: EntityChunk<NbtCompound>,
    runtimeEntityId: (Uuid) -> Int,
) {
    val minecraftEntitySnapshots = entityChunk.allEntities().map { entity ->
        entity.toMinecraftEntitySnapshot(entityId = runtimeEntityId(entity.uuid))
    }.toList()
    minecraftServerConnection.sendEntitySnapshots(minecraftEntitySnapshots)
}
```

Persisted Entities do not contain connection-local numeric IDs, protocol metadata indices, current tracking
relationships, or registry-resolved attributes. Supply those values to `toMinecraftEntitySnapshot` when needed. The
basic example sends spawn state; build the snapshots with passenger, leash, metadata, attribute, and equipment fields
when those states are tracked. `sendEntitySnapshots` places several pairing sequences into one logical bundle without
channel-level interleaving. Both helpers enqueue packets but do not flush; publish them at the application's tick
boundary with `requestFlush()`, or include the snapshots in `MinecraftInitialWorld` as shown above. `PoiChunk` remains a
server-side storage state and has no direct clientbound packet.

## Loader profiles and custom packets

Build one shareable connection definition for all possible extension codecs, then create the small profile state per
connection. The first function receives the application-lifetime codec, format, and selector values:

```kotlin
suspend fun bindNeoForgeServer(
    selectorManager: SelectorManager,
    applicationPacketCodecs: List<PacketCodecRegistration<out Packet>>,
    applicationProtocolFormat: MinecraftProtocolFormat,
): MinecraftServer {
    val minecraftConnectionDefinition = NeoForgeProtocol.connectionDefinition(
        extensionCodecs = applicationPacketCodecs,
        minecraftProtocolFormat = applicationProtocolFormat,
    )
    return MinecraftServer.bind(
        selectorManager = selectorManager,
        minecraftConnectionDefinition = minecraftConnectionDefinition,
    )
}
```

For each connection accepted from that server, supply the prepared NeoForge profile definition and application policy:

```kotlin
suspend fun negotiateNeoForge(
    minecraftServerConnection: MinecraftServerConnection,
    neoForgeServerProfileDefinition: NeoForgeServerProfileDefinition,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult? = minecraftServerConnection.negotiate(
    serverNegotiationProfile = NeoForgeServerProfile(neoForgeServerProfileDefinition),
    minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy = minecraftServerNegotiationPolicy,
)
```

Fabric and Forge equivalents are documented in [`protocol-session`](../protocol-session/README.md#negotiation-profiles).

## Custom negotiation and connection lifetime

Applications may replace the preset with their own Status/Login/Configuration sequence over the public typed connection.
The maintained
[`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerProtocol.kt) is
the source-level ordering reference.

A custom flow controls the state-specific KeepAlive run explicitly. After Login acknowledgement and the transition to
Configuration, call `enableConfigurationKeepAlive()`. After receiving `AcknowledgeFinishConfigurationPacket`, disable
that run and call `enablePlayKeepAlive()` before sending the first Play packet. Reconfiguration performs the reverse
switch after `AcknowledgeConfigurationPacket`, then restores Play after the next finish acknowledgement:

```kotlin
fun beginReconfigurationKeepAlive(minecraftServerConnection: MinecraftServerConnection) {
    minecraftServerConnection.disableKeepAlive()
    minecraftServerConnection.enableConfigurationKeepAlive()
}

fun finishReconfigurationKeepAlive(minecraftServerConnection: MinecraftServerConnection) {
    minecraftServerConnection.disableKeepAlive()
    minecraftServerConnection.enablePlayKeepAlive()
}
```

Each enable call replaces any active run and starts with no pending challenge. Do not manually enqueue a KeepAlive
packet for the same state while its managed run is active. Mods with a different packet pair can call the lower-level
`enableKeepAlive(extractChallenge, createRequest, interval)`: the extractor returns `null` for unrelated serverbound
packets, and the request factory places the generated challenge in a clientbound packet. This mapping does not inspect
the connection state, so its lifecycle remains explicit at the call site.

After Play begins, enqueue ordered packets through `outgoing` and call `requestFlush()` at a tick boundary. A full
channel is application backpressure policy; `trySend` lets a tick loop detect it without suspending. Closing the
connection stops its packet pumps and transport. Managed KeepAlive requests use the same writer but flush themselves;
they do not depend on the application's tick-boundary flush.
