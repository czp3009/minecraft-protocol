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
`MinecraftServerNegotiationResult` for an open Play connection.

The root quick start uses the built-in vanilla, offline, transport, Configuration, and policy defaults.

Negotiation exclusively uses both packet channels until it returns. Run it in one coroutine without concurrent readers
or writers. The preset has no built-in admission timeout; apply the application's own deadline around the call.

## Configure the advertised server

`MinecraftServerNegotiationOptions` contains protocol-visible choices such as compression, Status availability, player
limits, view and simulation distance, game mode, difficulty, secure-chat claim, and the `ProtocolData` sent during
Configuration. The `connection` parameter below is a value returned by `MinecraftServer.accept()`:

```kotlin
suspend fun negotiateConfigured(
    connection: MinecraftServerConnection,
): MinecraftServerNegotiationResult? {
    val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
        statusDescription = "A Kotlin Minecraft server",
        maximumPlayers = 50,
        viewDistance = 12,
        simulationDistance = 8,
        gameMode = GameMode.CREATIVE,
        difficulty = Difficulty.NORMAL,
    )
    return connection.negotiate(options = minecraftServerNegotiationOptions)
}
```

The defaults provide offline vanilla negotiation with the repository-selected release's generated vanilla protocol data.

Preset negotiation also manages the official server KeepAlive lifecycle. It starts a Configuration run after Login is
acknowledged, replaces it with a fresh Play run before the first Play packet, and defaults each run to a 15-second
interval. Matching replies are validated and consumed. A pending challenge at the next interval, an unsolicited reply,
or a mismatched reply closes the connection; closing the connection also cancels the run. Applications using
`negotiate()` need neither start this service nor handle its reply packets themselves.

Use `MinecraftServerNegotiationPolicy` for decisions that vary by connection: Status JSON, profile rejection, Play
Login, extra Configuration packets, response-gated Configuration tasks, and unknown query handling. Every method has a
default implementation, so a policy can override only what it needs. Here `allowedNames` is supplied by the
application's admission service:

```kotlin
fun admissionPolicy(allowedNames: Set<String>): MinecraftServerNegotiationPolicy =
    object : MinecraftServerNegotiationPolicy {
        override suspend fun profileRejection(
            gameProfile: GameProfile,
            transferred: Boolean,
            options: MinecraftServerNegotiationOptions,
        ): JsonTextComponent? = if (gameProfile.name in allowedNames) {
            null
        } else {
            JsonTextComponent(
                buildJsonObject { put("text", "Not allowed") }.toString(),
            )
        }
    }
```

Call `admissionPolicy(allowedNames)` with the application's current set, then pass the result to
`connection.negotiate(options = options, policy = policy)`. The module does not read `server.properties` or provide a
whitelist, operator, or permissions system.

If negotiation throws `MinecraftLoginRejectedException`, its `failurePacket` is ready to send. The library leaves the
connection open so the application can decide whether to send that packet and when to close.

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
        authentication = minecraftServerAuthentication,
    )
}
```

The preset validates Encryption Response, enables stream encryption, and calls the Session Server `/hasJoined` endpoint.
The caller configures and closes the HTTP client. Authentication failure never falls back to offline mode.

## Send custom data-pack Configuration

Pass any constructible `ProtocolData` through `MinecraftServerNegotiationOptions.protocolData`. Applications that use
world file packs can combine [`world-io`](../world-io/README.md) with the vanilla projection helpers:

```kotlin
suspend fun optionsFromWorldPacks(
    world: MinecraftWorldAccess,
): MinecraftServerNegotiationOptions {
    val worldDataPackLoadResult = world.readEnabledDataPacks()
    val protocolData = worldDataPackLoadResult.dataPackStack.toVanillaProtocolData()
    return MinecraftServerNegotiationOptions(protocolData = protocolData)
}
```

The generated vanilla core is the projection base, and release-matched defaults project every vanilla synchronized
registry. Tags are projected generically. Recipes, functions, loot tables, and other server-only resources remain in the
resolved data-pack stack and are not emitted as Configuration values.

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

## Send an initial world

After negotiation enters Play, the application must send whatever bootstrap and world state its client needs.

`MinecraftInitialWorldBootstrap` contains the fixed initial Play packets without Chunks or Entities. The convenience
factory derives ordinary values from the server options:

```kotlin
suspend fun sendBootstrap(
    connection: MinecraftServerConnection,
    playerPosition: Vector3d,
) {
    val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
        playerPosition = playerPosition,
    )
    connection.sendInitialWorldBootstrap(minecraftInitialWorldBootstrap)
    connection.requestFlush()
}
```

The bootstrap's `teleportId` is available to the application when it handles `ConfirmTeleportationPacket`. If
negotiation used non-default server options, pass that same options value to `vanilla` so the bootstrap uses the
matching game mode, difficulty, and distances.

For tests, previews, and simple finite views, `MinecraftInitialWorld` adds complete Chunk and Entity snapshots:

```kotlin
suspend fun sendFlatWorld(
    connection: MinecraftServerConnection,
) {
    val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
        chunkRadius = 1,
    )
    connection.synchronizeInitialWorld(minecraftInitialWorld)
    connection.requestFlush()
}
```

This sends one bootstrap, one complete Chunk batch, and the supplied Entity pairing bundles. It does not wait for
`ChunkBatchReceivedPacket` and does not create a game loop. A long-running server should pace later batches and updates
from its own tick/AOI state. Pass the negotiation options explicitly when they were customized.

## Convert semantic Chunks to packets

Applications can load or construct `world-format` Chunks and project them with the connection's active registries:

```kotlin
fun encodeChunks(
    connection: MinecraftServerConnection,
    minecraftDimensionLayout: MinecraftDimensionLayout,
    chunks: Iterable<Chunk<ProtocolBlockState, ProtocolRegistryEntry>>,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
): List<MinecraftChunkSnapshot> {
    val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
        protocolRegistryContext = connection.protocolRegistryContext,
        isAir = isAir,
        hasFluid = hasFluid,
        hasSkyLight = minecraftDimensionLayout.hasSkyLight,
    )
    return chunks.map { chunk -> chunk.toMinecraftChunkSnapshot(minecraftChunkPacketEncoder) }
}
```

`MinecraftChunkSnapshot.packet()` returns the corresponding `ChunkDataAndUpdateLightPacket`. `protocol-server` itself
never opens a world path; use [`world-io`](../world-io/README.md) to load stored Chunks, then pass the semantic values
through the same encoder.

## Send Entity pairing bundles

`MinecraftEntitySnapshot` holds the client-facing state for one Entity's spawn and optional metadata, attributes,
equipment, passengers, and leash relationship:

```kotlin
suspend fun sendEntity(
    connection: MinecraftServerConnection,
    entity: Entity<NbtCompound>,
    runtimeEntityId: Int,
) {
    val minecraftEntitySnapshot = entity.toMinecraftEntitySnapshot(entityId = runtimeEntityId)
    connection.sendEntitySnapshot(minecraftEntitySnapshot)
}
```

Persisted Entities do not contain connection-local numeric IDs, protocol metadata indices, current tracking
relationships, or registry-resolved attributes. Supply those values to `toMinecraftEntitySnapshot` when needed.
`sendEntitySnapshots` places several pairing sequences into one logical bundle without channel-level interleaving.

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
        format = applicationProtocolFormat,
    )
    return MinecraftServer.bind(
        selectorManager = selectorManager,
        definition = minecraftConnectionDefinition,
    )
}
```

For each connection accepted from that server, supply the prepared NeoForge profile definition and application policy:

```kotlin
suspend fun negotiateNeoForge(
    connection: MinecraftServerConnection,
    neoForgeServerProfileDefinition: NeoForgeServerProfileDefinition,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult? = connection.negotiate(
    profile = NeoForgeServerProfile(neoForgeServerProfileDefinition),
    options = options,
    policy = policy,
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
fun beginReconfigurationKeepAlive(connection: MinecraftServerConnection) {
    connection.disableKeepAlive()
    connection.enableConfigurationKeepAlive()
}

fun finishReconfigurationKeepAlive(connection: MinecraftServerConnection) {
    connection.disableKeepAlive()
    connection.enablePlayKeepAlive()
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
