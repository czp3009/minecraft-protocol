# protocol-session

Typed, channel-first Minecraft packet connections over [`protocol-transport`](../protocol-transport/README.md).

## Connection contract

`MinecraftPacketConnection<Incoming, Outgoing>` exposes standard coroutine channels:

```kotlin
for (packet in connection.incoming) {
    handle(packet)
}

connection.outgoing.send(myPacket)
```

The connection owns one reader pump and one writer pump. It validates direction and current protocol state, commits
Handshake/Login/Configuration/Play transitions only after the relevant packet crosses the wire, and activates
compression or encryption at the required boundary. `awaitState` observes committed state; `installRegistryContext` and
`activateExtensionRoutes` are public primitives available to library profiles and application negotiators alike.

`MinecraftConnectionDefinition` is an immutable, shareable definition of packet codecs, serializers, initial registry
context, and channel capacities. Build one definition and pass the same instance to every accepted server connection:

```kotlin
val definition = MinecraftConnectionDefinition.compose(
    extensionCodecs = myPacketCodecs,
    registries = myRegistryContext,
    serializersModule = mySerializersModule,
)
```

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

Pass registrations to `MinecraftConnectionDefinition.compose`, then activate only routes accepted by negotiation:

```kotlin
connection.activateExtensionRoutes(
    connection.activeExtensionRoutes + counterRoute,
)
```

Registration factories also cover Login queries and advanced top-level numeric packet IDs. An unregistered or inactive
top-level ID, Login query, or custom-payload channel arrives as a direction-correct `UnknownPacket` with its complete
route and body bytes. Every other decoding error, unread trailing byte, encoding error, or illegal state transition
propagates as the original channel/connection failure; the library never converts malformed known data into
`UnknownPacket`, swallows the cause, or sends an error response automatically.

## Negotiation profiles

`ClientNegotiationProfile` and `ServerNegotiationProfile` are optional one-connection algorithms used by the client and
server `negotiate` extension functions. They operate only through the same public channels and state primitives as
application code; an application may omit them and implement the entire flow itself.

The module ships preset profiles for Fabric API, NeoForge, and Forge networking under separate packages. Use
`FabricProtocol.connectionDefinition`, `NeoForgeProtocol.connectionDefinition`, or
`ForgeProtocol.connectionDefinition` to compose each profile's fixed codecs with application codecs.

## Failure and lifetime

Closing a connection closes its pumps and transport. EOF, framing errors, malformed known payloads, and pump failures
close the channels with the original cause; catch it from `incoming.receive`, channel iteration, or `awaitClosed`.
Closing `outgoing` drains values already accepted by that channel and then closes the connection, while
`connection.close()` is the immediate, idempotent cancellation path and may discard queued outgoing values. Cancelling
an in-flight send propagates cancellation without committing a transition; provisional Login Query correlation is rolled
back before the send returns.
