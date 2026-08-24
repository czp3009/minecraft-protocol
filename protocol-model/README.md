# protocol-model

Format-independent Kotlin models for Minecraft Java Edition packet payloads and reusable protocol values.

The module provides:

- packet marker interfaces grouped by connection state and direction;
- structured values for items, chunks, chat, commands, entities, registries, recipes, and other packet fields;
- sealed variants and logical `kotlinx.serialization` serializers for conditional protocol shapes;
- open direction-specific packet extension branches plus lossless `PacketRoute`/`UnknownPacket` values;
- immutable static, remote, and resolved registry models for dynamic block-state and registry IDs;
- wire-hint annotations interpreted by [`protocol-serialization`](../protocol-serialization/README.md);
- packet and data-component identity annotations, validated at compile time and generated into dispatch tables by the
  private [`protocol-symbol-processor`](../protocol-symbol-processor/README.md).

Models contain values and invariants; binary byte layout is supplied by a `kotlinx.serialization` format such as
`MinecraftProtocolFormat`. For example, a Status handshake and request are ordinary model values—this module does not
encode or send them:

```kotlin
val handshake: ServerboundPacket = HandshakePacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    serverAddress = "localhost",
    serverPort = 25_565,
    nextState = HandshakeNextState.STATUS,
)
val request: ServerboundPacket = StatusRequestPacket
```

## Structured values and sealed variants

Conditional protocol shapes are ordinary Kotlin types, so application logic stays exhaustive. Item stacks and their data
components are typical examples. Here `stoneId` is the raw item ID obtained from the active item registry:

```kotlin
val stack: ItemStack = ItemStack.Present(
    count = 32,
    itemId = stoneId,
    components = DataComponentPatch(
        added = listOf(DataComponent.MaxStackSize(value = 64)),
    ),
)

fun stackCount(stack: ItemStack): Int = when (stack) {
    ItemStack.Empty -> 0
    is ItemStack.Present -> stack.count
}
```

Immutable registry models resolve locally known block-state schemas against a loader-provided remote snapshot. In the
example, `staticSchema` is constructed from the client's local vanilla/mod catalogue and `remoteSnapshot` is received
from its loader negotiation:

```kotlin
val context: ProtocolRegistryContext = staticSchema.resolve(remoteSnapshot)

val biomes = context.registry(ProtocolRegistryContext.BIOME_REGISTRY)
    ?.entries
    ?.map { entry -> entry.id }
```

When a loader snapshot contains blocks absent from the local schema, resolution throws `MissingStaticBlockSchemas` and
exposes all missing identifiers through `blockIds`. Callers can therefore obtain or construct every required mod block
schema and retry without discovering failures one block at a time.

Routes without an active codec stay lossless as direction-correct `UnknownPacket` values that preserve the complete
route and body bytes. Here `bodyBytes` is the payload retained by the framing/dispatch layer for that unknown route:

```kotlin
val unknown = UnknownPacket.Clientbound(
    route = PacketRoute.CustomPayload(
        state = ConnectionState.PLAY,
        direction = PacketDirection.CLIENTBOUND,
        packetId = 0x3B,
        channel = Identifier("example:counter"),
    ),
    data = ByteString(bodyBytes),
)
```
