# protocol-model

This module owns format-independent packet payloads and shared protocol values.

Shared NBT values come from the `nbt` API dependency. Protocol-only wrappers such as `TextComponent` and wire hints such
as `NetworkNbt` remain here; NBT tag declarations and binary grammar do not.

## Source structure

- `model.packet` contains packet declarations and `@PacketInfo` protocol identities.
- `model.type` contains reusable values and sealed logical variants.
- `model.wire` contains declarative hints interpreted by physical formats.

Application packet types implement the open direction-specific extension branches. `PacketRoute` and `UnknownPacket`
remain format-independent and lossless. Static, remote, and resolved registry models are immutable snapshots; derived
contexts retain their large registry and block-state collections by reference.

Models remain valid in common Kotlin source sets, contain no buffers or I/O, and enforce intrinsic value invariants in
constructors. Presence and discriminator rules stay with the corresponding model through Kotlin types, annotations, or
logical serializers.

## Generation and tests

Packet and data-component identity annotations are KSP inputs. The private processor validates coverage against the
official packets report and generates portable runtime handoff tables; manual dispatch tables do not belong in source.
`MinecraftProtocol.kt` is generated from the root target-analysis artifact and has no source-tree copy.

Add format-independent common tests for new or changed invariants. Run `:protocol-model:jvmTest` and
`:protocol-serialization:jvmTest` after changing packet identities, wire hints, or shared values.
