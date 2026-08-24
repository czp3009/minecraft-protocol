# protocol-session

Typed, channel-first Minecraft packet connections over [`protocol-transport`](../protocol-transport/README.md).

## Connection contract

The names follow the matching official implementation's `PacketFlow`: `bound` identifies the destination, not the
sender.

```text
client -- ServerboundPacket --> server
client <-- ClientboundPacket -- server
```

`MinecraftClientPacketConnection` and `MinecraftServerPacketConnection` therefore fix both channel directions in their
types:

- the client receives `ClientboundPacket` and sends `ServerboundPacket`;
- the server receives `ServerboundPacket` and sends `ClientboundPacket`.

Both refine the lower-level `MinecraftPacketConnection<Incoming, Outgoing>` contract and expose standard coroutine
channels. In this basic loop, `connection` is an already-open typed client or server connection, `myPacket` is a packet
created by the application, and `handle` is its packet handler:

```kotlin
for (packet in connection.incoming) {
    handle(packet)
}

connection.outgoing.send(myPacket)
connection.requestFlush()
```

The endpoint type makes a wrong-direction channel operation unrepresentable. Each connection owns one reader pump and
one writer pump. It validates the current protocol state and packet route, commits
Handshake/Login/Configuration/Play transitions after the complete transition frame has been appended, and activates
compression or encryption at that same ordered boundary. A peer may begin sending the next state's packets as soon as
its acknowledgement allows. The public `state` and `awaitState` expose the committed connection state;
`installRegistryContext` and `activateExtensionRoutes` are public primitives available to library profiles and
application negotiators alike.

`MinecraftConnectionDefinition` is an immutable, shareable definition of packet codecs, serializers, initial registry
context, and channel capacities. Build one definition and pass the same instance to every accepted server connection.
Here `myRegistryContext`, `mySerializersModule`, and `myPacketCodecs` are immutable values assembled by the application
or loader before connections are opened:

```kotlin
val minecraftProtocolFormat = MinecraftProtocolFormat(
    configuration = MinecraftProtocolFormat.configuration.copy(registries = myRegistryContext),
    serializersModule = mySerializersModule,
)
val minecraftConnectionDefinition = MinecraftConnectionDefinition.compose(
    extensionCodecs = myPacketCodecs,
    format = minecraftProtocolFormat,
)
```

`incomingCapacity` and `outgoingCapacity` are passed directly to the coroutine channels. Use a non-dropping capacity:
`outgoing.send` then preserves order and suspends when the queue is full, while `outgoing.trySend` lets a tick loop
detect that condition without suspension. The library does not choose a slow-client policy. Once accepted, each outgoing
packet is encoded by the connection's single writer pump in order.

`requestFlush()` is the ordinary tick-end operation. It returns immediately, coalesces repeated pending requests, and
makes the same writer pump flush after packets already accepted by `outgoing`; any suspension caused by socket
backpressure therefore occurs on the connection's network dispatcher rather than the tick coroutine. Use the suspending
`flush()` only when the calling coroutine must wait for that ordered flush to finish:

In the example, `packetsForThisTick` is the batch produced by the application's tick, and `handleSlowConnection` is its
policy callback for a full outgoing channel:

```kotlin
for (clientboundPacket in packetsForThisTick) {
    if (connection.outgoing.trySend(clientboundPacket).isFailure) {
        handleSlowConnection(connection)
        break
    }
}
connection.requestFlush()
```

Ktor also advances writes as its own buffer fills; the explicit request publishes the remaining tail. A completed flush
is not a peer-receipt acknowledgement. The peer's protocol acknowledgement remains a separate incoming packet. Protocol
transitions do not introduce an implicit flush; call `requestFlush()` at the application's normal publication boundary.
In the normal Minecraft Login flow, Set Compression is sent once and applies to every later packet in both directions.

The library follows Minecraft's sequential connection model instead of adding locks around every public operation. Keep
one logical consumer of `incoming`, enqueue outgoing packets in their protocol order, and do not concurrently call
`MinecraftClientPacketSession` or `MinecraftServerPacketSession` frame-write APIs. High-level connections already
enforce wire write order through their outgoing channel and writer pump.

## Clientbound bundles

`ClientboundBundlePacket` is one logical Play message containing ordered `subPackets`. Enqueuing it as one outgoing
channel value makes the single writer emit an opening delimiter, every sub-packet, and the closing delimiter without
another channel value being interleaved. `sendBundle(subPackets)` and `trySendBundle(subPackets)` are channel shortcuts.
Here `spawnEntityPacket` and `setEntityMetadataPacket` are packet values already constructed by the application, and
`connection` is its open server-side connection:

```kotlin
val clientboundPackets = listOf<ClientboundPacket>(spawnEntityPacket, setEntityMetadataPacket)
connection.outgoing.sendBundle(clientboundPackets)
```

The client connection performs the inverse operation. It buffers from the opening delimiter through the closing
delimiter and then publishes one `ClientboundBundlePacket`; its public `incoming` channel never yields a
`BundleDelimiterPacket` or a partial bundle. A caller may still enqueue raw delimiters directly when it intentionally
controls the wire sequence.

## Custom packets and queries

Application packet types implement the direction-specific open extension branch. Most packet bodies can use the ordinary
`kotlinx.serialization` adapter:

```kotlin
@Serializable
data class CounterPayload(
    @VarInt val value: Int,
) : ClientboundPacket.Extension

val counterRoute = PacketRouteKey.CustomPayload(
    state = ConnectionState.PLAY,
    direction = PacketDirection.CLIENTBOUND,
    channel = Identifier("example:counter"),
)
val counterCodec = PacketCodecRegistration.clientboundCustomPayload(
    state = ConnectionState.PLAY,
    channel = Identifier("example:counter"),
    packetClass = CounterPayload::class,
    codec = KotlinxPacketBodyCodec(CounterPayload.serializer()),
)
```

Pass registrations to `MinecraftConnectionDefinition.compose`, then activate only routes accepted by negotiation. In the
following block, `counterRoute` was declared in the preceding block and `connection` is the connection whose negotiation
accepted that route:

```kotlin
connection.activateExtensionRoutes(
    connection.activeExtensionRoutes + counterRoute,
)
```

Registration factories also cover Login queries and advanced top-level numeric packet IDs. An unregistered or inactive
top-level ID, Login query, or custom-payload channel arrives as a direction-correct `UnknownPacket` with its complete
route and body bytes. Decoding errors, unread trailing bytes, illegal state transitions, and wire-write failures close
the connection with their cause; malformed known data is never converted into `UnknownPacket`. For the small
`SkippableClientboundPacket` set used by the official encoder, a payload-encoding failure omits that one packet and the
writer continues. This exception never covers framing or transport failures.

Keep each Login Query transaction ID unique until its response. The session remembers the request channel so a matching
response can be projected to an extension packet. A response for an ID not observed by this session remains the raw
`LoginPluginResponsePacket`.

## Negotiation profiles

`ClientNegotiationProfile` and `ServerNegotiationProfile` are optional one-connection algorithms used by the client and
server `negotiate` extension functions. They operate only through the same public channels and state primitives as
application code; an application may omit them and implement the entire flow itself.

The module ships preset profiles for Fabric API, NeoForge, and Forge networking under separate packages. Use
`FabricProtocol.connectionDefinition`, `NeoForgeProtocol.connectionDefinition`, or
`ForgeProtocol.connectionDefinition` to compose each profile's fixed codecs with application codecs.

## Failure and lifetime

Closing a connection closes its pumps and transport. EOF, framing errors, malformed known payloads, and pump failures
close the channels with the original cause; catch it from `incoming.receive`, channel iteration, or `awaitClosed`. An
asynchronous `requestFlush()` failure follows this same path: the writer pump fails the connection and preserves the
flush exception as the channel and `awaitClosed` cause.
Closing `outgoing` drains values already accepted by that channel and then closes the connection, while
`connection.close()` is the immediate, idempotent cancellation path and may discard queued outgoing values. Cancelling
an in-flight send propagates cancellation without committing its protocol transition or Login Query correlation.
