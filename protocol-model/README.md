# protocol-model

Format-independent Kotlin models for Minecraft Java Edition packet payloads and reusable protocol values.

The module provides:

- packet marker interfaces grouped by connection state and direction;
- `@PacketInfo` metadata for deterministic registry generation;
- structured values for NBT, items, chunks, chat, commands, entities, registries, recipes, and other packet fields;
- sealed variants and logical kotlinx.serialization serializers for conditional protocol shapes;
- wire-hint annotations interpreted by `protocol-serialization`.

Models contain values and invariants. Binary byte layout is supplied by a kotlinx.serialization format such as
`MinecraftFormat`.
