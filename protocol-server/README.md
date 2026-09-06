# protocol-server

`protocol-server` accepts Minecraft: Java Edition connections and provides preset orchestration for Status or Login
through entry into Play.

It is intended for applications that want to control their own server behavior. The module supplies protocol negotiation
and optional finite initial-world packets, but it does not implement ticking, gameplay, persistence, permissions, player
management, or an authoritative world.

## Accept connections

`MinecraftServer` binds a Ktor TCP listener. The application owns the accept loop and connection concurrency. The
[server quick start](../README.md#server-accept-client-connections) shows that lifetime with offline vanilla defaults;
the
[stored-world example](../README.md#read-and-send-world-data) connects it to disk reads and initial Chunk sending.

`bind()` is the maintained socket-creation path. The public `MinecraftServer` constructor also accepts an already-bound
`ServerSocket` and its connection, authentication, transport, and dispatcher configuration. A public
`MinecraftServerConnection` constructor can wrap a caller-supplied `MinecraftServerPacketConnection` for custom endpoint
implementations without changing the negotiation layer.

`accept()` returns a typed connection without starting negotiation. `negotiate()` answers a Status exchange and returns
`null` after closing that Status connection, or completes Login and Configuration and returns
`MinecraftServerNegotiationResult` for an open Play connection after sending the first `ClientboundLoginPacket`. It
deliberately does not send the initial-world bootstrap, Chunks, or Entities.

World contents, terrain generation and player abilities are application inputs.

Negotiation exclusively uses both packet channels until it returns. Run it in one coroutine without concurrent readers
or writers. The preset has no built-in admission timeout; apply the application's own deadline around the call.

## Offer a resource pack

The matching official dedicated server configures one optional pack in `server.properties`. The protocol and official
client can accept multiple packs, but this preset follows the dedicated server's single-pack Configuration flow.
Use explicit Configuration tasks or your own packet loop for additional packs.

Construct the existing packet value as configuration; the library does not host or download the URL. `required`
corresponds to `require-resource-pack`. `prompt` appears in the client's acceptance dialog; the separate rejection
reason is the disconnect message when a required pack is declined:

```kotlin
val resourcePack = ClientboundResourcePackPushPacket(
    id = Uuid.parse("45139d19-71dc-4bb5-bf9c-642bbf94f3c5"),
    url = "https://example.com/server-resources.zip",
    hash = "", // Supply the archive's SHA-1 when available.
    required = true,
    prompt = TextComponent.literal("This server uses custom textures."),
)
val resourcePackRejectionReason = TextComponent.literal("Enable server resource packs to join this server.")
```

`Uuid` is from `kotlin.uuid`, packet classes from `com.hiczp.minecraft.protocol.model.packet`, and `TextComponent` from
`com.hiczp.minecraft.protocol.model.type`.

### Convenience API: include the pack in negotiation

Use the values above and a fresh connection returned by `MinecraftServer.accept()`. Invoke this application helper
inside that connection's `use` block so exceptions also close it:

```kotlin
suspend fun negotiateWithResourcePack(
    minecraftServerConnection: MinecraftServerConnection,
    resourcePack: ClientboundResourcePackPushPacket,
    resourcePackRejectionReason: TextComponent,
): MinecraftServerNegotiationResult? = try {
    minecraftServerConnection.negotiate(
        minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            resourcePack = resourcePack,
            resourcePackRejectionReason = resourcePackRejectionReason,
        ),
    )
} catch (failure: MinecraftConfigurationRejectedException) {
    minecraftServerConnection.outgoing.send(failure.failurePacket)
    minecraftServerConnection.requestFlush()
    throw failure
}
```

Without a configured pack, negotiation is unchanged. With one, `ACCEPTED` and `DOWNLOADED` keep waiting. As in the
official server, declining a required pack throws `MinecraftConfigurationRejectedException`; all other terminal
responses, including download/reload failure, permit continuation. The default rejection reason uses the official
translation key. Negotiation never reports success on behalf of the client and never converts these failures into a
stricter admission policy. A non-null result still means Configuration has finished and the first Play Login has been
sent; initial-world sending follows as described below. The library leaves rejection packets and closing to the caller.

### Plain API: exchange the packets

For application-owned negotiation, first complete Login and enter Configuration through your packet loop. The same
`resourcePack` and rejection reason above can be used directly. `handleOtherPacket` is your handler for unrelated
incoming packets while waiting; this helper owns receiving for the duration of the exchange:

```kotlin
suspend fun offerResourcePack(
    minecraftServerConnection: MinecraftServerConnection,
    resourcePack: ClientboundResourcePackPushPacket,
    resourcePackRejectionReason: TextComponent,
    handleOtherPacket: suspend (ServerboundPacket) -> Unit,
): ServerboundResourcePackPacket.Action {
    minecraftServerConnection.outgoing.send(resourcePack)
    minecraftServerConnection.requestFlush()
    while (true) {
        val serverboundPacket = minecraftServerConnection.incoming.receive()
        if (serverboundPacket !is ServerboundResourcePackPacket || serverboundPacket.id != resourcePack.id) {
            handleOtherPacket(serverboundPacket)
            continue
        }
        if (resourcePack.required && serverboundPacket.action == ServerboundResourcePackPacket.Action.DECLINED) {
            throw MinecraftConfigurationRejectedException(resourcePackRejectionReason, "Required resource pack declined")
        }
        if (serverboundPacket.action.isTerminal()) return serverboundPacket.action
    }
}
```

Call this from your manual negotiation instead of concurrently with `negotiate()`. To use `configurationTasks`, return
`ServerNegotiationTaskResult.CONTINUE` for consumed progress, `COMPLETE` for completion, and `PASS` for unrelated
packets. During Play, Push/Pop packets and resource-pack responses also use the ordinary channels; your packet loop owns
their policy. [The client guide](../protocol-client/README.md#handle-resource-packs) shows application-owned downloading
and applying.

## Convert semantic Chunks to packets

Obtain a `Chunk` from `dimension.chunks(chunkNbtDecoder).readChunk(chunkPosition)?.chunk` in
[world-io](../world-io/README.md#read-computational-world-values-from-disk), or construct one as shown in
[world-format](../world-format/README.md#mutable-world-data).
`minecraftServerNegotiationResult` below is the non-null result of `negotiate()` on the accepted connection.
The application supplies `blockEntityUpdateTag: (BlockEntity) -> NbtCompound?`, selecting the public update tag for each
Block Entity, and `sectionStatistic: (Chunk, Int, SectionStatistic) -> Int`, calculating missing counts for the Chunk
and
absolute Section Y. The example requires stored heights and omits unavailable light. Air/plains are explicit application
defaults. Prepare the shared choices once:

```kotlin
val defaultBlockState = BlockState(BlockId("minecraft:air"))
val defaultBiome = BiomeId("minecraft:plains")
val chunkPacketWriteMappings = ChunkPacketWriteMappings(blockEntityUpdateTag)
val chunkPacketRequiredDataProvider = ChunkPacketRequiredDataProvider.RequirePresent.copy(
    sectionStatistic = sectionStatistic,
)
```

### Plain API: construct the encoder

The retained dimension supplies layout/light facts and the registry mapping for this negotiation:

```kotlin
val minecraftDimensionContext = minecraftServerNegotiationResult.minecraftDimensionContext
val chunkPacketEncoder = ChunkPacketEncoder(
    ChunkPacketEncoderContext(
        chunkLayout = minecraftDimensionContext.chunkLayout,
        hasSkyLight = minecraftDimensionContext.minecraftDimensionLayout.hasSkyLight,
        defaultBlockState = defaultBlockState,
        defaultBiome = defaultBiome,
        packetCodecContext = minecraftDimensionContext.packetCodecContext,
        chunkPacketWriteMappings = chunkPacketWriteMappings,
        chunkPacketRequiredDataProvider = chunkPacketRequiredDataProvider,
    ),
)
```

### Convenience API: construct it from the negotiation result

Replace the previous block with this call, using the same shared choices. The extension constructs the complete context
without asking for the dimension or registry information again:

```kotlin
val chunkPacketEncoder = minecraftServerNegotiationResult.chunkPacketEncoder(
    defaultBlockState, defaultBiome, chunkPacketWriteMappings, chunkPacketRequiredDataProvider,
)
```

Both paths return `ChunkPacketEncoder`, need no live connection, and can be reused while their inputs remain stable.
The encoder types live in `com.hiczp.minecraft.protocol.world`, defaults and semantic values in
`com.hiczp.minecraft.world.format`, and `NbtCompound` in `com.hiczp.minecraft.nbt`.
Use either encoder to convert the Chunks:

```kotlin
fun encodeChunks(
    chunks: List<Chunk>,
    chunkPacketEncoder: ChunkPacketEncoder,
): List<ClientboundLevelChunkWithLightPacket> = chunks.map(chunkPacketEncoder::encode)
```

The equivalent extension call for one Chunk is `chunk.toClientboundLevelChunkWithLightPacket(chunkPacketEncoder)`.
The encoder uses stored counts/heights/light when present and invokes the configured callbacks for missing required
values. It performs no gameplay calculation or input mutation. `MinecraftInitialWorld` can retain the same Chunks and
encoder and perform this conversion when sending.

## Send only the bootstrap

`MinecraftServerNegotiationResult` comes from `negotiate()` on the accepted connection. This example constructs a
bootstrap using its dimension and distances. Spawn coordinates, difficulty and ability values are explicit choices for
this example application; a real server supplies values appropriate to its world and player:

```kotlin
fun createBootstrap(minecraftServerNegotiationResult: MinecraftServerNegotiationResult): MinecraftInitialWorldBootstrap {
    val login = minecraftServerNegotiationResult.clientboundLoginPacket
    return MinecraftInitialWorldBootstrap(
        difficulty = Difficulty.NORMAL,
        defaultSpawn = RespawnData(
            GlobalPosition(login.commonPlayerSpawnInfo.dimension, BlockPosition(0, 64, 0)),
            yaw = 0.0f,
            pitch = 0.0f,
        ),
        playerAbilities = PlayerAbilities(
            invulnerable = false, flying = false, canFly = false, instantBuild = false,
            flyingSpeed = 0.05f, walkingSpeed = 0.1f,
        ),
        viewDistance = login.chunkRadius,
        simulationDistance = login.simulationDistance,
        playerPosition = PositionMoveRotation(
            position = Vector3d(0.5, 64.0, 0.5),
            deltaMovement = Vector3d(0.0, 0.0, 0.0),
            yaw = 0.0f,
            pitch = 0.0f,
        ),
    )
}
```

`BlockPosition`, `GlobalPosition`, `RespawnData`, `PlayerAbilities`, `PositionMoveRotation` and `GameMode` here are
packet values in
`com.hiczp.minecraft.protocol.model.type`. To send only the bootstrap, pass the returned value to
`minecraftServerConnection.sendInitialWorldBootstrap(...)`, then call `requestFlush()`.

The bootstrap sends difficulty, spawn, abilities, render/simulation distances, player position, the start-loading game
event and the center Chunk. Its `teleportId` identifies the expected `ServerboundAcceptTeleportationPacket`. It sends
no terrain or Entities. The constructor also works without a negotiation result when distances are supplied directly.

## Enter Play and send a finite world

After negotiation, prepare `chunks` by reading through `world-io` or constructing mutable `Chunk` values in
`world-format`. Use the encoder above and `createBootstrap(minecraftServerNegotiationResult)` to construct
`MinecraftInitialWorld(bootstrap, chunks, chunkPacketEncoder)`. Optional Entity batches may be included as a fourth
argument. The connection is the still-open value from the accept loop; `handlePacket` is the application's handler for
each incoming `ServerboundPacket`.

Send in a child coroutine while the parent receives, so bounded channels can progress:

```kotlin
suspend fun serveWorld(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftInitialWorld: MinecraftInitialWorld,
    handlePacket: suspend (ServerboundPacket) -> Unit,
) = coroutineScope {
    launch {
        minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
        minecraftServerConnection.requestFlush()
    }
    for (serverboundPacket in minecraftServerConnection.incoming) {
        handlePacket(serverboundPacket)
    }
}
```

`synchronizeInitialWorld()` enqueues the fixed bootstrap, one complete Chunk batch and every supplied Entity pairing
bundle. It neither flushes nor waits for acknowledgements. `ServerboundAcceptTeleportationPacket` and
`ServerboundChunkBatchReceivedPacket` arrive through `incoming`; the application records their state.

## Stream Chunk batches over ticks

A long-running server normally keeps a per-connection queue of visible Chunks instead of placing the entire view in one
`MinecraftInitialWorld`. On an application tick, when that connection has send quota and room for another in-flight
batch, it selects nearby pending Chunks. Pass the list returned by `encodeChunks(chunks, chunkPacketEncoder)` above to
this sender, using the same open Play connection:

```kotlin
suspend fun sendChunkBatch(
    minecraftServerConnection: MinecraftServerConnection,
    clientboundLevelChunkWithLightPackets: List<ClientboundLevelChunkWithLightPacket>,
) {
    require(clientboundLevelChunkWithLightPackets.isNotEmpty())
    minecraftServerConnection.outgoing.send(ClientboundChunkBatchStartPacket)
    clientboundLevelChunkWithLightPackets.forEach { clientboundLevelChunkWithLightPacket ->
        minecraftServerConnection.outgoing.send(clientboundLevelChunkWithLightPacket)
    }
    minecraftServerConnection.outgoing.send(
        ClientboundChunkBatchFinishedPacket(clientboundLevelChunkWithLightPackets.size),
    )
    minecraftServerConnection.requestFlush()
}
```

The client answers each finished batch with `ServerboundChunkBatchReceivedPacket.desiredChunksPerTick`. The
application's single
`incoming` consumer validates that response, reduces its outstanding-batch count, and uses a finite, policy-bounded form
of the requested rate when granting later tick quotas. A simple controller can allow only one outstanding batch and wait
for its acknowledgement before sending the next one. An official-style controller may later permit a bounded number of
in-flight batches, but it still uses acknowledgements for flow control instead of sending the complete view without
feedback.

Do not add a second `incoming.receive()` loop inside the tick or batch sender. Let the connection's packet handler
update or signal its Chunk-flow state, and let the next eligible tick consume that state. This module supplies the batch
packet models and Chunk encoders; pending visibility, tick scheduling, rate policy, and acknowledgement deadlines belong
to the server application. The [`protocol-client` flow](../protocol-client/README.md#receive-the-initial-world) shows
the matching
consumer and response order.

## Configure the advertised server

`MinecraftServerNegotiationOptions` contains only values used from Handshake through the first Play Login: compression,
Status and transfer behavior, authentication checks, player limits, advertised dimensions, view and simulation distance,
game mode, secure-chat claim, and the `ConfigurationData` sent during Configuration. Initial-world difficulty,
difficulty
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

Preset negotiation starts managed KeepAlive in Configuration and replaces it with a fresh Play run at the
acknowledgement boundary. The endpoint consumes matching replies; applications do not start or answer that service.
[protocol-session](../protocol-session/README.md#managed-keepalive) documents timers, validation and custom mappings.

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

Pass the returned policy directly when negotiating a fresh accepted connection:
`minecraftServerConnection.negotiate(minecraftServerNegotiationPolicy = admissionPolicy(allowedNames))`.
For customized options, pass the `MinecraftServerNegotiationOptions(...)` constructed in `negotiateConfigured` as well.
The module does not read `server.properties` or
provide a whitelist, operator, or permissions system.

If negotiation throws `MinecraftLoginRejectedException`, its `failurePacket` is ready to send. The library leaves the
connection open so the application can decide whether to send that packet and when to close.

The status policy returns the shared `ServerStatus` model; `protocol-serialization` alone turns it into the bounded JSON
protocol string. Override `onlinePlayerCount(...)` for a live count. The default `serverStatus(...)` calls that method
through the policy instance, so an override that delegates to `super.serverStatus(...)` still receives the customized
count. Construct `description` with `JsonTextComponent.literal("My server")`; `currentOnlinePlayers` reads the
application's player count:

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

`DefaultMinecraftServerNegotiationPolicy.createServerStatus(...)` and `createClientboundLoginPacket(...)` expose the
default
builders for a policy that wants to construct either response directly. Keeping them on the existing default-policy
object avoids unscoped builder names and leaves the options class as negotiation data only.

## Online authentication

Offline mode is the default. For online mode, provide a caller-owned Ktor `HttpClient`. The authentication factory
generates a server key pair; use `MinecraftServerAuthentication.Online` directly to supply a restored pair. As in the
root accept example, create a `SelectorManager(Dispatchers.Default)` and keep it open while the server runs. Construct
and configure `HttpClient` with the Ktor engine appropriate to the application's platform before calling this helper:

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

Open `MinecraftWorldAccess.open("world".toPath())` once for the world's lifetime. This function borrows that world,
loads its enabled file packs, completes the stack with datapack-vanilla, and projects Configuration data. Select a
`DimensionId.Overworld` or construct a custom ID with `DimensionId.parse("example:moon")`:

```kotlin
suspend fun negotiationOptions(
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionId: DimensionId,
): MinecraftServerNegotiationOptions {
    val worldDataPackLoadResult = minecraftWorldAccess.dataPacks.readEnabled()
    val configurationData = worldDataPackLoadResult.toVanillaDataPackStack().toVanillaConfigurationData(
        enabledFeatureFlags = worldDataPackLoadResult.enabledFeatureFlags.mapTo(linkedSetOf(), Identifier::parse),
    )
    val worldGenSettingsData = requireNotNull(
        minecraftWorldAccess.data.read<SavedDataFile<WorldGenSettingsData>>(SavedDataId("world_gen_settings")),
    ).data
    val dimensions = configurationData.resolveMinecraftDimensions(worldGenSettingsData)
    val selectedDimension = dimensions.getValue(dimensionId)
    return MinecraftServerNegotiationOptions(
        configurationData = configurationData,
        initialDimensionId = selectedDimension.dimensionId,
        initialDimensionTypeId = selectedDimension.minecraftDimensionLayout.dimensionTypeId,
        dimensionIds = dimensions.keys,
    )
}
```

Pass these options to `negotiate()` on each fresh accepted connection. A dimension ID and its type ID are independent:
`example:moon` may use `minecraft:the_nether`. Both must reach Play Login correctly. Ordinary default negotiation
selects
the vanilla overworld and its type.

The server-negotiable resolver requires synchronized dimension-type raw IDs and rejects inline holders. Domain-only
`resolveWorldChunkContexts` accepts inline types and takes explicit block/biome defaults. See
[protocol-configuration](../protocol-configuration/README.md) for both contracts and
[protocol-configuration-vanilla](../protocol-configuration-vanilla/README.md) for registry projector overrides.

## Send Entity pairing bundles

`MinecraftEntityBatch` takes `entities`, a prebuilt `EntityPacketEncoder`, and an `Entity -> EntityPairingData`
provider.
The provider supplies connection IDs and relationships; the codec reads current Entity properties and emits the finite
per-Entity sequence. Construct Entities directly or read them from an `EntityChunk`; construct the encoder with
`EntityPacketEncoder(EntityPacketEncoderContext(...))` using the mappings described in
[protocol-world](../protocol-world/README.md#entities-and-items). Construct each `EntityPairingData` from the
connection's
tracking ID and relationship state, then pass these inputs to `MinecraftEntityBatch(...)`. The endpoint wraps the
sequences in one logical bundle:

```kotlin
suspend fun sendEntities(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftEntityBatch: MinecraftEntityBatch,
) {
    minecraftServerConnection.sendEntities(minecraftEntityBatch)
    minecraftServerConnection.requestFlush()
}
```

Include the same batches in `MinecraftInitialWorld.entityBatches` for initial visibility. Registration, tracking,
visibility and later updates belong to the server application. Item and attribute mappings are shared with the client
through protocol-world. `EntityChunk` groups persistent Entities; it has no whole-record packet, and neither does
`PoiChunk`.

## Loader profiles and custom packets

Build one shareable connection definition for all possible extension codecs, then create the small profile state per
connection. Create registrations with `PacketCodecRegistration.clientboundCustomPayload(...)` or the other route
factories in [protocol-session](../protocol-session/README.md#register-custom-packets). Construct
`MinecraftPacketPayloadFormat(...)` with application serializers/registry configuration, or use its default companion
value. The selector is the same caller-owned Ktor selector used for ordinary binding:

```kotlin
suspend fun bindNeoForgeServer(
    selectorManager: SelectorManager,
    applicationPacketCodecs: List<PacketCodecRegistration<out Packet>>,
    applicationPacketFormat: MinecraftPacketPayloadFormat,
): MinecraftServer {
    val minecraftConnectionDefinition = NeoForgeProtocol.connectionDefinition(
        extensionCodecs = applicationPacketCodecs,
        minecraftPacketPayloadFormat = applicationPacketFormat,
    )
    return MinecraftServer.bind(
        selectorManager = selectorManager,
        minecraftConnectionDefinition = minecraftConnectionDefinition,
    )
}
```

For each connection accepted from that server, construct `NeoForgeServerProfileDefinition(...)` with the loader's
network configuration, registry snapshot and optional extensions (`NeoForgeServerProfileDefinition()` is the empty
definition). Options and policy come from the configuration examples above, or use `MinecraftServerNegotiationOptions()`
and `DefaultMinecraftServerNegotiationPolicy`:

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
[`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerNegotiation.kt) is
the source-level ordering reference.

A custom flow controls the state-specific KeepAlive run explicitly. After Login acknowledgement and the transition to
Configuration, call `enableKeepAlive()`. After receiving `ServerboundFinishConfigurationPacket`, disable
that run and call `enableKeepAlive()` before sending the first Play packet. Reconfiguration performs the reverse
switch after `ServerboundConfigurationAcknowledgedPacket`, then restores Play after the next finish acknowledgement.

The endpoint methods and custom packet mappings are described in
[protocol-session](../protocol-session/README.md#managed-keepalive).

After Play, enqueue through `outgoing` and use `requestFlush()` at the application's tick boundary. Keep receiving while
producing large batches so bounded channels can make progress. Closing the connection stops both pumps and transport.
