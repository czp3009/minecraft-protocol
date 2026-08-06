# protocol-serialization

This module owns Minecraft packet-payload encoding and decoding through `MinecraftFormat` and the physical registry
adapter built from generated packet definitions.

## Ownership

- `internal` contains physical primitive, palette, wire-hint, and packet-NBT adapter implementations.
- Packet NBT delegates the no-name any-tag grammar to `nbt-serialization`; this module does not duplicate NBT tag IDs,
  modified UTF, list wrapping, or NBT limits.
- Packet models, logical variants, and identities remain in `protocol-model`.
- Production framing, compression, encryption, and sockets enter through `protocol-transport`, not serialization.
- Configuration capture and data-to-source generation remain in `protocol-vanilla-data` and `buildSrc`; this module has
  no generator bridge or CLI.

## Tests

Golden payloads, conditional branches, limits, malformed input, and registry-wide round trips belong in `commonTest`.
Official-server and codec-differential entries also belong in `commonTest` and call `minecraft-test-support`. Standard
platform test source sets provide only the TCP transport unavailable to common code. JS/Node excludes the TCP scenario;
browser tasks and Wasm/WASI exclude official-peer tests. Official runtime loading remains inside the Fixture Host.

Run `:protocol-serialization:jvmTest` after changes.
