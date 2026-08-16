# protocol-serialization

Minecraft Java Edition packet-payload serialization built on `kotlinx.serialization`.

Serialization has no network or filesystem requirement and keeps the repository's complete supported KMP matrix,
including JS Browser and WasmJS Node, Browser, and D8 plus the private Wasm/WASI scaffold.

`MinecraftProtocolFormat` implements `BinaryFormat` and interprets structural serializers and wire annotations from
`protocol-model`. Raw packet NBT uses the logical `nbt` serializer bridge and delegates physical no-name NBT to
`nbt-serialization`.

The caller-owned stream API is canonical. Decoding takes the payload boundary established by framing:

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

`MinecraftPacketRegistry` is the immutable repository-selected vanilla base. Compose a connection registry with
application or loader declarations instead of mutating a global table:

```kotlin
val packetRegistry = MinecraftPacketRegistry.compose(myPacketCodecs)
val encoded = packetRegistry.encodePayload(packet)
```

If a startup-time modded protocol table reassigns vanilla numeric IDs, build a custom immutable base from
`MinecraftPacketRegistry.entries`: filter entries as needed, call `PacketCodec.withPacketId(...)` for moved entries, and
pass that list plus extension registrations to `PacketRegistry`. Codec and serializer instances are reused, and the
result can be shared by every connection using that protocol definition.

When the same extension class is declared in more than one phase, select its state and direction explicitly:

```kotlin
val encoded = packetRegistry.encodePayload(
    packet,
    state = ConnectionState.PLAY,
    direction = PacketDirection.CLIENTBOUND,
)
```

`PacketCodecRegistration` supports bounded Login-query, Configuration/Play custom-payload, and top-level numeric-ID
routes. `KotlinxPacketBodyCodec` adapts an ordinary `KSerializer`.
`MappedKotlinxPacketBodyCodec` keeps the body serializer separate from route metadata such as a Login transaction ID.
Implement `PacketBodyCodec` only for nested discrimination or another genuinely physical rule, while continuing to
delegate structured fields to `MinecraftProtocolFormat`.

A configured format carries the immutable registry context used by palette codecs:

```kotlin
val context = staticRegistrySchema.resolve(remoteRegistrySnapshot)
    .withChunkSectionCount(sectionCount)
val format = MinecraftProtocolFormat(
    MinecraftProtocolFormatConfiguration(registries = context),
)
```

Global block-state and biome palette widths are derived from that context, including modded registry ordering. Missing
required context, truncated or malformed known bodies, invalid identifiers, limits, and unread trailing bytes throw;
they are never treated as unknown packets. `UnknownExtensionPacketException` is the explicit narrow signal for a valid
but unknown nested extension discriminator.
