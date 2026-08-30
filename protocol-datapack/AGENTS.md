# protocol-datapack

This module owns vanilla-neutral, filesystem-independent data-pack-to-Configuration projection and the shared adaptation
of active protocol dimension/registry facts to semantic world-Chunk contracts.

## Local invariants

- Consume `world-format` data-pack resources and resolved stacks without redefining them. Own projection into
  `ResolvedProtocolData`, the constructible `ProtocolData` contract, and `ClientRegistryView` values derived from
  `DataPackConfigurationSnapshot`.
- Keep `ProtocolData` domain-oriented: feature flags and registry tags are values; client/server orchestration creates
  the corresponding wire packets at the send boundary.
- Keep every public stage manually constructible. Generic conversion requires explicit base/default data and registry
  projectors.
- Never assume disk and network codecs are equivalent; registry projection uses caller-supplied
  `DataPackRegistryProjector` values in `DataPackProtocolProjector`.
- `MinecraftDimensionLayout` combines synchronized identity/raw ID with `world-format`'s `DimensionTypeLayout`, and
  `MinecraftDimensionContext` validates that network handoff. `MinecraftChunkContext` contains only the dimension ID,
  layout, active registries, `ChunkCodecContext`, and `ChunkNbtCodec`; disk and Chunk packet bodies must not require a
  synchronized dimension-type raw ID. Retain immutable registries by reference.
- `ResolvedProtocolData.resolveMinecraftWorld` resolves every persisted referenced dimension type against the exact
  complete synchronized registry order before constructing contexts. Aggregate dimension failures, reject inline holders
  on this server-negotiable path, and never create partial results or synthetic registry entries.
- `ResolvedProtocolData.resolveMinecraftChunkContexts` is the semantic disk/custom-endpoint path. Resolve referenced
  holders against the same registry data, decode inline holders directly, aggregate failures, and return no partial map.
- Keep `ChunkDataRegistries` and `ChunkCodecContext` usable as lower-level custom-codec branch points. Do not add
  synonymous composition helpers that only pass their properties onward.
- Do not move packet encoding/decoding, connection state, initial-world snapshots, or filesystem behavior into these
  shared adapters.
- Do not depend on `protocol-datapack-vanilla`, access a filesystem, open a socket, or supply release-specific defaults.
- Received Configuration data contains only what the server transmitted. Do not present it as a reconstruction of
  recipes, loot tables, functions, or other server-only resources.

## Verification

Run `:protocol-datapack:jvmTest`.
