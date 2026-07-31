# protocol-serialization guidance

This module inherits the repository guidance.

- `MinecraftFormat` implements packet payload encoding and decoding through `kotlinx.serialization`.
- `internal` contains physical primitive, NBT, palette, and wire-hint implementations.
- `PacketRegistry` adapts the packet definitions generated in `protocol-model` into physical wire serializers.
- KSP generates `GeneratedPacketDefinitions.kt` and `GeneratedDataComponentSerializers.kt` from model annotations,
  validating packet coverage against the official report; do not commit source-tree copies.
- Common tests own golden payloads, branch coverage, limits, malformed input, and registry-wide round trips.
- Configuration capture is implemented entirely by the `buildSrc` task registered in `protocol-vanilla-data`; this
  runtime module exposes no generator bridge or CLI.
- Keep portable branch samples and round trips in `commonTest`. JVM tests own test-only packet framing, official-codec
  differential execution, and official-server interoperability through `minecraft-test-support`. They run through the
  standard `jvmTest` task.
- Production transport APIs enter a dedicated networking stage and package.
