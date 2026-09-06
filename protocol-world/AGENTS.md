# protocol-world

This module owns filesystem-independent conversion between world-format values and Play packets.

- Keep connection registration, tracking, bundles across entities, enqueue policy and incremental world application in
  the endpoint or application. A directional codec converts only the supplied domain value or finite pairing input.
- Decode raw Section bytes through `MinecraftChunkSectionPayloadFormat`. The packet model and its outer physical
  format never obtain a world layout through this module.
- Packet fields and storage fields have different coverage. Invoke the explicit missing-data provider once per full
  Chunk input; preserve its ordinary mutable references. Required-data callbacks run only when the input lacks a value
  needed by the chosen projection, and their results never update the input.
- Block-entity update tags are explicit projections, not complete persisted NBT. Keep registry ID resolution in the
  supplied `PacketCodecContext` and content projection in the direction's mappings.

Run `:protocol-world:jvmTest`; changed wire projection also requires `:protocol-serialization:jvmTest` and the affected
endpoint JVM suites.
