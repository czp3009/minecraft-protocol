# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

`MinecraftProtocolFormat` implements `BinaryFormat` and interprets the structural serializers and wire annotations
supplied by
`protocol-model`. Raw packet NBT is recognized through the `nbt` logical serializer bridge and its no-name binary form
is delegated to `nbt-serialization`.

The caller-owned stream API is canonical. Decoding takes the payload boundary established by the framing layer:

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

// BinaryFormat byte-array methods wrap the same stream path.
val bytes = MinecraftProtocolFormat.encodeToByteArray(HandshakePacket.serializer(), handshake)
```

`MinecraftPacketRegistry` adds packet identity:

```kotlin
val encoding = MinecraftPacketRegistry.encodePayloadToSink(packet, payloadSink)
val codec = MinecraftPacketRegistry.codec(
    encoding.key.state,
    encoding.key.direction,
    encoding.key.id,
)
```

A configured format supplies connection-specific limits and contextual values:

```kotlin
val format = MinecraftProtocolFormat(
    MinecraftProtocolFormatConfiguration(chunkSectionCount = sectionCount),
)
```
