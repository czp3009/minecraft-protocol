# protocol-vanilla-data

Versioned vanilla data needed to communicate with the matching Minecraft Java Edition client.

The module exposes typed static block-state and entity-type catalogues, Configuration registry snapshots, dimension
layouts, feature flags, Known Packs, and tags. Its data is deterministically derived from the matching official server
under `build/generated` and included in published binaries and source JARs. It does not implement block behavior, entity
AI, world generation, or a general Datapack loader.

`VanillaProtocolData.Default` selects compact registry entries when a client accepts the offered Known Packs and
complete network NBT otherwise.
