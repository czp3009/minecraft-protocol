# nbt

This module owns the format-independent NBT value algebra, document wrappers, intrinsic invariants, and logical
serializer handoff.

## Local invariants

- Containers and primitive arrays are immutable snapshots.
- `TAG_End` is accepted only in domain-valid positions. Lists may contain mixed non-END tags as required by the selected
  official release.
- Serializer bridges expose tag trees, never buffers, byte order, compression, or I/O.
- Keep the public model independently usable with only `kotlinx-serialization-core`; physical formats belong in
  `nbt-serialization`.

## Verification

Run `:nbt:jvmTest`. Changes to serializer handoff also require `:nbt-serialization:jvmTest` and affected protocol or
world tests.
