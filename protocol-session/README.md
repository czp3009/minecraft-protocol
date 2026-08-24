# protocol-session

`protocol-session` turns framed packet bytes into typed, direction-safe coroutine channels. It sits between [
`protocol-transport`](../protocol-transport/README.md) and the high-level [
`protocol-client`](../protocol-client/README.md) / [`protocol-server`](../protocol-server/README.md) modules.

## Typed packet connections

Minecraft direction names identify the destination:

```text
client -- ServerboundPacket --> server
client <-- ClientboundPacket -- server
```

Accordingly:

- `MinecraftClientPacketConnection` receives `ClientboundPacket` and sends `ServerboundPacket`;
- `MinecraftServerPacketConnection` receives `ServerboundPacket` and sends `ClientboundPacket`.

Both expose ordinary coroutine channels:

```kotlin
suspend fun handleClientConnection(
    connection: MinecraftClientPacketConnection,
    outgoingPacket: ServerboundPacket,
    handleIncoming: suspend (ClientboundPacket) -> Unit,
) {
    connection.outgoing.send(outgoingPacket)
    connection.requestFlush()

    for (packet in connection.incoming) {
        handleIncoming(packet)
    }
}
```

The connection validates packet routes against the committed protocol state and applies Handshake, Login, Configuration,
and Play transitions in wire order. Compression and encryption changes are committed at the corresponding transition
packet boundary.

Keep one logical consumer of `incoming`. Enqueue outgoing packets in protocol order; the connection's writer preserves
the order accepted by its non-dropping channel.

## Definitions and registry context

`MinecraftConnectionDefinition` is an immutable, shareable description of packet codecs, the format, initial registries,
and channel capacities:

```kotlin
fun createConnectionDefinition(
    registryContext: ProtocolRegistryContext,
    serializersModule: SerializersModule,
    extensionCodecs: List<PacketCodecRegistration<out Packet>>,
): MinecraftConnectionDefinition {
    val format = MinecraftProtocolFormat(
        configuration = MinecraftProtocolFormat.configuration.copy(registries = registryContext),
        serializersModule = serializersModule,
    )
    return MinecraftConnectionDefinition.compose(
        extensionCodecs = extensionCodecs,
        format = format,
    )
}
```

Create one definition at application lifetime and pass it to each client connection or accepted server connection.
Negotiation can later install a connection-specific `ProtocolRegistryContext` and activate only the extension routes the
peer accepted.

`incomingCapacity` and `outgoingCapacity` are passed to the coroutine channels. Use `outgoing.trySend()` when a tick
must detect a full queue without suspending and apply its own slow-peer policy.

## Flush queued packets

`requestFlush()` is the normal tick-end operation. It returns immediately, coalesces repeated requests, and asks the
writer to flush packets already accepted by `outgoing`:

```kotlin
fun publishTick(
    connection: MinecraftServerPacketConnection,
    packets: Iterable<ClientboundPacket>,
): Boolean {
    for (packet in packets) {
        if (connection.outgoing.trySend(packet).isFailure) return false
    }
    connection.requestFlush()
    return true
}
```

Use suspending `flush()` when the calling coroutine must wait for that ordered flush. A completed flush is not proof
that the peer received or decoded the packets; protocol acknowledgements arrive separately through `incoming`.

## Clientbound bundles

`ClientboundBundlePacket` is one logical Play message containing ordered sub-packets. Sending it as one channel value
prevents another channel value from interleaving with its delimiter-bounded wire sequence:

```kotlin
suspend fun sendEntityPairing(
    connection: MinecraftServerPacketConnection,
    spawn: SpawnEntityPacket,
    metadata: SetEntityMetadataPacket,
) {
    connection.outgoing.sendBundle(listOf(spawn, metadata))
}
```

The client side performs the inverse operation and publishes one complete `ClientboundBundlePacket`; ordinary callers do
not see partial bundles or delimiter packets.

## Register custom packets

Application packets implement the appropriate open extension branch. Most bodies can use a normal `KSerializer`:

```kotlin
@Serializable
data class CounterPayload(
    @VarInt val value: Int,
) : ClientboundPacket.Extension

val counterCodec = PacketCodecRegistration.clientboundCustomPayload(
    state = ConnectionState.PLAY,
    channel = Identifier("example:counter"),
    packetClass = CounterPayload::class,
    codec = KotlinxPacketBodyCodec(CounterPayload.serializer()),
)
```

Pass the registration to `MinecraftConnectionDefinition.compose()`. A route must also be active on the individual
connection before the codec is used. Here `connection` is an already-open `MinecraftPacketConnection` whose negotiation
accepted this route:

```kotlin
val counterRoute = PacketRouteKey.CustomPayload(
    state = ConnectionState.PLAY,
    direction = PacketDirection.CLIENTBOUND,
    channel = Identifier("example:counter"),
)

connection.activateExtensionRoutes(connection.activeExtensionRoutes + counterRoute)
```

Factories also cover Login queries and top-level numeric packet IDs. Valid unregistered or inactive routes arrive as
direction-correct `UnknownPacket` values containing the complete route and payload. A malformed registered body is still
an error, not an unknown packet.

## Negotiation profiles

`ClientNegotiationProfile` and `ServerNegotiationProfile` are optional per-connection algorithms used by the high-level
client/server `negotiate()` extensions. Applications may omit them and implement negotiation directly through the public
channels and state operations.

The module includes profile and connection-definition helpers for:

- Fabric API: `FabricProtocol`, `FabricClientProfile`, and `FabricServerProfile`;
- NeoForge: `NeoForgeProtocol`, `NeoForgeClientProfile`, and `NeoForgeServerProfile`;
- Forge: `ForgeProtocol`, `ForgeClientProfile`, and `ForgeServerProfile`.

Each protocol helper composes its fixed packet codecs with application codecs. Profiles activate routes only after
negotiation accepts them and may resolve loader-supplied registry mappings into the connection context.

## Failure and lifetime

EOF, framing failures, malformed known packets, invalid transitions, and pump failures close the connection and remain
visible through channel operations or `awaitClosed()`.

Closing `outgoing` drains values already accepted by that channel before closing the connection. `connection.close()` is
the immediate idempotent cancellation path and may discard queued values. Cancelling an in-flight transition send does
not commit its protocol state or Login-query correlation.
