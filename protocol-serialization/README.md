# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

`MinecraftFormat` implements `BinaryFormat` and interprets the structural serializers and wire annotations supplied by
`protocol-model`. Raw packet NBT is recognized through the `nbt` logical serializer bridge and its no-name binary form
is delegated to `nbt-serialization`.

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
