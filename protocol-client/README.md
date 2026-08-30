# protocol-client

`protocol-client` connects to a Minecraft: Java Edition server and exposes a typed packet connection through Status,
Login, Configuration, and entry into Play.

`MinecraftClientConnection` provides:

- `incoming: ReceiveChannel<ClientboundPacket>`;
- `outgoing: SendChannel<ServerboundPacket>`;
- committed protocol state and the active registry context;
- automatic replies to direct official Configuration and Play KeepAlive requests;
- optional vanilla, Fabric API, NeoForge, or Forge negotiation;
- helpers for turning received registry, Chunk, and Entity packets into useful runtime values.

The module does not implement gameplay or maintain a world. After negotiation, the application owns the packet loop and
any state built from it.

## Query server status

A Status connection performs one Handshake, Status request, and Ping/Pong exchange:

```kotlin
suspend fun queryStatus(
    selectorManager: SelectorManager,
    host: String,
    pingPayload: Long,
): MinecraftStatusExchange = MinecraftClientConnection.connect(
    selectorManager = selectorManager,
    host = host,
).use { minecraftClientConnection ->
    minecraftClientConnection.queryStatus(pingPayload)
}
```

The result exposes the server's `StatusResponsePacket` as `statusResponsePacket` and the matching
`StatusPongResponsePacket` as `statusPongResponsePacket`. The response packet contains a shared, typed `ServerStatus`;
the client never has to parse the enclosing protocol JSON:

```kotlin
fun advertisedProtocol(minecraftStatusExchange: MinecraftStatusExchange): Int? =
    minecraftStatusExchange.statusResponsePacket.status.version?.protocol
```

Status cannot continue into Login; close it and open a new connection when joining.

## Enter Play

The preset negotiation handles compression, optional encryption, cookies, Login queries, client information, Known
Packs, synchronized registries, tags, Finish Configuration, and the first `PlayLoginPacket`. It returns before the
server's initial-world bootstrap, Chunk batches, and Entities. The repository's
[client quick start](../README.md#connect-a-client) owns the default offline connection lifetime; this guide continues
from Login into progressive world reception.

`negotiate()` runs in the calling coroutine and exclusively uses both packet channels until it returns. Do not read from
`incoming` or send to `outgoing` from another coroutine during that call. The preset has no built-in admission timeout;
wrap it in the deadline appropriate for the application.

Direct official Configuration and Play KeepAlive requests are answered automatically by the connection endpoint and do
not appear on `incoming`. The reply uses the connection's writer and is flushed immediately, so application packet loops
must neither send a second reply nor call `requestFlush()` for it. KeepAlive inside a logical clientbound bundle is not
extracted from that bundle.

After Play begins, send packets through `outgoing` and publish queued data with `requestFlush()` at the application's
normal tick boundary. Use the suspending `flush()` only when the caller must wait until all earlier queued packets have
reached the transport's flush boundary.

### Receive the initial world

The client does not receive one complete world snapshot. It advances through the initial Play stream in order:

1. `negotiate()` consumes `PlayLoginPacket` and returns the dimension and registry context needed to decode Chunks.
2. Bootstrap packets establish difficulty, spawn, abilities, distances, player position, and the center Chunk. Apply a
   `SynchronizePlayerPositionPacket` before replying with its `ConfirmTeleportationPacket`.
3. `ChunkBatchStartPacket` opens a batch. Decode and store each `ChunkDataAndUpdateLightPacket` as it arrives rather
   than waiting for the whole view.
4. `ChunkBatchFinishedPacket` closes the batch and states its Chunk count. Reply with
   `ChunkBatchReceivedPacket`, whose `desiredChunksPerTick` tells the server how quickly to send later batches.
5. Keep the same single packet loop running for later Chunk batches, Entity bundles, updates, and ordinary Play traffic.
   The library projects complete Chunk packets and Entity pairing bundles; the application applies later incremental
   world packets to its own state.

This connection-scoped example performs the required replies while leaving player/world storage and throughput
measurement with the application:

```kotlin
suspend fun runPlayPacketLoop(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftOfflineIdentity: MinecraftOfflineIdentity,
    desiredChunksPerTick: () -> Float,
    applyPlayerPosition: suspend (SynchronizePlayerPositionPacket) -> Unit,
    storeChunk: suspend (Chunk<ProtocolBlockState, ProtocolRegistryEntry>) -> Unit,
    handlePacket: suspend (ClientboundPacket) -> Unit,
) {
    val minecraftClientNegotiationResult =
        minecraftClientConnection.negotiate(minecraftOfflineIdentity)
    val minecraftChunkPacketDecoder = minecraftClientNegotiationResult.minecraftDimensionContext
        .createMinecraftChunkContext()
       .packetDecoder()
    var chunkBatchOpen = false
    var receivedChunkCount = 0

    for (clientboundPacket in minecraftClientConnection.incoming) {
        when (clientboundPacket) {
            ChunkBatchStartPacket -> {
                check(!chunkBatchOpen) { "Received a nested Chunk batch" }
                chunkBatchOpen = true
                receivedChunkCount = 0
            }

            is ChunkDataAndUpdateLightPacket -> {
                storeChunk(minecraftChunkPacketDecoder.decode(clientboundPacket))
                if (chunkBatchOpen) receivedChunkCount++
            }

            is ChunkBatchFinishedPacket -> {
                check(chunkBatchOpen) { "Received Chunk batch finish without a start" }
                check(clientboundPacket.batchSize == receivedChunkCount) {
                    "Received $receivedChunkCount Chunks in a batch declared as ${clientboundPacket.batchSize}"
                }
                chunkBatchOpen = false
                val requestedChunksPerTick = desiredChunksPerTick()
                require(requestedChunksPerTick.isFinite() && requestedChunksPerTick > 0.0f)
                minecraftClientConnection.outgoing.send(
                    ChunkBatchReceivedPacket(requestedChunksPerTick),
                )
                minecraftClientConnection.requestFlush()
            }

            is SynchronizePlayerPositionPacket -> {
                applyPlayerPosition(clientboundPacket)
                minecraftClientConnection.outgoing.send(
                    ConfirmTeleportationPacket(clientboundPacket.teleportId),
                )
                minecraftClientConnection.requestFlush()
            }

            else -> handlePacket(clientboundPacket)
        }
    }
}
```

`desiredChunksPerTick` may be a fixed application policy for a simple client or a value derived from measured batch
processing time. The library does not calculate it, store a world, or acknowledge Chunk batches automatically. The
server may keep a bounded number of batches in flight after receiving feedback, so the client must acknowledge every
finished batch and continue processing packets instead of waiting for an end-of-map marker. Entity pairing bundles reach
`handlePacket` and can be decoded with the helper described below. The
[`protocol-server` flow](../protocol-server/README.md#stream-chunk-batches-over-ticks) describes the matching tick-side
queue and acknowledgement state.

### Configure negotiation

`MinecraftClientNegotiationOptions` contains only inputs used during Login, Configuration, and entry into Play: client
information, protocol data, cookies, accepted Known Packs, the Code of Conduct decision, the resource-pack response,
local static registries, and handling of unrecognized negotiation queries. Here `minecraftClientConnection` is a fresh
Handshake-state value returned by `MinecraftClientConnection.connect`:

```kotlin
val minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
    clientInformation = ClientInformation(
        locale = "en_us",
        viewDistance = 12,
        chatMode = ChatMode.ENABLED,
        chatColors = true,
        displayedSkinParts = 0x7F,
        mainHand = MainHand.RIGHT,
        enableTextFiltering = false,
        allowServerListings = true,
        particleStatus = ParticleStatus.ALL,
    ),
    resourcePackResult = ResourcePackResult.ACCEPTED,
)

val minecraftClientNegotiationResult = minecraftClientConnection.negotiate(
    minecraftIdentity = MinecraftOfflineIdentity("Player"),
    minecraftClientNegotiationOptions = minecraftClientNegotiationOptions,
)
```

No options object is required for vanilla. The default `protocolData`, static registry schema, and accepted Known Packs
come from [`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md). Pass options only to override client
behavior or to connect with custom registry/data-pack definitions.

## Online Login

Online Login takes profile values already obtained by a launcher and a caller-owned Ktor `HttpClient` for the Session
Server `/join` request:

```kotlin
suspend fun playOnline(
    minecraftClientConnection: MinecraftClientConnection,
    profileId: Uuid,
    profileName: String,
    minecraftAccessToken: String,
    httpClient: HttpClient,
): MinecraftClientNegotiationResult {
    val minecraftOnlineIdentity = MinecraftOnlineIdentity(
        id = profileId,
        name = profileName,
        accessToken = minecraftAccessToken,
    )
    return minecraftClientConnection.negotiate(
        minecraftIdentity = minecraftOnlineIdentity,
        sessionHttpClient = httpClient,
    )
}
```

The caller configures and closes the `HttpClient`. [`account-auth`](../account-auth/README.md) shows how a launcher
obtains the token and profile; [`protocol-auth`](../protocol-auth/README.md) documents the game identity and
key-exchange APIs.

## Use received Configuration data

`MinecraftClientNegotiationResult.dataPackConfigurationSnapshot` retains the data-pack-related values received during
Configuration. The connection already contains the registry context resolved from those values and the selected profile.

Convert both into a client registry view:

```kotlin
suspend fun useClientRegistryView(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    consume: suspend (ClientRegistryView) -> Unit,
) {
    val clientRegistryView = minecraftClientNegotiationResult.resolveClientRegistryView(minecraftClientConnection)
    consume(clientRegistryView)
}
```

The snapshot retains synchronized registries and feature flags; the resolved view exposes registry entries, block
states, and tags. Neither can contain recipes, loot tables, functions, advancements, or other server-only data-pack
files because Configuration does not transmit them.

For a hand-written Configuration flow, make each source explicit:

```kotlin
fun resolveConfiguration(
    dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    protocolData: ProtocolData,
    staticRegistrySchema: StaticRegistrySchema,
    remoteRegistrySnapshot: RemoteRegistrySnapshot,
): ClientRegistryView = dataPackConfigurationSnapshot.resolveClientRegistryView(
    protocolData = protocolData,
    staticRegistrySchema = staticRegistrySchema,
    remoteRegistrySnapshot = remoteRegistrySnapshot,
)
```

See [`protocol-datapack`](../protocol-datapack/README.md) for all constructible stages.

## Decode Chunk packets

Configuration and Play Login are resolved together during `negotiate()`. The returned `minecraftDimensionContext`
contains the selected `DimensionId`, synchronized dimension-type ID/raw ID, validated layout, and active registries. It
deliberately stops before block and biome defaults because those are semantic codec choices, not negotiation input. For
vanilla data, create the complete Chunk context with its defaults and then create the packet decoder fluently:

```kotlin
fun createChunkDecoder(
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
): MinecraftChunkPacketDecoder {
    val minecraftChunkContext = minecraftClientNegotiationResult.minecraftDimensionContext
        .createMinecraftChunkContext()
   return minecraftChunkContext.packetDecoder()
}

fun decodeChunk(
    chunkDataAndUpdateLightPacket: ChunkDataAndUpdateLightPacket,
    minecraftChunkPacketDecoder: MinecraftChunkPacketDecoder,
): Chunk<ProtocolBlockState, ProtocolRegistryEntry> =
    minecraftChunkPacketDecoder.decode(chunkDataAndUpdateLightPacket)
```

Packet heightmaps, block entities, lighting, position, palettes, and biomes become a directly usable semantic Chunk. Its
`chunkMetadata.chunkStorageMetadata` is null because network Chunk packets do not carry data version, generation status,
inhabited time, scheduled ticks, or other persistence-only fields. Persistent encoding requires the caller to explicitly
reconstruct or merge every omitted storage field and any persistent Block Entity data absent from the server's update
tags; the decoder never invents or implicitly retains that state.

The decoder validates packet Section count and palette IDs against the same context installed on the connection. The
result is the same semantic `Chunk<ProtocolBlockState, ProtocolRegistryEntry>` used by the disk and server paths; the
client does not need a data-pack directory or a separately assembled registry adapter.

`dataPackConfigurationSnapshot`, `resolveClientRegistryView(...)`, `playLoginPacket`, `minecraftDimensionContext`,
`minecraftDimensionLayout`, and `chunkLayout` remain available for inspection and custom decoders. The explicit
`MinecraftChunkPacketDecoder(protocolRegistryContext, chunkCodecContext)` constructor is the low-level entry. Rebuild
the context and decoder after reconfiguration or a dimension change.

For a modded registry without `minecraft:air` or `minecraft:plains`, pass `defaultBlock` and `defaultBiome` to
`createMinecraftChunkContext`. Changing those values never changes the packets sent during negotiation.

## Decode Entity pairing bundles

The typed incoming channel combines delimiter-framed clientbound bundles into `ClientboundBundlePacket`. A pairing
bundle can be converted to one or more semantic Entities:

```kotlin
fun decodeEntities(
    minecraftClientConnection: MinecraftClientConnection,
    clientboundBundlePacket: ClientboundBundlePacket,
): List<Entity<NbtCompound>>? {
    val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(minecraftClientConnection.protocolRegistryContext)
    return clientboundBundlePacket.toEntitiesOrNull(minecraftEntityPacketDecoder)
}
```

The basic form restores registry-resolved type, UUID, position, velocity, and rotation, while leaving subtype data as an
empty `NbtCompound`. Supply a `MinecraftEntityPacketAdapter<E>` when the application needs to register entities and
apply pairing metadata, attributes, equipment, passenger relationships, or leash state to its own runtime type.

`toEntity()` is the strict one-Entity form. `toEntities()` accepts several pairing sequences in one bundle, and the
`OrNull` variants leave unrelated bundles available to the normal packet dispatcher.

## Know the client projection boundary

The connection has already decoded every registered wire payload before it places a typed `ClientboundPacket` on
`incoming`. Semantic projection into the shared world-format values is narrower:

| Received value                                                                                                                           | Current semantic path                  | Result and caller responsibility                                                               |
|------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|------------------------------------------------------------------------------------------------|
| `ChunkDataAndUpdateLightPacket`                                                                                                          | `MinecraftChunkPacketDecoder.decode()` | Produces a complete computational `Chunk`; the application stores it by dimension and position |
| Entity pairing `ClientboundBundlePacket`                                                                                                 | `MinecraftEntityPacketDecoder`         | Produces `Entity` values or invokes a caller adapter for pairing state                         |
| `BlockUpdatePacket`, `UpdateSectionBlocksPacket`, `ChunkBiomesPacket`, `LightUpdatePacket`, `BlockEntityDataPacket`, `UnloadChunkPacket` | No high-level projector                | The application applies the typed update or removes the Chunk from its own world state         |
| Later Entity movement, metadata, equipment, attributes, relationship, and removal packets                                                | No high-level state applier            | The application resolves the runtime Entity ID and updates its own Entity table                |
| POI data                                                                                                                                 | No vanilla clientbound packet          | No client-side `PoiChunk` can be reconstructed from the network                                |

Consequently, the full-Chunk receive path is immediately usable for computation but is not a maintained client-world
mirror. The Entity decoder likewise restores `Entity`, not `EntityChunk`: the latter is an on-disk grouping with a data
version and Chunk position that the network does not transmit losslessly. Applications that need long-lived state keep
one packet consumer, route these typed updates into their own state model, and rebuild the Chunk decoder after a
dimension change or reconfiguration.

## Loader profiles and custom packets

Declare possible custom packet codecs in a shareable `MinecraftConnectionDefinition`, then use the matching
per-connection profile. Keep the connection open while consuming the negotiation result and Play traffic. This Fabric
example makes that lifetime explicit through a caller-supplied `play` block:

```kotlin
suspend fun runFabric(
    selectorManager: SelectorManager,
    host: String,
    minecraftOfflineIdentity: MinecraftOfflineIdentity,
    extensionCodecs: List<PacketCodecRegistration<out Packet>>,
    staticRegistrySchema: StaticRegistrySchema,
    play: suspend (MinecraftClientConnection, MinecraftClientNegotiationResult) -> Unit,
) {
    val minecraftConnectionDefinition = FabricProtocol.connectionDefinition(
        extensionCodecs = extensionCodecs,
    )
    MinecraftClientConnection.connect(
        selectorManager = selectorManager,
        host = host,
        minecraftConnectionDefinition = minecraftConnectionDefinition,
    ).use { minecraftClientConnection ->
        val minecraftClientNegotiationResult = minecraftClientConnection.negotiate(
            minecraftIdentity = minecraftOfflineIdentity,
            clientNegotiationProfile = FabricClientProfile(staticRegistrySchema),
        )
        play(minecraftClientConnection, minecraftClientNegotiationResult)
    }
}
```

NeoForge and Forge definitions and profiles are documented in
[`protocol-session`](../protocol-session/README.md#negotiation-profiles). Unknown valid routes remain lossless
`UnknownPacket.Clientbound` values; malformed registered payloads still fail decoding.

## Custom negotiation and lifetime

Applications may implement their own Handshake/Login/Configuration flow using `incoming`, `outgoing`, `awaitState`,
`installProtocolRegistryContext`, `activateExtensionRoutes`, authentication helpers, and profile hooks. The maintained
[`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/client/MinecraftClientProtocol.kt) is
the complete source-level ordering reference. Endpoint-managed direct KeepAlive replies remain active in a hand-written
flow and must not be duplicated there.

Closing a connection closes its packet pumps and transport. Protocol rejection and transfer exceptions leave a usable
lifetime decision to the caller; framing, transport, and packet-pump failures terminate the connection and remain
visible through channel operations or `awaitClosed()`.
