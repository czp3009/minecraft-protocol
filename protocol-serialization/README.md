# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

`MinecraftProtocolFormat` implements `BinaryFormat` and interprets the structural serializers and wire annotations from
[`protocol-model`](../protocol-model/README.md). The caller-owned stream API is canonical; decoding takes the payload
boundary established by framing. In the example, `handshake` is the `HandshakePacket` to encode, `payloadSink` is the
caller's destination, and `payloadSource` plus `payloadByteCount` are the bounded payload supplied by the framing layer:

```kotlin
MinecraftProtocolFormat.encodeToSink(
    HandshakePacket.serializer(),
    handshake,
    payloadSink,
)

val packet = MinecraftProtocolFormat.decodeFromSource(
    HandshakePacket.serializer(),
    payloadSource,
    payloadByteCount,
)
```

`MinecraftPacketRegistry` is the immutable vanilla base for the project-selected Minecraft release. Compose a
connection-specific registry with application or loader packet codecs instead of mutating a global table. Here
`myPacketCodecs` is the caller's collection of extension registrations and `packet` is the packet value being encoded:

```kotlin
val packetRegistry = MinecraftPacketRegistry.compose(myPacketCodecs)
val encoded = packetRegistry.encodePayload(packet)

val decoded = packetRegistry.decodePayload(
    state = encoded.key.state,
    direction = encoded.key.direction,
    id = encoded.key.id,
    payload = encoded.payload,
)
```

Registration factories cover Login queries, Configuration/Play custom payloads, and top-level numeric packet IDs. Most
packet bodies can use `KotlinxPacketBodyCodec` with an ordinary `KSerializer`; implement `PacketBodyCodec` only for a
genuinely physical rule such as nested discrimination. When the same extension class is declared in more than one phase,
select its state and direction explicitly:

```kotlin
val encoded = packetRegistry.encodePayload(
    packet,
    state = ConnectionState.PLAY,
    direction = PacketDirection.CLIENTBOUND,
)
```

Dynamic block-state and biome palette widths come from the registry context installed on a configured format. Here
`staticRegistrySchema` comes from the local vanilla/mod catalogue, `remoteRegistrySnapshot` comes from loader
negotiation, and `sectionCount` comes from the active dimension layout:

```kotlin
val protocolRegistryContext = staticRegistrySchema.resolve(remoteRegistrySnapshot)
    .withChunkSectionCount(sectionCount)
val minecraftProtocolFormat = MinecraftProtocolFormat(
    MinecraftProtocolFormatConfiguration(registries = protocolRegistryContext),
)
```

Malformed known bodies, invalid identifiers, violated wire-field bounds, and unread trailing bytes throw; they are never
treated as unknown packets. The format does not add a shared policy-sized collection, byte-array, NBT-depth, or
total-allocation ceiling beyond the bounds declared by the packet field itself.
