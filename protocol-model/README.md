# protocol-model

Format-independent Kotlin models for Minecraft Java Edition packet payloads and reusable protocol values.

The module provides:

- packet marker interfaces grouped by connection state and direction;
- logical clientbound Bundle values with intrinsic size and nesting invariants;
- structured values for server status, items, chunks, chat, commands, entities, registries, recipes, and other packet
  fields;
- sealed variants and logical `kotlinx.serialization` serializers for conditional protocol shapes;
- open direction-specific packet extension branches plus lossless `PacketRoute`/`UnknownPacket` values;
- static, remote, and resolved registry data models for dynamic block-state and registry IDs;
- wire-hint annotations interpreted by [`protocol-serialization`](../protocol-serialization/README.md);
- packet and data-component identity annotations, validated at compile time and generated into dispatch tables by the
  private [`protocol-symbol-processor`](../protocol-symbol-processor/README.md).

Models contain values and invariants; binary byte layout is supplied by a `kotlinx.serialization` format such as
`MinecraftProtocolFormat`. For example, a Status handshake and request are ordinary model values—this module does not
encode or send them:

```kotlin
val handshakePacket: ServerboundPacket = HandshakePacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    serverAddress = "localhost",
    serverPort = 25_565,
    nextState = HandshakeNextState.STATUS,
)
val statusRequestPacket: ServerboundPacket = StatusRequestPacket
```

`StatusResponsePacket.status` is the shared `ServerStatus` value produced by a server and consumed by a client. Its
description, optional player sample, version, favicon bytes, and secure-chat claim remain typed here; the JSON protocol
string is only their physical representation in `protocol-serialization`. The nested status records avoid exposing a
second set of server-only models:

```kotlin
val serverStatus = ServerStatus(
    description = JsonTextComponent.literal("Ready"),
    players = ServerStatus.Players(max = 20, online = 3),
    version = ServerStatus.Version(
        name = MinecraftProtocol.MINECRAFT_VERSION,
        protocol = MinecraftProtocol.PROTOCOL_VERSION,
    ),
)
val statusResponsePacket: ClientboundPacket = StatusResponsePacket(serverStatus)
```

`ClientboundBundlePacket` is one logical Play value rather than a registered packet with its own numeric ID. Its
constructor retains a supplied `List` and accepts a general `Collection` by materializing it only when it is not already
a list. It validates at most 4,096 ordered sub-packets and rejects nested bundles or `BundleDelimiterPacket` values, so
callers must keep a retained list stable after construction. The delimiter remains a separate wire packet model;
[`protocol-session`](../protocol-session/README.md#clientbound-bundles) owns expansion and reconstruction at the
packet-session boundary.

## Structured values and sealed variants

Conditional protocol shapes are ordinary Kotlin types, so application logic stays exhaustive. Item stacks and their data
components are typical examples. Here `stoneId` is the raw item ID obtained from the active item registry:

```kotlin
val itemStack: ItemStack = ItemStack.Present(
    count = 32,
    itemId = stoneId,
    components = DataComponentPatch(
        added = listOf(DataComponent.MaxStackSize(value = 64)),
    ),
)

fun itemStackCount(itemStack: ItemStack): Int = when (itemStack) {
    ItemStack.Empty -> 0
    is ItemStack.Present -> itemStack.count
}
```

Registry data classes retain caller-supplied read-only collections by reference; callers must keep them stable because
lookup indexes are derived during construction. `RemoteRegistrySnapshot` is the explicit exception: it detaches the
loader mappings, entries, and aliases supplied to it. In the example, `staticRegistrySchema` is constructed from the
client's local vanilla/mod catalogue and `remoteRegistrySnapshot` is received from its loader negotiation:

```kotlin
val protocolRegistryContext: ProtocolRegistryContext = staticRegistrySchema.resolve(remoteRegistrySnapshot)

val biomeIds = protocolRegistryContext.registry(ProtocolRegistryContext.BIOME_REGISTRY)
    ?.entries
    ?.map { entry -> entry.id }
```

When a loader snapshot contains blocks absent from the local schema, resolution throws `MissingStaticBlockSchemas` and
exposes all missing identifiers through `blockIds`. Callers can therefore obtain or construct every required mod block
schema and retry without discovering failures one block at a time.

Routes without an active codec stay lossless as direction-correct `UnknownPacket` values that preserve the complete
route and body bytes. Here `outerPacketId` and `bodyBytes` are the validated header value and payload retained by the
framing/dispatch layer for that unknown route:

```kotlin
fun retainUnknownClientboundPayload(
    outerPacketId: Int,
    bodyBytes: ByteArray,
): UnknownPacket.Clientbound = UnknownPacket.Clientbound(
    packetRoute = PacketRoute.CustomPayload(
        connectionState = ConnectionState.PLAY,
        packetDirection = PacketDirection.CLIENTBOUND,
        packetId = outerPacketId,
        channel = Identifier("example:counter"),
    ),
    data = ByteString(bodyBytes),
)
```
