# protocol-serialization guidance

This module inherits the repository guidance.

- `MinecraftFormat` implements packet payload encoding and decoding through kotlinx.serialization.
- `internal` contains physical primitive, NBT, palette, and wire-hint implementations.
- `PacketRegistry` maps packet identity to serializers.
- `GeneratedPacketRegistryEntries.kt` is generated under `build/generated` from official reports and local
  `@PacketInfo` declarations; do not commit a source-tree copy.
- Common tests own golden payloads, branch coverage, limits, malformed input, and registry-wide round trips.
- The private `jvmTool` compilation owns deterministic official Configuration capture. Shared JVM interop support is
  compiled only into the tool and tests, never publication.
- JVM tests own official-codec differential execution, packet framing, compression, process control, and official-server
  interoperability. They run through the standard `jvmTest` task.
- Production transport APIs enter a dedicated networking stage and package.
