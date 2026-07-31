# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

`MinecraftFormat` implements `BinaryFormat` and interprets the structural serializers and wire annotations supplied by
`protocol-model`.

```kotlin
val bytes = MinecraftFormat.encodeToByteArray(
    HandshakePacket.serializer(),
    handshake,
)

val packet = MinecraftFormat.decodeFromByteArray(
    HandshakePacket.serializer(),
    bytes,
)
```

`MinecraftPacketRegistry` adds packet identity:

```kotlin
val encoded = MinecraftPacketRegistry.encodePayload(packet)
val codec = MinecraftPacketRegistry.codec(
    encoded.key.state,
    encoded.key.direction,
    encoded.key.id,
)
```

A configured format supplies connection-specific limits and contextual values:

```kotlin
val format = MinecraftFormat(
    MinecraftFormatConfiguration(chunkSectionCount = sectionCount),
)
```

The module's standard `jvmTest` includes primitive and composite codecs, packet-specific golden vectors and branches,
full registry round trips, test-only framing/compression, direct official-codec differentials, and a real offline-mode
official server session.
