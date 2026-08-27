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
`StatusPongResponsePacket` as `statusPongResponsePacket`. Status cannot continue into Login; close it and open a new
connection when joining.

## Enter Play

The preset negotiation handles compression, optional encryption, cookies, Login queries, client information, Known
Packs, synchronized registries, tags, and Finish Configuration. The repository's
[client quick start](../README.md#connect-a-client) owns the default offline example; this guide focuses on the options
and results beyond that path.

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

### Configure negotiation

`MinecraftClientNegotiationOptions` controls the client information, protocol data, cookies, accepted Known Packs, Code
of Conduct decision, resource-pack response, local static registries, and handling of unrecognized negotiation queries.
Here `minecraftClientConnection` is a fresh Handshake-state value returned by `MinecraftClientConnection.connect`:

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

The negotiation result supplies the `ChunkLayout` for the dimension selected by Play Login. Combine it with the
installed registry context to decode `ChunkDataAndUpdateLightPacket` into a semantic `Chunk`:

`MinecraftClientNegotiationResult.chunkLayout` delegates the canonical dimension conversion to
[`protocol-datapack`](../protocol-datapack/README.md#adapt-protocol-context-to-semantic-chunks). The result remains a
client convenience describing the initial Play dimension; create a new layout and decoder after a dimension change.

```kotlin
fun createChunkDecoder(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    expectedDataVersion: Int,
): MinecraftChunkPacketDecoder = MinecraftChunkPacketDecoder(
    protocolRegistryContext = minecraftClientConnection.protocolRegistryContext,
    chunkLayout = minecraftClientNegotiationResult.chunkLayout,
    chunkMetadata = ChunkMetadata(
        dataVersion = expectedDataVersion,
        status = ChunkMetadata.FULLY_GENERATED_STATUS,
    ),
)

fun decodeChunk(
    chunkDataAndUpdateLightPacket: ChunkDataAndUpdateLightPacket,
    minecraftChunkPacketDecoder: MinecraftChunkPacketDecoder,
): Chunk<ProtocolBlockState, ProtocolRegistryEntry> =
    chunkDataAndUpdateLightPacket.toChunk(minecraftChunkPacketDecoder)
```

The caller supplies the metadata template because network Chunk packets do not carry persistence-only fields such as
data version, generation status, inhabited time, or scheduled ticks. Packet heightmaps, block entities, lighting,
position, palettes, and biomes replace the corresponding values during decoding.

The decoder's registry adapter uses the active `ProtocolRegistryContext` through the same shared
`protocol-datapack` entry point. Recreate it after Configuration, reconfiguration, or another context replacement.

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
