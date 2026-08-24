# protocol-model

This module owns format-independent packet payloads, shared protocol values, logical serializers, and declarative wire
metadata.

## Local invariants

- `model.packet` contains packet declarations and `@PacketInfo` identities; `model.type` contains shared values and
  logical variants; `model.wire` contains hints interpreted by physical formats.
- Application packets implement the open direction-specific extension branches. `PacketRoute` and `UnknownPacket` remain
  format-independent and lossless.
- Models are valid in common Kotlin, contain no buffers or I/O, and enforce intrinsic invariants in their constructors.
- Static, remote, and resolved registry models are immutable snapshots. Derived contexts retain large immutable
  collections by reference.
- Packet and data-component annotations are KSP inputs. The processor validates source coverage and generates runtime
  handoff tables; do not maintain parallel dispatch tables by hand.
- `MinecraftProtocol.kt` is generated from the declared target analysis and has no checked-in copy.

## Verification

Run `:protocol-model:jvmTest`. Packet identity, wire-hint, or shared-value changes also require
`:protocol-serialization:jvmTest`.
