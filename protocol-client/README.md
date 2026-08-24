# protocol-client

`protocol-client` connects to a Minecraft: Java Edition server and exposes a typed packet connection through Status,
Login, Configuration, and entry into Play.

`MinecraftClientConnection` provides:

- `incoming: ReceiveChannel<ClientboundPacket>`;
- `outgoing: SendChannel<ServerboundPacket>`;
- committed protocol state and the active registry context;
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
).use { connection ->
    connection.queryStatus(pingPayload)
}
```

The result contains the server's `StatusResponsePacket` and matching `StatusPongResponsePacket`. Status cannot continue
into Login; close it and open a new connection when joining.

## Enter Play

The preset negotiation handles compression, optional encryption, cookies, Login queries, client information, Known
Packs, synchronized registries, tags, and Finish Configuration.

```kotlin
suspend fun playOffline(
    selectorManager: SelectorManager,
    host: String,
    handlePacket: suspend (ClientboundPacket) -> Unit,
) {
    MinecraftClientConnection.connect(selectorManager, host).use { connection ->
        val result = connection.negotiate(MinecraftOfflineIdentity("Player"))
        val initialDimension = result.dimensionLayout

        for (packet in connection.incoming) {
            handlePacket(packet)
        }
    }
}
```

`negotiate()` runs in the calling coroutine and exclusively uses both packet channels until it returns. Do not read from
`incoming` or send to `outgoing` from another coroutine during that call. The preset has no built-in admission timeout;
wrap it in the deadline appropriate for the application.

After Play begins, send packets through `outgoing` and publish queued data with `requestFlush()` at the application's
normal tick boundary. Use the suspending `flush()` only when the caller must wait until all earlier queued packets have
reached the transport's flush boundary.

### Configure negotiation

`MinecraftClientNegotiationOptions` controls the client information, protocol data, cookies, accepted Known Packs, Code
of Conduct decision, resource-pack response, local static registries, and handling of unrecognized negotiation queries:

```kotlin
val options = MinecraftClientNegotiationOptions(
    information = ClientInformation(
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

val result = connection.negotiate(
    identity = MinecraftOfflineIdentity("Player"),
    options = options,
)
```

The default `protocolData` is the release-matched vanilla data from [
`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md). Replace it when connecting with custom registry or
data-pack definitions.

## Online Login

Online Login takes profile values already obtained by a launcher and a caller-owned Ktor `HttpClient` for the Session
Server `/join` request:

```kotlin
suspend fun playOnline(
    connection: MinecraftClientConnection,
    profileId: Uuid,
    profileName: String,
    minecraftAccessToken: String,
    httpClient: HttpClient,
): MinecraftClientNegotiationResult {
    val identity = MinecraftOnlineIdentity(
        id = profileId,
        name = profileName,
        accessToken = minecraftAccessToken,
    )
    return connection.negotiate(
        identity = identity,
        sessionHttpClient = httpClient,
    )
}
```

The caller configures and closes the `HttpClient`. [`account-auth`](../account-auth/README.md) shows how a launcher
obtains the token and profile; [`protocol-auth`](../protocol-auth/README.md) documents the game identity and
key-exchange APIs.

## Use received Configuration data

`MinecraftClientNegotiationResult.configuration` retains the data-pack-related packets received during Configuration.
The connection already contains the registry context resolved from those packets and the selected profile.

Convert both into a client runtime view:

```kotlin
suspend fun useConfigurationRuntime(
    connection: MinecraftClientConnection,
    result: MinecraftClientNegotiationResult,
    consume: suspend (ClientDataPackRuntime) -> Unit,
) {
    val runtime = result.toDataPackRuntime(connection)
    consume(runtime)
}
```

The runtime exposes synchronized registries, block states, feature flags, and resolved tags. It cannot contain recipes,
loot tables, functions, advancements, or other server-only data-pack files because Configuration does not transmit them.

For a hand-written Configuration flow, make each source explicit:

```kotlin
fun resolveConfiguration(
    configuration: MinecraftClientConfiguration,
    protocolData: ProtocolDataSet,
    staticRegistries: StaticRegistrySchema,
    remoteRegistries: RemoteRegistrySnapshot,
): ClientDataPackRuntime = configuration.toReceivedDataPackConfiguration().resolveRuntime(
    protocolData = protocolData,
    staticRegistries = staticRegistries,
    remoteRegistries = remoteRegistries,
)
```

See [`protocol-datapack`](../protocol-datapack/README.md) for all constructible stages.

## Decode Chunk packets

The negotiation result supplies the `ChunkLayout` for the dimension selected by Play Login. Combine it with the
installed registry context to decode `ChunkDataAndUpdateLightPacket` into a semantic `Chunk`:

```kotlin
fun createChunkDecoder(
    connection: MinecraftClientConnection,
    result: MinecraftClientNegotiationResult,
    expectedDataVersion: Int,
): MinecraftChunkPacketDecoder = MinecraftChunkPacketDecoder(
    registries = connection.registries,
    layout = result.chunkLayout,
    metadata = ChunkMetadata(
        dataVersion = expectedDataVersion,
        status = ChunkMetadata.FULLY_GENERATED_STATUS,
    ),
)

fun decodeChunk(
    packet: ChunkDataAndUpdateLightPacket,
    decoder: MinecraftChunkPacketDecoder,
): Chunk<ProtocolBlockState, ProtocolRegistryEntry> = packet.toChunk(decoder)
```

The caller supplies the metadata template because network Chunk packets do not carry persistence-only fields such as
data version, generation status, inhabited time, or scheduled ticks. Packet heightmaps, block entities, lighting,
position, palettes, and biomes replace the corresponding values during decoding.

Create a new decoder after changing dimension; the initial result's layout describes only the dimension selected by that
Play Login.

## Decode Entity pairing bundles

The typed incoming channel combines delimiter-framed clientbound bundles into `ClientboundBundlePacket`. A pairing
bundle can be converted to one or more semantic Entities:

```kotlin
fun decodeEntities(
    connection: MinecraftClientConnection,
    bundle: ClientboundBundlePacket,
): List<Entity<NbtCompound>>? {
    val decoder = MinecraftEntityPacketDecoder(connection.registries)
    return bundle.toEntitiesOrNull(decoder)
}
```

The basic form restores registry-resolved type, UUID, position, velocity, and rotation, while leaving subtype data as an
empty `NbtCompound`. Supply a `MinecraftEntityPacketAdapter<E>` when the application needs to register entities and
apply pairing metadata, attributes, equipment, passenger relationships, or leash state to its own runtime type.

`toEntity()` is the strict one-Entity form. `toEntities()` accepts several pairing sequences in one bundle, and the
`OrNull` variants leave unrelated bundles available to the normal packet dispatcher.

## Loader profiles and custom packets

Declare possible custom packet codecs in a shareable `MinecraftConnectionDefinition`, then use the matching
per-connection profile. This Fabric example receives all application-owned values as parameters:

```kotlin
suspend fun connectFabric(
    selectorManager: SelectorManager,
    host: String,
    identity: MinecraftOfflineIdentity,
    extensionCodecs: List<PacketCodecRegistration<out Packet>>,
    staticRegistries: StaticRegistrySchema,
): MinecraftClientNegotiationResult {
    val definition = FabricProtocol.connectionDefinition(
        extensionCodecs = extensionCodecs,
    )
    return MinecraftClientConnection.connect(
        selectorManager = selectorManager,
        host = host,
        definition = definition,
    ).use { connection ->
        connection.negotiate(
            identity = identity,
            profile = FabricClientProfile(staticRegistries),
        )
    }
}
```

NeoForge and Forge definitions and profiles are documented in [
`protocol-session`](../protocol-session/README.md#negotiation-profiles). Unknown valid routes remain lossless
`UnknownPacket.Clientbound` values; malformed registered payloads still fail decoding.

## Custom negotiation and lifetime

Applications may implement their own Handshake/Login/Configuration flow using `incoming`, `outgoing`, `awaitState`,
`installRegistryContext`, `activateExtensionRoutes`, authentication helpers, and profile hooks. The maintained [
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/client/MinecraftClientProtocol.kt) is the
complete source-level ordering reference.

Closing a connection closes its packet pumps and transport. Protocol rejection and transfer exceptions leave a usable
lifetime decision to the caller; framing, transport, and packet-pump failures terminate the connection and remain
visible through channel operations or `awaitClosed()`.
