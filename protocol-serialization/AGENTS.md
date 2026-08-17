# protocol-serialization

This module owns Minecraft packet-payload encoding and decoding through `MinecraftProtocolFormat`, the immutable vanilla
packet registry, and connection-specific composed extension registries.

## Ownership

- `internal` contains physical primitive, palette, wire-hint, and packet-NBT adapter implementations.
- Packet NBT delegates the no-name any-tag grammar to `nbt-serialization`; this module does not duplicate NBT tag IDs,
  modified UTF, list wrapping, or binary-field validation.
- Packet models, logical variants, and identities remain in `protocol-model`.
- Production framing, compression, encryption, and sockets enter through `protocol-transport`, not serialization.
- Extension registration covers bounded Login-query, Configuration/Play custom-payload, and top-level numeric routes.
  Known malformed bodies and trailing bytes propagate. Only the explicit nested-unknown signal may become a lossless
  direction-correct `UnknownPacket`.
- Palette widths come from `MinecraftProtocolFormatConfiguration.registries`; never consult a global vanilla block or
  biome count for a connection codec.
- `MinecraftProtocolFormat.encodeToSink` and bounded `decodeFromSource` are the canonical payload paths. Registry and
  byte-array APIs delegate to them. Buffering is permitted only for a wire construct whose length must precede its
  encoded body, such as `@ByteLengthPrefixed`.
- NBT serialization failures retain the shared `SerializationException` hierarchy and are not wrapped solely to change
  the concrete exception name.
- Configuration capture and data-to-source generation remain in `protocol-vanilla-data` and `buildSrc`; this module has
  no generator bridge or CLI.

## Tests

Golden payloads, conditional branches, limits, malformed input, and registry-wide round trips belong in `commonTest`.
Codec-differential coverage also enters through `commonTest`, places its annotated entry under `fixturetest`, and calls
`minecraft-test-support`. This module has no official-server TCP scenario, `protocol-transport` test dependency, or
custom `networkTest` source set: real transport plus serialization interoperability belongs to the higher-level client
and server fixtures. Browser, D8, and WasmWASI tasks exclude the codec fixture by package while continuing to run
portable serialization tests. Official runtime loading remains inside the Fixture Host.

Run `:protocol-serialization:jvmTest` after changes.
