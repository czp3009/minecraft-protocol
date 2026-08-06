# nbt

This module owns the format-independent Java Edition NBT value algebra, document wrappers, intrinsic invariants, and the
logical raw-tag serializer handoff. Physical bytes live in `nbt-serialization`; packet and filesystem integration remain
in their owning modules.

## Invariants

- Containers and primitive arrays are immutable snapshots.
- `TAG_End` is legal only where the NBT domain permits it.
- Minecraft 26.2 logical lists permit mixed non-END tags; physical wrapping is delegated to `nbt-serialization`.
- Serializer bridge contracts expose trees only, never buffers, byte order, compression, or I/O.
- This module has no dependency on protocol, world, compression, or `kotlinx.io` code.
- The public model and serializer bridge remain independently consumable with only `kotlinx-serialization-core`.

## Verification

Run `:nbt:jvmTest`. A model or serializer-handoff change also requires `:nbt-serialization:jvmTest` and the affected
protocol and world suffix.
