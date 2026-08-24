# protocol-server

`protocol-server` accepts Minecraft: Java Edition connections and provides preset orchestration for Status or Login
through entry into Play.

It is intended for applications that want to control their own server behavior. The module supplies protocol negotiation
and optional finite initial-world packets, but it does not implement ticking, gameplay, persistence, permissions, player
management, or an authoritative world.

## Accept connections

`MinecraftServer` binds a Ktor TCP listener. The application owns the accept loop and connection concurrency:

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
                    val result = connection.negotiate() ?: return@connectionUse
                    val profile = result.gameProfile

                    for (packet in connection.incoming) {
                        handlePacket(connection, packet)
                    }
                }
            }
        }
    }
}
```

`accept()` returns a typed connection without starting negotiation. `negotiate()` answers a Status exchange and returns
`null`, or completes Login and Configuration and returns `MinecraftServerNegotiationResult` for an open Play connection.

Negotiation exclusively uses both packet channels until it returns. Run it in one coroutine without concurrent readers
or writers. The preset has no built-in admission timeout; apply the application's own deadline around the call.

## Configure the advertised server

`MinecraftServerNegotiationOptions` contains protocol-visible choices such as compression, Status availability, player
limits, view and simulation distance, game mode, difficulty, secure-chat claim, and the `ProtocolDataSet` sent during
Configuration. The `connection` parameter below is a value returned by `MinecraftServer.accept()`:

```kotlin
suspend fun negotiateConfigured(
    connection: MinecraftServerConnection,
): MinecraftServerNegotiationResult? {
    val options = MinecraftServerNegotiationOptions(
        statusDescription = "A Kotlin Minecraft server",
        maximumPlayers = 50,
        viewDistance = 12,
        simulationDistance = 8,
        gameMode = GameMode.CREATIVE,
        difficulty = Difficulty.NORMAL,
    )
    return connection.negotiate(options = options)
}
```

The defaults provide offline vanilla negotiation with the repository-selected release's generated vanilla protocol data.

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
    val authentication = MinecraftServerAuthentication.online(httpClient)
    return MinecraftServer.bind(
        selectorManager = selectorManager,
        authentication = authentication,
    )
}
```

The preset validates Encryption Response, enables stream encryption, and calls the Session Server `/hasJoined` endpoint.
The caller configures and closes the HTTP client. Authentication failure never falls back to offline mode.

## Send custom data-pack Configuration

Pass any constructible `ProtocolDataSet` through `MinecraftServerNegotiationOptions.protocolData`. Applications that use
world file packs can combine [`world-io`](../world-io/README.md) with the vanilla projection helpers:

```kotlin
suspend fun optionsFromWorldPacks(
    world: MinecraftWorldAccess,
    registryProjectors: List<DataPackSynchronizedRegistryProjector>,
): MinecraftServerNegotiationOptions {
    val loaded = world.readEnabledDataPacks()
    val protocolData = loaded.stack.toVanillaProtocolDataSet(registryProjectors)
    return MinecraftServerNegotiationOptions(protocolData = protocolData)
}
```

The generated vanilla core is the projection base. A file pack that changes a synchronized registry needs a matching
disk-JSON-to-network-NBT projector; packs that change only tags or server-only resources do not.

For full control, construct a `DataPackProtocolProjection` or `DataPackProtocolDataSet` directly. [
`protocol-datapack`](../protocol-datapack/README.md) explains the stages, and [
`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md) documents the release-matched defaults.

## Send an initial world

After negotiation enters Play, the application must send whatever bootstrap and world state its client needs.

`MinecraftInitialWorldBootstrap` contains the fixed initial Play packets without Chunks or Entities. The convenience
factory derives ordinary values from the server options:

```kotlin
suspend fun sendBootstrap(
    connection: MinecraftServerConnection,
    options: MinecraftServerNegotiationOptions,
    playerPosition: Vector3d,
) {
    val bootstrap = MinecraftInitialWorldBootstrap.vanilla(
        options = options,
        playerPosition = playerPosition,
    )
    connection.sendInitialWorldBootstrap(bootstrap)
    connection.requestFlush()
}
```

The returned `teleportId` is available to the application when it handles `ConfirmTeleportationPacket`.

For tests, previews, and simple finite views, `MinecraftInitialWorld` adds complete Chunk and Entity snapshots:

```kotlin
suspend fun sendFlatWorld(
    connection: MinecraftServerConnection,
    options: MinecraftServerNegotiationOptions,
) {
    val world = MinecraftInitialWorld.flatVanilla(
        options = options,
        chunkRadius = 1,
    )
    connection.synchronizeInitialWorld(world)
    connection.requestFlush()
}
```

This sends one bootstrap, one complete Chunk batch, and the supplied Entity pairing bundles. It does not wait for
`ChunkBatchReceivedPacket` and does not create a game loop. A long-running server should pace later batches and updates
from its own tick/AOI state.

## Convert semantic Chunks to packets

Applications can load or construct `world-format` Chunks and project them with the connection's active registries:

```kotlin
fun encodeChunks(
    connection: MinecraftServerConnection,
    dimension: MinecraftDimensionLayout,
    chunks: Iterable<Chunk<ProtocolBlockState, ProtocolRegistryEntry>>,
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
): List<MinecraftChunkSnapshot> {
    val encoder = MinecraftChunkPacketEncoder(
        registries = connection.registries,
        isAir = isAir,
        hasFluid = hasFluid,
        hasSkyLight = dimension.hasSkyLight,
    )
    return chunks.map { chunk -> chunk.toMinecraftChunkSnapshot(encoder) }
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
    val snapshot = entity.toMinecraftEntitySnapshot(entityId = runtimeEntityId)
    connection.sendEntitySnapshot(snapshot)
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
    val definition = NeoForgeProtocol.connectionDefinition(
        extensionCodecs = applicationPacketCodecs,
        format = applicationProtocolFormat,
    )
    return MinecraftServer.bind(
        selectorManager = selectorManager,
        definition = definition,
    )
}
```

For each connection accepted from that server, supply the prepared NeoForge profile definition and application policy:

```kotlin
suspend fun negotiateNeoForge(
    connection: MinecraftServerConnection,
    profileDefinition: NeoForgeServerProfileDefinition,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult? = connection.negotiate(
    profile = NeoForgeServerProfile(profileDefinition),
    options = options,
    policy = policy,
)
```

Fabric and Forge equivalents are documented in [`protocol-session`](../protocol-session/README.md#negotiation-profiles).

## Custom negotiation and connection lifetime

Applications may replace the preset with their own Status/Login/Configuration sequence over the public typed connection.
The maintained [
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerProtocol.kt) is the
source-level ordering reference.

After Play begins, enqueue ordered packets through `outgoing` and call `requestFlush()` at a tick boundary. A full
channel is application backpressure policy; `trySend` lets a tick loop detect it without suspending. Closing the
connection stops its packet pumps and transport.
