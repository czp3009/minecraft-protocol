# protocol-serialization guidance

This module inherits the repository guidance.

- `MinecraftFormat` implements packet payload encoding and decoding through kotlinx.serialization.
- `internal` contains physical primitive, NBT, palette, and wire-hint implementations.
- `PacketRegistry` maps packet identity to serializers.
- `GeneratedPacketRegistryEntries.kt` is regenerated and checked through Gradle tasks.
- Common tests own golden payloads, branch coverage, limits, malformed input, and registry-wide round trips.
- JVM test fixtures own finite-registry manifests, official-codec differential execution, packet framing, compression,
  process control, and official-server interoperability.
- Production transport APIs enter a dedicated networking stage and package.
