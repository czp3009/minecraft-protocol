# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

`MinecraftPacketPayloadFormat` owns field bytes. `PacketRegistry` maps packet types to packet keys and extension routes,
returning packet-ID and framing metadata separately from the encoded body.
[`protocol-session`](../protocol-session/README.md) owns stateful dispatch, while
[`protocol-transport`](../protocol-transport/README.md) owns frames, compression, encryption, and sockets.

`ClientboundBundlePacket` is deliberately not a `PacketRegistry` entry: it is a logical session value without its own
packet ID. The registered delimiter and sub-packets are encoded individually after `protocol-session` expands the
bundle, and the client session reconstructs the logical value after decoding them.

## Encode and decode payloads

`MinecraftPacketPayloadFormat` implements `BinaryFormat` and interprets the structural serializers and wire annotations
from
[`protocol-model`](../protocol-model/README.md). The caller-owned stream API is canonical; decoding takes the payload
boundary established by framing. This complete in-memory example uses `kotlinx.io.Buffer` as both endpoints; a framed
connection supplies a bounded source instead:

```kotlin
val clientIntentionPacket = ClientIntentionPacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    hostName = "localhost",
    port = 25_565,
    intention = ClientIntent.STATUS,
)
val payloadBuffer = Buffer()
MinecraftPacketPayloadFormat.encodeToSink(ClientIntentionPacket.serializer(), clientIntentionPacket, payloadBuffer)
val decodedHandshakePacket = MinecraftPacketPayloadFormat.decodeFromSource(
    ClientIntentionPacket.serializer(), payloadBuffer, payloadBuffer.size.toInt(),
)
check(decodedHandshakePacket == clientIntentionPacket)
```

`ClientboundStatusResponsePacket` demonstrates the boundary between a logical value and a physical representation. Its
public field
is `ServerStatus`; the format interprets `@JsonEncoded`, writes one bounded protocol string, and reconstructs the same
typed value while decoding. Callers on either endpoint do not assemble or parse the enclosing status JSON. Malformed
JSON, missing required nested fields, invalid favicon data URLs, and the packet string bound fail during decoding or
encoding.

## Compose a packet registry

`MinecraftPacketRegistry` is the immutable vanilla base for the repository-selected Minecraft release. Construct a
connection-specific registry with application or loader packet codecs instead of mutating a global table.
`PacketCodecRegistration.clientboundCustomPayload(...)` constructs a custom registration as shown in
[protocol-session](../protocol-session/README.md#register-custom-packets). The default empty list uses only vanilla
entries. Pass the `clientIntentionPacket` constructed above, or another registered packet, to this helper:

```kotlin
fun roundTripPacket(
    packet: Packet,
    extensionCodecs: List<PacketCodecRegistration<out Packet>> = emptyList(),
): Packet {
    val packetRegistry = PacketRegistry(MinecraftPacketRegistry.entries, extensionCodecs)
    val encodedPacketPayload = packetRegistry.encodePayload(packet)
    return packetRegistry.decodePayload(
        connectionState = encodedPacketPayload.packetKey.connectionState,
        packetDirection = encodedPacketPayload.packetKey.packetDirection,
        id = encodedPacketPayload.packetKey.id,
        payload = encodedPacketPayload.payload,
    )
}
```

Registration factories cover Login queries, Configuration/Play custom payloads, and top-level numeric packet IDs. Most
packet bodies can use `KotlinxPacketBodyCodec` with an ordinary `KSerializer`; implement `PacketBodyCodec` only for a
genuinely physical rule such as nested discrimination. When the same extension class is declared in more than one phase,
select its state and direction explicitly with the `connectionState` and `packetDirection` arguments to
`PacketRegistry.encodePayload`. Use `ConnectionState.PLAY` and `PacketDirection.CLIENTBOUND`, for example, for a
clientbound Play extension whose codec was registered for more than one state.

## Configure dynamic registries

Dynamic block-state and biome palette widths come from the registry context installed on a configured format. Here
construct `StaticRegistrySchema(registries, blocks)` from the local catalogue, or take
`VanillaRegistryData.staticRegistrySchema` from protocol-configuration-vanilla. Construct `RemoteRegistrySnapshot(...)`
from the loader's decoded registry lists; `RemoteRegistrySnapshot.Empty` means no overrides. These become the
`staticRegistrySchema` and `remoteRegistrySnapshot` inputs below:

```kotlin
val packetCodecContext = staticRegistrySchema.resolve(remoteRegistrySnapshot)
val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
    MinecraftPacketPayloadFormatConfiguration(packetCodecContext = packetCodecContext),
)
```

Full Chunk packet bodies retain their Section payload as immutable `ByteString`. Their outer byte length is encoded
by `MinecraftPacketPayloadFormat`; that operation needs no dimension layout or Section count. To inspect or encode
the inner Sections, construct `MinecraftChunkSectionPayloadFormat` with
`MinecraftChunkSectionPayloadFormatConfiguration(packetCodecContext, sectionCount)`, where `sectionCount` comes from
the dimension layout. Domain conversion in [protocol-world](../protocol-world/README.md) composes those two stages.

## Failure behavior

Malformed known bodies, invalid identifiers, violated wire-field bounds, and unread trailing bytes throw; they are never
treated as unknown packets. The format does not add a shared policy-sized collection, byte-array, NBT-depth, or
total-allocation ceiling beyond the bounds declared by the packet field itself.
