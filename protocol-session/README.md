# protocol-session

Typed, channel-first Minecraft packet connections over `protocol-transport`.

The module targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. It does not publish
browser, D8, or Wasm/WASI variants because a live session requires TCP.

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
compression or encryption at the required boundary. `awaitState` observes committed state; `installRegistryContext`
and `activateExtensionRoutes` are public primitives available to both library profiles and application-written
negotiators.

`MinecraftConnectionDefinition` is an immutable, shareable definition for packet codecs, serializers, initial registry
context, and channel capacities. A server can construct one definition and pass the same instance to `MinecraftServer`
for every accepted connection:

```kotlin
val definition = MinecraftConnectionDefinition.compose(
  extensionCodecs = myPacketCodecs,
  registries = myRegistryContext,
  serializersModule = mySerializersModule,
)
```

The definition and each connection retain the supplied immutable registry context by reference. Derived contexts such as
an active-dimension view also retain the large registry and block-state collections; callers remain responsible for
constructing and sharing their application data at the desired lifetime.

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

Registration factories cover Login queries, Configuration/Play custom payloads, and advanced top-level numeric packet
IDs. A Login-query `PacketBodyCodec` receives the full `PacketRoute.LoginQuery`, including its transaction ID, and its
registration supplies the inverse route selector for encoding. Query responses are checked against the pending
transaction and channel.

An unregistered or inactive top-level ID, Login query, or custom-payload channel arrives as direction-correct
`UnknownPacket.Clientbound` or `UnknownPacket.Serverbound` with its complete route and body bytes. A codec can throw
`UnknownExtensionPacketException` only when a known outer extension contains an intentionally unknown nested packet; the
same lossless fallback is then used. Every other decoding error, unread trailing byte, encoding error, or illegal
state/order transition propagates as the original channel/connection failure. The library never converts malformed known
data into `UnknownPacket`, swallows the cause, or automatically sends an error response.

## Negotiation profiles

`ClientNegotiationProfile` and `ServerNegotiationProfile` are optional one-connection algorithms used by the client and
server `negotiate` extension functions. They operate only through the same public channels and state primitives as
application code; an application may omit them and implement the entire flow itself.

The module contains examples for the repository-selected release under separate packages:

- `com.hiczp.minecraft.protocol.fabric` implements Fabric API common networking, registration, registry sync, split
  payloads, and Play-route activation.
- `com.hiczp.minecraft.protocol.neoforge` implements NeoForge network setup, frozen registries, data maps, enums,
  feature flags, configuration files, split payloads, and Play-route activation.
- `com.hiczp.minecraft.protocol.forge` implements the distinct Forge hostname marker, Login wrapper helpers, mod/channel
  versions, registry/configuration tasks, and Play routes.

Use `FabricProtocol.connectionDefinition`, `NeoForgeProtocol.connectionDefinition`, or
`ForgeProtocol.connectionDefinition` to compose each profile's fixed codecs with application codecs. NeoForge and Forge
provide reusable definition objects plus one-connection profile instances. Fabric profiles likewise retain supplied
registry snapshots and resolved contexts by reference; create a fresh profile for each connection.

The selected Forge source revision has its old `VanillaPacketSplitter` disabled, so the Forge profile does not invent a
splitter that is absent on that peer. Quilt has no preset profile; applications can still declare its packet codecs and
write negotiation directly through `MinecraftPacketConnection`.

## Failure and lifetime

Closing a connection closes its pumps and transport. EOF, framing errors, malformed known payloads, and pump failures
close the channels with the original cause; catch it from `incoming.receive`, channel iteration, or `awaitClosed` for
logging and application policy. Negotiation-policy exceptions occur in the caller's coroutine and leave packet sending
to the caller when the wire state still permits it. Profile mismatch exceptions that have a protocol-defined failure
payload expose that packet, but never send it automatically.

Closing `outgoing` drains values already accepted by that channel and then closes the connection. Calling
`connection.close()` is the immediate, idempotent cancellation path and may discard queued outgoing values.
