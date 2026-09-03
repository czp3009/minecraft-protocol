# protocol-model

This module owns format-independent packet payloads, shared protocol values, logical serializers, and declarative wire
metadata.

## Local invariants

- `model.packet` contains packet declarations and `@PacketInfo` identities; `model.type` contains shared values and
  logical variants; `model.wire` contains hints interpreted by physical formats.
- Application packets implement the open direction-specific extension branches. `PacketRoute` and `UnknownPacket` remain
  format-independent and lossless.
- Packet declarations, packet-owned nested values, and fields use the matching official Java simple names, record
  components or stable members, nesting, and field order by default, including `Clientbound`, `Serverbound`, `Level`,
  and state terms. Kotlin/KMP may replace an unsuitable mutable/runtime container while preserving its field and wire
  semantics. Record every deliberately shared or otherwise non-official name as a narrow, reviewable exception; a
  shorter historical project name or local style preference is not sufficient justification.
- Models are valid in common Kotlin, contain no mutable buffers or I/O, and enforce intrinsic invariants in their
  constructors. An immutable `ByteString` is appropriate when the official packet itself owns an opaque byte sequence.
- `ClientboundLevelChunkPacketData` retains its contiguous Section payload bytes, excluding the wire length prefix, as
  one raw immutable `ByteString`; it does not expose typed Chunk Sections or require a dimension layout. Semantic
  Section projection belongs to the adjacent Chunk packet encoder and decoder. Keep the packet-layer typed Section
  value needed by the cross-module physical-format seam as an explicitly low-level public model; it is not a field of
  the complete Chunk packet and is not the world-format `ChunkSection`.
- Keep the official shared protocol `Identifier` in this module. World modules retain role-specific IDs and convert at
  protocol boundaries; do not reverse the dependency on `world-format` or create a broad core module for one value.
- `RemoteRegistrySnapshot` is the detached ownership boundary for loader mappings and copies nested entries and aliases.
  Static and resolved registry values use the repository-wide caller-owned collection policy.
- The Gradle-produced `packets.json` enumerates the official registered packet inventory by connection state,
  direction, protocol ID, and resource identity such as `minecraft:add_entity`; it contains neither official Java
  class names nor payload members. Do not mechanically derive a `Clientbound...Packet` or `Serverbound...Packet` class
  name, nested type, or field name from that resource identity.
- Packet and data-component annotations are KSP inputs. Limit processor validation to source coverage, report identity
  agreement, explicitly recorded naming exceptions, and runtime handoff generation; do not maintain parallel dispatch
  tables by hand. For every packet, trace the report entry through the matching official registration and `PacketType`
  to its Java class, then manually or with an agent audit the class simple name, nested values, fields, declaration
  order, types, nullability, and conditional shape against the selected release's official producer, consumer, and
  codec. Keep a reviewable checklist; generated checks and codec-oracle samples do not replace that audit.
- `MinecraftProtocol.kt` is generated from the declared target analysis and has no checked-in copy.

## Verification

Run `:protocol-model:jvmTest`. Packet identity, wire-hint, or shared-value changes also require
`:protocol-serialization:jvmTest`.
