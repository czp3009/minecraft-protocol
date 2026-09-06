# protocol-model

## Model and evidence boundaries

- `model.packet` owns packet declarations and `@PacketInfo`; `model.type` owns shared values and logical variants;
  `model.wire` owns hints interpreted by physical formats.
- Application packets implement the open direction-specific extension branches. `PacketRoute` and `UnknownPacket`
  preserve the complete route and opaque payload.
- Record justified top-level naming and shape differences in `@PacketInfo.nameException` and `shapeException`,
  respectively, and explain the official counterpart in [PACKET-MODELS.md](PACKET-MODELS.md). These are separate
  exceptions; replacing an unsuitable Java container does not justify renaming its packet.
- `ClientboundLevelChunkPacketData.buffer` is one immutable `ByteString` of contiguous Section payload bytes without
  its wire length prefix. It requires no dimension layout. `LevelChunkSectionData` is a public low-level seam for the
  physical Section format, not a field of the complete packet or a replacement for world-format's `ChunkSection`.
- Keep the official shared protocol `Identifier` here. World values use their role-specific IDs and convert at the
  protocol boundary.
- `RemoteRegistrySnapshot` is a detached snapshot and copies nested entries and aliases. Other registry values use
  the root guide's collection ownership rule.
- The vanilla report supplies route/resource identities. The separate `officialMinecraftPackets` artifact follows
  official registration and `PacketType` to Java classes and members; KSP consumes it as `minecraft.packetClasses`.
  Never derive Java names from resource paths.
- KSP validates route coverage, names and ordered fields with explicit exceptions. Audit nested values, types,
  nullability and conditional shape against the official producer/codec/consumer; generated checks cannot prove them.
- `MinecraftProtocol.kt`, packet definitions and component dispatch are generated. Change their source annotations or
  owning producer instead of maintaining parallel tables.

## Verification

Run `:protocol-model:jvmTest`; wire-visible changes also require `:protocol-serialization:jvmTest`.
