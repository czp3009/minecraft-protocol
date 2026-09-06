# protocol-session

`protocol-session` turns framed packet bytes into typed, direction-safe coroutine channels. It sits between
[`protocol-transport`](../protocol-transport/README.md) and the high-level
[`protocol-client`](../protocol-client/README.md) / [`protocol-server`](../protocol-server/README.md) modules.

## Typed packet connections

Minecraft direction names identify the destination:

```text
client -- ServerboundPacket --> server
client <-- ClientboundPacket -- server
```

Accordingly:

- `MinecraftClientPacketConnection` receives `ClientboundPacket` and sends `ServerboundPacket`;
- `MinecraftServerPacketConnection` receives `ServerboundPacket` and sends `ClientboundPacket`.

Both expose ordinary coroutine channels. The high-level `MinecraftClientConnection.connect(...)` result implements
`MinecraftClientPacketConnection`; `MinecraftServer.accept()` returns a `MinecraftServerPacketConnection`
implementation.
The root [client/server examples](../README.md#client-connect-to-a-server) show their construction and lifetime.
For this lower-level helper, construct a packet appropriate to the current state, such as
`ServerboundPingRequestPacket(payload)` after the Status response. `handleIncoming` receives each typed reply:

```kotlin
suspend fun handleClientConnection(
    minecraftClientPacketConnection: MinecraftClientPacketConnection,
    serverboundPacket: ServerboundPacket,
    handleIncoming: suspend (ClientboundPacket) -> Unit,
) {
    minecraftClientPacketConnection.outgoing.send(serverboundPacket)
    minecraftClientPacketConnection.requestFlush()

    for (clientboundPacket in minecraftClientPacketConnection.incoming) {
        handleIncoming(clientboundPacket)
    }
}
```

The connection validates packet routes against the committed protocol state and applies Handshake, Login, Configuration,
and Play transitions in wire order. Compression and encryption changes are committed at the corresponding transition
packet boundary.

Keep one logical consumer of `incoming`. Enqueue outgoing packets in protocol order; the connection's writer preserves
the relative order accepted by its non-dropping public channel. Endpoint-generated packets may be inserted only between
complete logical packets.

## Managed KeepAlive

The client endpoint automatically answers direct official Configuration and Play KeepAlive requests with the same ID.
Those requests are consumed by the endpoint, flushed through the connection's writer without a caller
`requestFlush()`, and do not appear on `incoming`. A KeepAlive nested inside a logical `ClientboundBundlePacket` remains
part of that bundle; the official server sends KeepAlive directly, so the endpoint does not inspect bundle contents for
this behavior.

The server endpoint owns challenge generation, pending-response validation and timeout handling. Its
`enableKeepAlive()` method defaults to the official packet pair in both Configuration and Play. For a hand-written
negotiation, call it after entering Configuration. Disable that run at the finish acknowledgement, await committed Play
state, then enable a fresh run before sending Play packets. Use the server connection returned by `accept()`.

Each enable call starts a fresh timer and clears any pending challenge. The default interval is 15 seconds. At each
interval boundary, an existing pending challenge terminates the connection; otherwise the endpoint records and sends a
new monotonic-time challenge. A matching reply clears the pending challenge without resetting that timer, while an
unsolicited or mismatched reply terminates the connection. If KeepAlive is disabled, an otherwise valid reply remains an
ordinary `incoming` packet.

The preset `protocol-server` negotiation performs the Configuration-to-Play switch automatically. A hand-written flow
must disable the old run and enable the new one at the corresponding acknowledgement boundary. Closing the connection
cancels the active run. While a managed run is active, do not manually send KeepAlive requests from the same protocol
state. Mods with another packet pair can call the lower-level
`enableKeepAlive(extractChallenge, createRequest, interval)`; that mapping does not infer a protocol state.

Connection-generated KeepAlive packets and client replies share the connection's only writer with public `outgoing`
traffic. They take priority at the next logical packet boundary and flush immediately, but never interrupt a frame or a
logical bundle already being written.

## Register custom packets

Application packets implement the appropriate open extension branch. Most bodies can use a normal `KSerializer`:

```kotlin
@Serializable
data class CounterPayload(
    @VarInt val value: Int,
) : ClientboundPacket.Extension

val counterCodec = PacketCodecRegistration.clientboundCustomPayload(
    connectionState = ConnectionState.PLAY,
    channel = Identifier("example:counter"),
    packetClass = CounterPayload::class,
    packetBodyCodec = KotlinxPacketBodyCodec(CounterPayload.serializer()),
)
```

Pass the registration to `MinecraftConnectionDefinition.compose()`. A route must also be active on the individual
connection before the codec is used. Here `minecraftPacketConnection` is an already-open `MinecraftPacketConnection`
whose negotiation accepted this route:

```kotlin
val counterRoute = PacketRouteKey.CustomPayload(
    connectionState = ConnectionState.PLAY,
    packetDirection = PacketDirection.CLIENTBOUND,
    channel = Identifier("example:counter"),
)

minecraftPacketConnection.activateExtensionRoutes(minecraftPacketConnection.activeExtensionRoutes + counterRoute)
```

Factories also cover Login queries and top-level numeric packet IDs. Valid unregistered or inactive routes arrive as
direction-correct `UnknownPacket` values containing the complete route and payload. A malformed registered body is still
an error, not an unknown packet.

## Definitions and registry context

`MinecraftConnectionDefinition` is a shareable data description of packet codecs, the format, initial registries, and
channel capacities. It retains those values rather than rebuilding them, so callers that supply collection-backed
registries keep those inputs stable. Vanilla users of `MinecraftClientConnection.connect` and `MinecraftServer.bind` do
not need to construct one: both high-level entry points default to `MinecraftConnectionDefinition()` and the built-in
vanilla packet registry.

Create a definition only when adding extension codecs, replacing the format, or changing channel capacities.
`extensionCodecs` can be `listOf(counterCodec)` from the registration above. Construct `SerializersModule { ... }` with
application serializers (or use `EmptySerializersModule()`), and obtain `packetCodecContext` from the resolved
Configuration result, `VanillaConfigurationData.completePacketCodecContext`, or
`StaticRegistrySchema(registries, blocks).resolve(...)` as documented in [protocol-model](../protocol-model/README.md):

```kotlin
fun createConnectionDefinition(
    packetCodecContext: PacketCodecContext,
    serializersModule: SerializersModule,
    extensionCodecs: List<PacketCodecRegistration<out Packet>>,
): MinecraftConnectionDefinition {
    val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
        minecraftPacketPayloadFormatConfiguration = MinecraftPacketPayloadFormat.minecraftPacketPayloadFormatConfiguration.copy(
            packetCodecContext = packetCodecContext,
        ),
        serializersModule = serializersModule,
    )
    return MinecraftConnectionDefinition.compose(
        extensionCodecs = extensionCodecs,
        minecraftPacketPayloadFormat = minecraftPacketPayloadFormat,
    )
}
```

Create one definition at application lifetime and pass it to each client connection or accepted server connection.
Negotiation can later install a connection-specific `PacketCodecContext` and activate only the extension routes the
peer accepted.

`incomingCapacity` and `outgoingCapacity` are passed to the coroutine channels. Use `outgoing.trySend()` when a tick
must detect a full queue without suspending and apply its own slow-peer policy.

## Flush queued packets

`requestFlush()` is the normal tick-end operation. It returns immediately, coalesces repeated requests, and asks the
writer to flush packets already accepted by `outgoing`. In this example, the accepted server connection supplies
`minecraftServerPacketConnection`; `clientboundPackets` is the application's ordered list of constructed Play packet
values, such as `ClientboundSetChunkCacheRadiusPacket(viewDistance)`:

```kotlin
fun publishTick(
    minecraftServerPacketConnection: MinecraftServerPacketConnection,
    clientboundPackets: Iterable<ClientboundPacket>,
): Boolean {
    for (clientboundPacket in clientboundPackets) {
        if (minecraftServerPacketConnection.outgoing.trySend(clientboundPacket).isFailure) return false
    }
    minecraftServerPacketConnection.requestFlush()
    return true
}
```

Use suspending `flush()` when the calling coroutine must wait for that ordered flush. A completed flush is not proof
that the peer received or decoded the packets; protocol acknowledgements arrive separately through `incoming`.

## Clientbound bundles

`ClientboundBundlePacket` is one logical Play message containing ordered sub-packets. Sending it as one channel value
prevents another channel value from interleaving with its delimiter-bounded wire sequence. The following inputs are
the `ClientboundAddEntityPacket(...)` and `ClientboundSetEntityDataPacket(...)` constructed by the application's entity
projection, or obtained from `EntityPacketEncoder.encode(...)`
in [protocol-world](../protocol-world/README.md#entities-and-items):

```kotlin
suspend fun sendEntityPairing(
    minecraftServerPacketConnection: MinecraftServerPacketConnection,
    clientboundAddEntityPacket: ClientboundAddEntityPacket,
    clientboundSetEntityDataPacket: ClientboundSetEntityDataPacket,
) {
    minecraftServerPacketConnection.outgoing.sendBundle(listOf(clientboundAddEntityPacket, clientboundSetEntityDataPacket))
}
```

The client side performs the inverse operation and publishes one complete `ClientboundBundlePacket`; ordinary callers do
not see partial bundles or delimiter packets. A bundle may contain at most 4,096 packets and cannot contain another
bundle or a delimiter. Do not put `ClientboundStartConfigurationPacket` or another terminal state-transition packet in a
bundle;
the official protocol expects it to stand alone. The library intentionally leaves this semantic rule to callers instead
of inspecting bundle members. Send only `ClientboundBundlePacket`; raw delimiter packets are owned by the server packet
session and are rejected at its public send boundary.

## Negotiation profiles

`ClientNegotiationProfile` and `ServerNegotiationProfile` are optional per-connection algorithms used by the high-level
client/server `negotiate()` extensions. Those extensions default to `VanillaClient` and `VanillaServer`, so a vanilla
caller never constructs a profile. Applications may provide a loader profile or omit the high-level preset and implement
negotiation directly through the public channels and state operations.

The module includes profile and connection-definition helpers for:

- Fabric API: `FabricProtocol`, `FabricClientProfile`, and `FabricServerProfile`;
- NeoForge: `NeoForgeProtocol`, `NeoForgeClientProfile`, and `NeoForgeServerProfile`;
- Forge: `ForgeProtocol`, `ForgeClientProfile`, and `ForgeServerProfile`.

Each protocol helper composes its fixed packet codecs with application codecs. Profiles activate routes only after
negotiation accepts them and may resolve loader-supplied registry mappings into the connection context.

## Failure and lifetime

EOF, framing failures, malformed known packets, invalid transitions, and pump failures close the connection and remain
visible through channel operations or `awaitClosed()`.

Closing `outgoing` drains values already accepted by that channel before closing the connection. Calling `close()` is
the immediate idempotent cancellation path and may discard queued values. Cancelling an in-flight transition send does
not commit its protocol state or Login-query correlation.
