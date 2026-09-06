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

Create a caller-owned Ktor `SelectorManager(Dispatchers.Default)` and keep it open until its connections close. `host`
is the server address and `pingPayload` is any application-selected Long. A Status connection performs one Handshake,
Status request, and Ping/Pong exchange:

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

The result exposes the server's `ClientboundStatusResponsePacket` as `clientboundStatusResponsePacket` and the matching
`ClientboundPongResponsePacket` as `clientboundPongResponsePacket`. The response packet contains a shared, typed
`ServerStatus`;
the client never has to parse the enclosing protocol JSON:

```kotlin
fun advertisedProtocol(minecraftStatusExchange: MinecraftStatusExchange): Int? =
   minecraftStatusExchange.clientboundStatusResponsePacket.status.version?.protocol
```

Status cannot continue into Login; close it and open a new connection when joining.

## Enter Play

The preset negotiation handles compression, optional encryption, cookies, Login queries, client information, Known
Packs, synchronized registries, tags, Finish Configuration, and the first `ClientboundLoginPacket`. The initial-world
bootstrap, Chunk batches and Entities remain on `incoming` for the application to receive. The repository's
[client quick start](../README.md#client-connect-to-a-server) owns the default offline connection lifetime; this guide
continues
from Login into progressive world reception.

`negotiate()` runs in the calling coroutine and exclusively uses both packet channels until it returns. Do not read from
`incoming` or send to `outgoing` from another coroutine during that call. The preset has no built-in admission timeout;
wrap it in the deadline appropriate for the application.

The endpoint consumes and answers direct KeepAlive requests, so the application must not reply again. After Play,
enqueue through `outgoing` and call `requestFlush()` at a tick
boundary. [protocol-session](../protocol-session/README.md)
documents KeepAlive, bundle and flush semantics.

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
   resourcePackResult = ServerboundResourcePackPacket.Action.ACCEPTED,
)

val minecraftClientNegotiationResult = minecraftClientConnection.negotiate(
    minecraftIdentity = MinecraftOfflineIdentity("Player"),
    minecraftClientNegotiationOptions = minecraftClientNegotiationOptions,
)
```

No options object is required for vanilla. The default `configurationData`, static registry schema, and accepted Known
Packs
come from [`protocol-configuration-vanilla`](../protocol-configuration-vanilla/README.md). Pass options only to override
client
behavior or to connect with custom registry/data-pack definitions.

## Online Login

Online Login takes profile values already obtained by a launcher and a caller-owned Ktor `HttpClient` for the Session
Server `/join` request. Obtain the token and profile through [account-auth](../account-auth/README.md), parse the
returned `minecraftProfileResponse.id` with `Uuid.parseHex(minecraftProfileResponse.id)`, and use its `name`.
Construct/configure `HttpClient` with the
application's Ktor engine; pass a fresh connection from `MinecraftClientConnection.connect(...)`:

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

The caller closes the HTTP client after its operations finish. [protocol-auth](../protocol-auth/README.md) documents
the identity and key-exchange APIs used by this flow.

## Use received Configuration data

`MinecraftClientNegotiationResult` retains the received `dataPackConfigurationSnapshot` and the registry context
resolved
for that negotiation in `minecraftDimensionContext`. Use `resolveClientRegistryView()` to query received registries and
tags. This helper accepts a result returned by `negotiate()` and returns the member identifiers of a tag in the selected
registry, or `null` if that tag was not sent. Construct the selectors with `Identifier("minecraft:worldgen/biome")`
and a tag identifier from the server's data packs, for example `Identifier("minecraft:is_overworld")`:

```kotlin
fun registryTagMembers(
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    registryId: Identifier,
    tagId: Identifier,
): List<Identifier>? {
   val clientRegistryView = minecraftClientNegotiationResult.resolveClientRegistryView()
   return clientRegistryView.tag(registryId, tagId)
      ?.registryIdMapEntries
      ?.map { it.id }
}
```

This is synchronous in-memory resolution with no network operation or connection parameter. It also works after the
connection closes. Replacing the connection's registry context later does not change which context this result uses;
use the corresponding new snapshot and context to inspect a later Configuration epoch.

The snapshot retains synchronized registries and feature flags; the resolved view exposes registry entries, block
states, and tags. Neither can contain recipes, loot tables, functions, advancements, or other server-only data-pack
files because Configuration does not transmit them.

For a hand-written Configuration flow, use the explicit snapshot/schema inputs documented in
[protocol-configuration](../protocol-configuration/README.md#resolve-received-configuration).

## Decode Chunk packets

### Plain API: construct the decoder

The `minecraftDimensionContext` input comes from the result of `negotiate()` above. It contains the dimension identity,
synchronized type/layout and registry mappings. Construct a decoder once per dimension and Configuration epoch. This
application chooses air/plains for absent terrain, retains update-tag fields dynamically, and initializes facts absent
from the packet to local empty or unknown values:

```kotlin
fun createChunkDecoder(minecraftDimensionContext: MinecraftDimensionContext): ChunkPacketDecoder = ChunkPacketDecoder(
   ChunkPacketDecoderContext(
      chunkContext = minecraftDimensionContext.chunkContext(
         defaultBlockState = BlockState(BlockId("minecraft:air")),
         defaultBiome = BiomeId("minecraft:plains"),
      ),
      packetCodecContext = minecraftDimensionContext.packetCodecContext,
      chunkPacketReadMappings = ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
      chunkPacketMissingDataProvider = {
         ChunkPacketMissingData(status = "minecraft:full", inhabitedTime = 0, isLightCorrect = false)
      },
   ),
)
```

### Convenience API: construct it from the negotiation result

With `minecraftClientNegotiationResult` returned by `negotiate()` above, the library's `chunkPacketDecoder` extension
constructs the same complete context. Supply only the four application choices; dimension identity, layout and registry
mappings come from the result. This replaces `createChunkDecoder(...)` above:

```kotlin
val chunkPacketDecoder = minecraftClientNegotiationResult.chunkPacketDecoder(
   defaultBlockState = BlockState(BlockId("minecraft:air")),
   defaultBiome = BiomeId("minecraft:plains"),
   chunkPacketReadMappings = ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
   chunkPacketMissingDataProvider = {
      ChunkPacketMissingData(status = "minecraft:full", inhabitedTime = 0, isLightCorrect = false)
   },
)
```

Both paths return `ChunkPacketDecoder` and need no live connection. Use either result in `runPlayPacketLoop` below;
conversion is `chunkPacketDecoder.decode(packet)`, or the `protocol-world` extension
`packet.toChunk(chunkPacketDecoder)`.

These are explicit application choices, not recovered server state. Update tags carry only the server-selected Block
Entity fields; they do not reconstruct private inventories. Saving a received Chunk requires a separately configured
NBT encoder. See [protocol-world](../protocol-world/README.md#chunk-packet-conversion) for each provider's contract.
Replace this decoder after reconfiguration or a dimension change.

### Receive the initial world

The client does not receive one complete world snapshot. It advances through the initial Play stream in order:

1. `negotiate()` consumes `ClientboundLoginPacket` and returns the dimension and registry context needed to decode
   Chunks.
2. Bootstrap packets establish difficulty, spawn, abilities, distances, player position, and the center Chunk. Apply a
   `ClientboundPlayerPositionPacket` before replying with its `ServerboundAcceptTeleportationPacket`.
3. `ClientboundChunkBatchStartPacket` opens a batch. Decode and store each `ClientboundLevelChunkWithLightPacket` as it
   arrives rather
   than waiting for the whole view.
4. `ClientboundChunkBatchFinishedPacket` closes the batch and states its Chunk count. Reply with
   `ServerboundChunkBatchReceivedPacket`, whose `desiredChunksPerTick` tells the server how quickly to send later
   batches.
5. Keep the same single packet loop running for later Chunk batches, Entity bundles, updates, and ordinary Play traffic.
   The library projects complete Chunk packets and Entity pairing bundles; the application applies later incremental
   world packets to its own state.

Continue after `negotiate()` on the same open connection. Pass
either decoder constructed above as `chunkPacketDecoder`. The application callbacks apply a received player position,
store a decoded `Chunk`, and handle
other `ClientboundPacket` values. `desiredChunksPerTick` supplies the application's measured or configured receive rate:

```kotlin
suspend fun runPlayPacketLoop(
   minecraftClientConnection: MinecraftClientConnection,
   chunkPacketDecoder: ChunkPacketDecoder,
   desiredChunksPerTick: () -> Float,
   applyPlayerPosition: suspend (ClientboundPlayerPositionPacket) -> Unit,
   storeChunk: suspend (Chunk) -> Unit,
   handlePacket: suspend (ClientboundPacket) -> Unit,
) {
   var chunkBatchOpen = false
   var receivedChunkCount = 0

   for (clientboundPacket in minecraftClientConnection.incoming) {
      when (clientboundPacket) {
         ClientboundChunkBatchStartPacket -> {
            check(!chunkBatchOpen) { "Received a nested Chunk batch" }
            chunkBatchOpen = true
            receivedChunkCount = 0
         }

         is ClientboundLevelChunkWithLightPacket -> {
            storeChunk(chunkPacketDecoder.decode(clientboundPacket))
            if (chunkBatchOpen) receivedChunkCount++
         }

         is ClientboundChunkBatchFinishedPacket -> {
            check(chunkBatchOpen) { "Received Chunk batch finish without a start" }
            check(clientboundPacket.batchSize == receivedChunkCount) {
               "Received $receivedChunkCount Chunks in a batch declared as ${clientboundPacket.batchSize}"
            }
            chunkBatchOpen = false
            val requestedChunksPerTick = desiredChunksPerTick()
            require(requestedChunksPerTick.isFinite() && requestedChunksPerTick > 0.0f)
            minecraftClientConnection.outgoing.send(
               ServerboundChunkBatchReceivedPacket(requestedChunksPerTick),
            )
            minecraftClientConnection.requestFlush()
         }

         is ClientboundPlayerPositionPacket -> {
            applyPlayerPosition(clientboundPacket)
            minecraftClientConnection.outgoing.send(
               ServerboundAcceptTeleportationPacket(clientboundPacket.id),
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

## Decode Entity pairing bundles

The incoming channel combines delimiter-framed messages into `ClientboundBundlePacket`. Pass a prebuilt shared
`EntityPacketDecoder` to `toEntities`. Construct it with `EntityPacketDecoder(EntityPacketDecoderContext(...))`,
supplying the negotiated packet registry context, application mappings and missing-data provider
described in [protocol-world](../protocol-world/README.md#entities-and-items).
`registerEntity` stores the decoded Entity under its connection-local Int ID; `pendingPacket` receives unresolved tails:

```kotlin
fun applyPairing(
    clientboundBundlePacket: ClientboundBundlePacket,
    entityPacketDecoder: EntityPacketDecoder,
    registerEntity: (Int, Entity) -> Unit,
    pendingPacket: (EntityPacketDecodeResult) -> Unit,
): List<EntityPacketDecodeResult> = clientboundBundlePacket.toEntities(
   entityPacketDecoder, registerEntity, pendingPacket,
)
```

The endpoint registers the spawn before applying its following metadata/equipment/attribute mappings. Connection IDs,
passenger/vehicle/leash resolution and visibility belong to the application. Check `isEntityPairingBundle` before
using this adapter; route unrelated bundles through the ordinary dispatcher.

## Know the client projection boundary

| Received value                                                                | Semantic path                             | Application responsibility                                              |
|-------------------------------------------------------------------------------|-------------------------------------------|-------------------------------------------------------------------------|
| Full Chunk packet                                                             | `ChunkPacketDecoder`                      | Supply omitted facts and retain the returned mutable Chunk              |
| Entity pairing bundle                                                         | `EntityPacketDecoder` and bundle adapters | Register Entities and resolve relation tails                            |
| Container content/slot packets                                                | `ItemStackPacketDecoder` for each slot    | Maintain the active menu separately from Chunk Block Entity update data |
| Block/biome/light/Block Entity updates and Chunk removal                      | Typed packets                             | Apply the update to current Chunk data                                  |
| Later Entity movement, metadata, equipment, attributes, relations and removal | Typed packets                             | Resolve connection IDs and update the current Entity graph              |
| POI                                                                           | No aggregate vanilla client packet        | Keep server POI state or define a custom projection                     |

The library does not maintain a client world or run gameplay. [protocol-world](../protocol-world/README.md) documents
the shared conversions and [the field inventory](../world-format/CHUNK-DATA.md) describes information lost at each
representation boundary.

## Loader profiles and custom packets

Declare possible custom packet codecs in a shareable `MinecraftConnectionDefinition`, then use the matching
per-connection profile. `MinecraftOfflineIdentity("Player")` constructs the identity. Custom
`PacketCodecRegistration` entries are built as shown
in [protocol-session](../protocol-session/README.md#register-custom-packets);
`StaticRegistrySchema(...)` describes the application's local registry definitions. Supply an empty codec list when no
extra packets are needed. Keep the connection open for Play traffic; the returned negotiation data has no I/O lifetime.
The application `play` callback receives that open connection and its negotiation result:

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
`installPacketCodecContext`, `activateExtensionRoutes`, authentication helpers, and profile hooks. The maintained
[`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/client/MinecraftClientNegotiation.kt) is
the complete source-level ordering reference. Endpoint-managed direct KeepAlive replies remain active in a hand-written
flow and must not be duplicated there.

`MinecraftClientConnection` can also wrap a caller-supplied `MinecraftClientPacketConnection` together with its
advertised server address and port. `connect()` remains the maintained socket-creation path, while the public
constructor lets custom transports or endpoint implementations use the same high-level orchestration.

Closing a connection closes its packet pumps and transport. Protocol rejection and transfer exceptions leave a usable
lifetime decision to the caller; framing, transport, and packet-pump failures terminate the connection and remain
visible through channel operations or `awaitClosed()`.
