# protocol-serialization

This module owns physical packet-payload encoding, the immutable vanilla packet registry, and composed
connection-specific extension registries.

## Local invariants

- Packet NBT delegates to `nbt-serialization`; do not duplicate tag IDs, modified UTF, list wrapping, or NBT validation.
- Extension registration covers bounded Login-query, Configuration/Play custom-payload, and top-level numeric routes.
  Known malformed bodies and trailing bytes propagate. Only the explicit nested-unknown signal becomes a
  direction-correct `UnknownPacket`.
- Palette widths come from `MinecraftPacketPayloadFormatConfiguration.packetCodecContext`, never from a global vanilla
  count.
- `MinecraftPacketPayloadFormat.encodeToSink` and bounded `decodeFromSource` are canonical. Byte-array and registry
  helpers delegate to them; buffer only a wire construct whose length must precede its body. This bidirectional format
  stays one type because both operations are physical transformations within the network representation layer; the
  repository-wide directional encoder/decoder naming rule applies at representation-to-domain boundaries.
- For `ClientboundLevelChunkPacketData`, read and write the official VarInt-prefixed, intrinsically bounded raw Section
  payload as the model's `ByteString`. This physical format enforces the matching release's byte-length rule but does
  not require a Chunk layout or parse typed Sections; that conversion belongs to the Chunk packet decoder and encoder.
- Publicly own the narrow `MinecraftChunkSectionPayloadFormat` used by that cross-module semantic conversion. Its
  configuration contains the
  connection's `PacketCodecContext` and current section count; it parses only packet-layer palette/container values
  from a bounded Source or writes them to a Sink. It never accepts world-format `Chunk`/`ChunkContext` values or owns
  the enclosing VarInt byte length. Keep it one bidirectional physical format, and let `protocol-world` derive its
  configuration and compose it with the directional Chunk packet codecs.
- Treat official codec-oracle and round-trip cases as sampled physical-wire evidence. Packet field names, types, order,
  nullability, and conditional codecs still require the reviewable producer/consumer/codec audit owned with the packet
  model; do not claim that KSP or a finite sample proves the complete schema.
- Keep framing, compression envelopes, encryption, and sockets in `protocol-transport`. Keep Configuration capture and
  vanilla generation outside this module.

## Verification

Run `:protocol-serialization:jvmTest`.
