# protocol-serialization

This module owns physical packet-payload encoding, the immutable vanilla packet registry, and composed
connection-specific extension registries.

## Local invariants

- Packet NBT delegates to `nbt-serialization`; do not duplicate tag IDs, modified UTF, list wrapping, or NBT validation.
- Extension registration covers bounded Login-query, Configuration/Play custom-payload, and top-level numeric routes.
  Known malformed bodies and trailing bytes propagate. Only the explicit nested-unknown signal becomes a
  direction-correct `UnknownPacket`.
- Palette widths come from `MinecraftProtocolFormatConfiguration.registries`, never from a global vanilla count.
- `MinecraftProtocolFormat.encodeToSink` and bounded `decodeFromSource` are canonical. Byte-array and registry helpers
  delegate to them; buffer only a wire construct whose length must precede its body.
- Keep framing, compression envelopes, encryption, and sockets in `protocol-transport`. Keep Configuration capture and
  vanilla generation outside this module.

## Verification

Run `:protocol-serialization:jvmTest`.
