# protocol-model

Format-independent Kotlin models for Minecraft Java Edition packet payloads and reusable protocol values.

The module provides:

- packet marker interfaces grouped by connection state and direction;
- structured values for items, chunks, chat, commands, entities, registries, recipes, and other packet fields;
- an API dependency on the standalone `nbt` value algebra for packet fields that carry raw NBT;
- sealed variants and logical `kotlinx.serialization` serializers for conditional protocol shapes;
- wire-hint annotations interpreted by `protocol-serialization`.

Models contain values and invariants. Binary byte layout is supplied by a `kotlinx.serialization` format such as
`MinecraftProtocolFormat`.

For example, a Status handshake and request are ordinary format-independent model values; this module does not encode or
send them:

```kotlin
val handshake: ServerboundPacket = HandshakePacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    serverAddress = "localhost",
    serverPort = 25_565,
    nextState = HandshakeNextState.STATUS,
)
val request: ServerboundPacket = StatusRequestPacket
```
