# protocol-serialization

This module owns Minecraft packet-payload encoding and decoding through `MinecraftProtocolFormat` and the physical
registry
adapter built from generated packet definitions.

## Ownership

- `internal` contains physical primitive, palette, wire-hint, and packet-NBT adapter implementations.
- Packet NBT delegates the no-name any-tag grammar to `nbt-serialization`; this module does not duplicate NBT tag IDs,
  modified UTF, list wrapping, or NBT limits.
- Packet models, logical variants, and identities remain in `protocol-model`.
- Production framing, compression, encryption, and sockets enter through `protocol-transport`, not serialization.
- `MinecraftProtocolFormat.encodeToSink` and bounded `decodeFromSource` are the canonical payload paths. Registry and
  byte-array APIs delegate to them. Buffering is permitted only for a wire construct whose length must precede its
  encoded body, such as `@ByteLengthPrefixed`.
- NBT serialization failures retain the shared `SerializationException` hierarchy and are not wrapped solely to change
  the concrete exception name.
- Configuration capture and data-to-source generation remain in `protocol-vanilla-data` and `buildSrc`; this module has
  no generator bridge or CLI.

## Tests

Golden payloads, conditional branches, limits, malformed input, and registry-wide round trips belong in `commonTest`.
The shared official-server scenario and codec-differential coverage also enter through `commonTest` and call
`minecraft-test-support`. The `networkTest` capability source set supplies the one Ktor TCP entry shared by `jvmTest`,
`nativeTest`, and `wasmJsTest`; Android host, JS, and Wasm/WASI tests need no platform placeholders. Browser tasks and
Wasm/WASI exclude official-peer tests. Official runtime loading remains inside the Fixture Host.

Run `:protocol-serialization:jvmTest` after changes.
