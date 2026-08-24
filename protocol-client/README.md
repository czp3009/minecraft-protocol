# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

`MinecraftClientConnection` is a typed packet connection exposing `ReceiveChannel<ClientboundPacket>`,
`SendChannel<ServerboundPacket>`, committed protocol state, active registries, and extension routes. It does not expose
the raw socket or frame stream.

Each connection performs one Status or Login handshake. To ping a server as the multiplayer server list does, call
`queryStatus()`; it obtains the Status response and completes the Ping/Pong exchange without running `negotiate()`:

```kotlin
suspend fun queryMinecraftStatus(selectorManager: SelectorManager): MinecraftStatusExchange =
    MinecraftClientConnection.connect(
        selectorManager = selectorManager,
        host = "127.0.0.1",
    ).use { minecraftClientConnection ->
        minecraftClientConnection.queryStatus()
    }
```

Status cannot continue into Login. Close the Status connection after the ping and create a fresh connection before
calling `negotiate()` to join the server.

The preset `negotiate` extension supports offline or online Login, cookies and custom queries, compression/encryption,
Configuration, dynamic registry context, optional loader profiles, and Play entry. Afterward the application owns the
packet loop:

```kotlin
suspend fun enterPlay(
    minecraftClientConnection: MinecraftClientConnection,
    handlePlayPacket: suspend (ClientboundPacket) -> Unit,
): MinecraftClientNegotiationResult {
    val minecraftClientNegotiationResult = minecraftClientConnection.negotiate(MinecraftOfflineIdentity("Player"))
    for (packet in minecraftClientConnection.incoming) {
        handlePlayPacket(packet)
    }
    return minecraftClientNegotiationResult
}
```

`negotiate` runs sequentially in the calling coroutine and exclusively borrows `incoming` and `outgoing` until it
returns. Give that coroutine sole ownership of both channels for the complete negotiation.

The preset does not impose a phase packet count or timeout. A required response ends the current phase; connection
failure or coroutine cancellation ends the wait. Apply application-specific deadlines around `negotiate` when needed.

The same ownership rule applies when an application implements negotiation itself. Keep the entire
Handshake/Login/Configuration sequence and its channel reads and writes in one coroutine. After negotiation hands the
Play connection back, the application may establish its packet-loop ownership model.

The connection's reader, writer, and requested flushes use the dispatcher from `selectorManager` by default. Override
`connectionDispatcher` on `connect` when those per-connection jobs should use a different dispatcher. Sending to
`outgoing` only enters its configured channel; it does not make the tick coroutine perform socket I/O. At the end of a
client tick, enqueue the complete ordered packet batch and call `requestFlush()`. Use `trySend` when the tick must
detect a full outgoing queue without suspension and apply its own slow-connection policy.

The retained Configuration packets can also be converted to a `ClientDataPackRuntime`. The convenient
`negotiationResult.toDataPackRuntime(connection)` path uses the authoritative registry context already installed by the
vanilla or loader profile, then resolves every received tag to registry entries. For a hand-written negotiation,
`MinecraftClientConfiguration.toReceivedDataPackConfiguration` exposes the intermediate constructible value and
`resolveRuntime` accepts caller-supplied static block schemas and loader registry mappings. See
[`protocol-datapack`](../protocol-datapack/README.md#resolve-client-configuration-data). The preset's default base data
comes from `VanillaDataPacks.protocolData` in
[`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md); custom and modded clients can replace it through
`MinecraftClientNegotiationOptions.protocolData` and supply their own static or remote registry mappings.

## Receive Configuration data and build the runtime

The preset retains every data-pack-related Configuration packet and installs the resolved registry context before it
returns. Convert the result while the connection is open; the callback receives both the exact negotiation facts and the
runtime registry/block-state/tag view without introducing an application wrapper type:

```kotlin
suspend fun connectAndUseDataPackRuntime(
    selectorManager: SelectorManager,
    profile: ClientNegotiationProfile = VanillaClient,
    options: MinecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(),
    usePlayConnection: suspend (
        MinecraftClientConnection,
        MinecraftClientNegotiationResult,
        ClientDataPackRuntime,
    ) -> Unit,
) {
    MinecraftClientConnection.connect(
        selectorManager = selectorManager,
        host = "127.0.0.1",
    ).use { connection ->
        val negotiationResult = connection.negotiate(
            identity = MinecraftOfflineIdentity("Player"),
            profile = profile,
            options = options,
        )
        val dataPackRuntime = negotiationResult.toDataPackRuntime(connection)
        usePlayConnection(connection, negotiationResult, dataPackRuntime)
    }
}
```

`dataPackRuntime.registryContext.blockStates` is the active global block-state palette,
`requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)` supplies the active biome raw IDs, and
`dataPackRuntime.tags` or `tag(registry, id)` supplies tags already resolved to their registry entries. The same
`registryContext`, together with `negotiationResult.chunkLayout`, can be passed to `MinecraftChunkPacketDecoder` in the
next section.

For a loader profile, pass that profile to `negotiate`; `toDataPackRuntime(connection)` then uses the profile's exact
installed mappings. An application that implements Configuration itself can retain and replace every intermediate value
explicitly instead. Making those inputs function parameters keeps their sources explicit:

```kotlin
fun resolveHandwrittenConfiguration(
    minecraftClientConfiguration: MinecraftClientConfiguration,
    applicationProtocolData: ProtocolDataSet,
    applicationStaticRegistries: StaticRegistrySchema,
    loaderRegistrySnapshot: RemoteRegistrySnapshot,
): ClientDataPackRuntime {
    val received = minecraftClientConfiguration.toReceivedDataPackConfiguration()
    return received.resolveRuntime(
        protocolData = applicationProtocolData,
        staticRegistries = applicationStaticRegistries,
        remoteRegistries = loaderRegistrySnapshot,
    )
}
```

Only Configuration-visible data can be reconstructed on the client. Recipes, loot tables, functions, advancements, and
other server-only files are not sent by the protocol and therefore are not present in `ClientDataPackRuntime`.

## Use the received registries to read world Chunks

After `negotiate` reaches Play, `connection.registries` contains the block-state and biome mappings selected during
Configuration, including loader remapping. Convert that installed context directly into the `ChunkDataRegistries`
required by `world-format`'s strong NBT codec:

`ChunkLayout` deliberately has no repository-version default. Chunk height is a property of the active dimension type,
and a server can synchronize vanilla, datapack, or modded dimensions with different `min_y` and `height` values. During
Configuration the server sends its dimension-type registry; Play Login then selects one entry by raw ID. `negotiate`
resolves that exact entry and exposes both forms on its result:

- `minecraftClientNegotiationResult.dimensionLayout` retains the resolved `MinecraftDimensionLayout`, including sky
  light information.
- `minecraftClientNegotiationResult.chunkLayout` is the corresponding `world-format` `ChunkLayout`.

When the synchronized entry contains NBT, those server values are authoritative. When Known Packs allows the server to
omit known entry NBT, resolution uses the same `MinecraftClientNegotiationOptions.protocolData` supplied to that
negotiation. It does not silently substitute an Overworld layout or another global default.

```kotlin
fun createClientChunkNbtCodec(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    expectedDataVersion: Int,
): ChunkNbtCodec<ProtocolBlockState, ProtocolRegistryEntry> {
    val chunkDataRegistries = minecraftClientConnection.chunkDataRegistries()
    val chunkNbtContext = ChunkNbtContext(
        layout = minecraftClientNegotiationResult.chunkLayout,
        registries = chunkDataRegistries,
        expectedDataVersion = expectedDataVersion,
    )
    return ChunkNbtCodec(chunkNbtContext)
}
```

The returned block values are `ProtocolBlockState` instances with their active global IDs; biome values are
`ProtocolRegistryEntry` instances with their active raw IDs. Their persistent names and properties still round-trip
through `ChunkNbtCodec`. Custom default air/biome identifiers can be passed to `chunkDataRegistries` when a negotiated
profile does not use the vanilla defaults.

## Decode initial-world Chunk packets

The Chunk portion sent by `MinecraftServerConnection.synchronizeInitialWorld` arrives as
`ChunkDataAndUpdateLightPacket`. Build one decoder for the active dimension, then call `packet.toChunk(...)`. The
decoded semantic `Chunk.position` retains the packet's absolute `chunkPosition`:

```kotlin
fun createChunkPacketDecoder(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    expectedDataVersion: Int,
): MinecraftChunkPacketDecoder {
    val chunkMetadata = ChunkMetadata(
        dataVersion = expectedDataVersion,
        status = "full",
    )
    return MinecraftChunkPacketDecoder(
        registries = minecraftClientConnection.registries,
        layout = minecraftClientNegotiationResult.chunkLayout,
        metadata = chunkMetadata,
    )
}
```

`ChunkDataAndUpdateLightPacket` does not carry persistence-only fields such as data version, generation status,
inhabited time, or ticks, which is why the caller supplies a metadata template. Packet heightmaps, block entities, and
light replace the corresponding fields in that template. Palette IDs are resolved directly into logical block/biome
values; `chunk.block(...)` has already applied the palette and never returns a raw palette index.

The layout stored in `MinecraftClientNegotiationResult` describes the initial Play dimension selected by that result's
`playLogin`. An application that later changes dimensions creates a decoder for the newly selected dimension instead of
reusing the old decoder.

The following loop extracts every Chunk in one initial batch and sends the two acknowledgements required by the same
server synchronization sequence. The callback is an explicit parameter, so no application variable is assumed:

```kotlin
suspend fun receiveInitialWorldChunks(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftChunkPacketDecoder: MinecraftChunkPacketDecoder,
    consumeChunk: suspend (Chunk<ProtocolBlockState, ProtocolRegistryEntry>) -> Unit,
) {
    var batchFinished = false
    while (!batchFinished) {
        when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
            is SynchronizePlayerPositionPacket -> minecraftClientConnection.outgoing.send(
                ConfirmTeleportationPacket(clientboundPacket.teleportId),
            )

            is ChunkDataAndUpdateLightPacket -> {
                val chunk = clientboundPacket.toChunk(minecraftChunkPacketDecoder)
                consumeChunk(chunk)
            }

            is ChunkBatchFinishedPacket -> {
                minecraftClientConnection.outgoing.send(ChunkBatchReceivedPacket(desiredChunksPerTick = 10.0f))
                minecraftClientConnection.flush()
                batchFinished = true
            }

            else -> Unit
        }
    }
}
```

`flush()` deliberately waits until the batch acknowledgement and all earlier queued packets have been flushed to the
wire. During initial world synchronization, completing this function before `ChunkBatchReceivedPacket` has been sent
could leave the server transmitting at its previous rate and overwhelm the client. Blocking this joining coroutine is
appropriate because one client connection is synchronizing with one server. Tick-driven steady-state traffic can still
use `requestFlush()` when the caller should continue without waiting for the wire flush.

The packet decoder validates section count, palette width and IDs, light masks, block-entity registry IDs, and absolute
block-entity coordinates. Network update tags are reassembled with their separate type and position metadata. This is a
network projection: it cannot recreate persistence fields that the server never sent.

## Decode Entity pairing bundles

Entity creation uses the same data boundary as Chunk creation. `MinecraftEntitySnapshot` is a server-side convenience;
the client receives its `protocol-model` packets. An Entity pairing bundle begins with `SpawnEntityPacket`. Build one
decoder from the active registry context to restore every strong `Entity` represented by the bundle:

```kotlin
fun decodeEntities(
    minecraftClientConnection: MinecraftClientConnection,
    clientboundBundlePacket: ClientboundBundlePacket,
): List<Entity<NbtCompound>>? {
    val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(minecraftClientConnection.registries)
    return clientboundBundlePacket.toEntitiesOrNull(minecraftEntityPacketDecoder)
}
```

The decoder resolves `typeId` through the negotiated Entity-type registry and restores type, UUID, position, velocity,
yaw, and pitch. This basic overload returns `Entity<NbtCompound>` values with empty subtype data. Runtime-only state
remains in the pairing packets. `clientboundBundlePacket.isEntityPairingBundle` provides a direct Boolean check using
the same leading-`SpawnEntityPacket` rule when conversion is not yet needed.

The leading packet is the discriminator: the decoder does not scan a bundle for a later Spawn. If the first packet is
not `SpawnEntityPacket`, `toEntitiesOrNull` returns null and the bundle remains ordinary application traffic.

The matching official server creates one bundle per Entity. The bundle protocol itself is generic, however, and the
official client simply handles every subpacket in received order. It therefore also accepts several complete Entity
pairing sequences concatenated in one bundle. `toEntities` supports both shapes by starting a new Entity whenever it
encounters another `SpawnEntityPacket`. `toEntity` remains the convenient strict form for the ordinary one-Entity
bundle; it requires exactly one Spawn, while `toEntityOrNull` returns null for a non-Entity or multi-Entity bundle.

Use `MinecraftEntityPacketAdapter` when the returned Entity is the caller's complete client runtime model. The adapter
creates its subtype data, registers the Entity before dependent packets are applied, and receives each supported tail
packet through a type-specific callback:

```kotlin
data class ClientEntityData(
    val type: Identifier,
    val spawnData: Int,
    var headYaw: Float,
    var metadata: EntityMetadata? = null,
    var attributes: List<AttributeSnapshot> = emptyList(),
    var equipment: List<EquipmentUpdate> = emptyList(),
)

class ClientEntityPacketAdapter(
    private val entitiesById: MutableMap<Int, Entity<ClientEntityData>>,
    private val passengerIdsByVehicleId: MutableMap<Int, List<Int>>,
    private val leashHolderIdsByEntityId: MutableMap<Int, Int>,
) : MinecraftEntityPacketAdapter<ClientEntityData> {
    override fun createData(packet: SpawnEntityPacket, type: Identifier): ClientEntityData = ClientEntityData(
        type = type,
        spawnData = packet.data,
        headYaw = packet.headYaw.degrees,
    )

    override fun registerEntity(packet: SpawnEntityPacket, entity: Entity<ClientEntityData>) {
        entitiesById[packet.entityId] = entity
    }

    override fun applyMetadata(entity: Entity<ClientEntityData>, packet: SetEntityMetadataPacket) {
        entity.data.metadata = packet.metadata
    }

    override fun applyAttributes(entity: Entity<ClientEntityData>, packet: UpdateAttributesPacket) {
        entity.data.attributes = packet.attributes.toList()
    }

    override fun applyEquipment(entity: Entity<ClientEntityData>, packet: SetEquipmentPacket) {
        entity.data.equipment = packet.updates.entries.toList()
    }

    override fun applyPassengers(entity: Entity<ClientEntityData>, packet: SetPassengersPacket) {
        passengerIdsByVehicleId[packet.vehicleEntityId] = packet.passengerEntityIds.toList()
    }

    override fun applyLink(entity: Entity<ClientEntityData>, packet: LinkEntitiesPacket) {
        leashHolderIdsByEntityId[packet.attachedEntityId] = packet.holdingEntityId
    }
}
```

The adapter can then be reused by the packet loop. A non-Entity bundle continues through the application's normal packet
dispatcher:

```kotlin
suspend fun receiveEntities(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftEntityPacketAdapter: MinecraftEntityPacketAdapter<ClientEntityData>,
    dispatchPacket: suspend (ClientboundPacket) -> Unit,
) {
    val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(minecraftClientConnection.registries)
    for (clientboundPacket in minecraftClientConnection.incoming) {
        val entities = (clientboundPacket as? ClientboundBundlePacket)?.toEntitiesOrNull(
            minecraftEntityPacketDecoder,
            minecraftEntityPacketAdapter,
        )
        if (entities == null) dispatchPacket(clientboundPacket)
    }
}
```

For every Spawn, the adapter overload calls `registerEntity` before applying that pairing sequence's metadata,
attributes, equipment, passenger relations, and leash state. A later Spawn starts the next sequence. Tail packets are
processed in received order without requiring a fixed order among their types. `SetPassengersPacket` and
`LinkEntitiesPacket` can refer to other Entities, which is why the example keeps those relationships in caller-owned ID
maps.

Callers that need explicit lifecycle control can use the same primitive operations separately: obtain
`spawnEntityPacket()`, create the Entity with `SpawnEntityPacket.toEntity`, register it, and then call
`applyEntityPairingPackets` for that one sequence. `spawnEntityPackets()` exposes every Spawn in a multi-Entity bundle.
An already unwrapped `Iterable<ClientboundPacket>` supports the same `toEntities` and `toEntitiesOrNull` calls, so raw
packet lists do not need to be wrapped back into `ClientboundBundlePacket`. The public incoming channel waits for both
wire delimiters and publishes one complete logical bundle, so it never exposes `BundleDelimiterPacket`.

## Writing your own negotiation

Applications can write their own `negotiate` function instead of calling the preset. Read the maintained
[client
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/client/MinecraftClientProtocol.kt)
for the complete packet ordering and optional branches. It uses only the public connection API: `incoming`, `outgoing`,
`awaitState`, `installRegistryContext`, `activateExtensionRoutes`, authentication primitives, and profile hooks. A
vanilla offline implementation has these registry-sensitive steps:

1. Send Handshake and Login Start, handle Login packets, send Login Acknowledged, and await Configuration.
2. Send Client Information; collect Known Packs, Feature Flags, every Registry Data packet, and tags.
3. Resolve the Configuration context with `resolveSynchronizedRegistryContext`, pass it through
   `ClientNegotiationProfile.resolveRegistryContext`, install it, call `preparePlay`, then acknowledge Finish
   Configuration.
4. Receive Play Login, derive the active dimension with `withPlayLoginDimension`, install the result, and only then
   continue decoding Play packets such as chunks.

The portable
[public-primitives end-to-end test](../protocol-server/src/commonTest/kotlin/com/hiczp/minecraft/protocol/server/ClientToServerEndToEndTest.kt)
executes complete client and server implementations without either preset, including compression, a fake profile,
initial chunks, acknowledgements, and a Play keepalive. The
[official-server client scenario](src/commonTest/kotlin/com/hiczp/minecraft/protocol/client/OfficialServerClientScenario.kt)
also runs the public client sequence against the matching official server. These tests verify the production ordering
reference; reaching `ConnectionState.PLAY` alone is not treated as completed Play initialization.

## Custom protocols and loader profiles

Declare every possible custom packet before connecting through a shareable `MinecraftConnectionDefinition`:

```kotlin
suspend fun negotiateFabric(
    selectorManager: SelectorManager,
    host: String,
    minecraftOfflineIdentity: MinecraftOfflineIdentity,
    packetCodecRegistrations: List<PacketCodecRegistration<out Packet>>,
    staticRegistrySchema: StaticRegistrySchema,
): MinecraftClientNegotiationResult {
    val minecraftConnectionDefinition = FabricProtocol.connectionDefinition(
        extensionCodecs = packetCodecRegistrations,
    )
    return MinecraftClientConnection.connect(
        selectorManager = selectorManager,
        host = host,
        definition = minecraftConnectionDefinition,
    ).use { minecraftClientConnection ->
        minecraftClientConnection.negotiate(
            identity = minecraftOfflineIdentity,
            profile = FabricClientProfile(staticRegistrySchema),
        )
    }
}
```

Equivalent NeoForge and Forge APIs live in [`protocol-session`](../protocol-session/README.md). An unregistered query or
payload reaches the application or profile as `UnknownPacket.Clientbound`; during preset negotiation,
`MinecraftClientNegotiationOptions.onUnhandledQuery` can return an explicit response or rejection.

## Login identities

Identities come from [`protocol-auth`](../protocol-auth/README.md). Offline Login needs no HTTP API:

```kotlin
suspend fun negotiateOffline(
    minecraftClientConnection: MinecraftClientConnection,
): MinecraftClientNegotiationResult =
    minecraftClientConnection.negotiate(MinecraftOfflineIdentity("Player"))
```

Online Login receives account data already available to the game process plus a caller-owned HTTP client:

```kotlin
suspend fun negotiateOnline(
    minecraftClientConnection: MinecraftClientConnection,
    profileId: Uuid,
    profileName: String,
    minecraftAccessToken: String,
    applicationHttpClient: HttpClient,
): MinecraftClientNegotiationResult {
    val minecraftOnlineIdentity = MinecraftOnlineIdentity(
        id = profileId,
        name = profileName,
        accessToken = minecraftAccessToken,
    )
    return minecraftClientConnection.negotiate(
        identity = minecraftOnlineIdentity,
        sessionHttpClient = applicationHttpClient,
    )
}
```

How a launcher obtains, stores, or transfers those values is outside this module. When the server sends Encryption
Request, the client performs the Session Server `/join` call and enables encryption at the official boundary; it never
silently downgrades authentication, and it imposes no timeout, retry, or engine policy on the caller-owned client.
Malformed frames and known packet bodies, encoding failures, and invalid packet ordering close the channel with the
original cause instead of being swallowed or converted to automatic replies.
