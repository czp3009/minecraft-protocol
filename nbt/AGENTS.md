# NBT module guidance

This file extends the repository `AGENTS.md`.

- Keep the public binary API centered on `kotlinx.io.Source` and `Sink`.
- Match official Java modified UTF and named-root behavior.
- Preserve every NBT tag without version-specific chunk semantics.
- Apply limits before allocation and reject malformed or trailing byte-array input.
- Keep packet and filesystem adapters outside this module.

Run `:nbt:nbtLayerTest` after changes. Shared NBT changes also require the packet-format and world-storage gates.
