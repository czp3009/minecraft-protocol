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
- Keep portable branch samples and round trips in `commonTest`. The official-server scenario shares the portable Ktor
  transport and `minecraft-test-support` external-process fixture across supported host targets; only the reflective
  official-codec differential oracle remains in `jvmTest`. Both run through standard platform test tasks.
- Production transport APIs enter a dedicated networking stage and package.
