# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

`MinecraftProtocolFormat` owns field bytes. `PacketRegistry` maps packet types to packet keys and extension routes,
returning packet-ID and framing metadata separately from the encoded body.
[`protocol-session`](../protocol-session/README.md) owns stateful dispatch, while
[`protocol-transport`](../protocol-transport/README.md) owns frames, compression, encryption, and sockets.

`ClientboundBundlePacket` is deliberately not a `PacketRegistry` entry: it is a logical session value without its own
packet ID. The registered delimiter and sub-packets are encoded individually after `protocol-session` expands the
bundle, and the client session reconstructs the logical value after decoding them.

## Encode and decode payloads

`MinecraftProtocolFormat` implements `BinaryFormat` and interprets the structural serializers and wire annotations from
[`protocol-model`](../protocol-model/README.md). The caller-owned stream API is canonical; decoding takes the payload
boundary established by framing. In the example, `handshakePacket` is the `HandshakePacket` to encode, `payloadSink`
is the caller's destination, and `payloadSource` plus `payloadByteCount` are the bounded payload supplied by the framing
layer:

```kotlin
MinecraftProtocolFormat.encodeToSink(
    HandshakePacket.serializer(),
    handshakePacket,
    payloadSink,
)

val decodedHandshakePacket = MinecraftProtocolFormat.decodeFromSource(
    HandshakePacket.serializer(),
    payloadSource,
    payloadByteCount,
)
```

## Compose a packet registry

`MinecraftPacketRegistry` is the immutable vanilla base for the repository-selected Minecraft release. Construct a
connection-specific registry with application or loader packet codecs instead of mutating a global table. Here
`myPacketCodecs` is the caller's collection of extension registrations and `packet` is the packet value being encoded:

```kotlin
val packetRegistry = PacketRegistry(MinecraftPacketRegistry.entries, myPacketCodecs)
val encodedPacketPayload = packetRegistry.encodePayload(packet)

val decodedPacket = packetRegistry.decodePayload(
    connectionState = encodedPacketPayload.packetKey.connectionState,
    packetDirection = encodedPacketPayload.packetKey.packetDirection,
    id = encodedPacketPayload.packetKey.id,
    payload = encodedPacketPayload.payload,
)
```

Registration factories cover Login queries, Configuration/Play custom payloads, and top-level numeric packet IDs. Most
packet bodies can use `KotlinxPacketBodyCodec` with an ordinary `KSerializer`; implement `PacketBodyCodec` only for a
genuinely physical rule such as nested discrimination. When the same extension class is declared in more than one phase,
select its state and direction explicitly:

```kotlin
val encodedPacketPayload = packetRegistry.encodePayload(
    packet,
    connectionState = ConnectionState.PLAY,
    packetDirection = PacketDirection.CLIENTBOUND,
)
```

## Configure dynamic registries

Dynamic block-state and biome palette widths come from the registry context installed on a configured format. Here
`staticRegistrySchema` comes from the local vanilla/mod catalogue, `remoteRegistrySnapshot` comes from loader
negotiation, and `sectionCount` comes from the active dimension layout:

```kotlin
val protocolRegistryContext = staticRegistrySchema.resolve(remoteRegistrySnapshot)
    .withChunkSectionCount(sectionCount)
val minecraftProtocolFormat = MinecraftProtocolFormat(
    MinecraftProtocolFormatConfiguration(protocolRegistryContext = protocolRegistryContext),
)
```

## Failure behavior

Malformed known bodies, invalid identifiers, violated wire-field bounds, and unread trailing bytes throw; they are never
treated as unknown packets. The format does not add a shared policy-sized collection, byte-array, NBT-depth, or
total-allocation ceiling beyond the bounds declared by the packet field itself.
