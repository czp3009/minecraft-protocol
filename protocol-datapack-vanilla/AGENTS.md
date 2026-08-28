# protocol-datapack-vanilla

This module owns generated immutable defaults for the repository-selected official release and convenience factories
built on `protocol-datapack`.

## Local invariants

- Own the official data-pack archives, static registries, block states, Known Packs branches, feature flags, tags, and
  Configuration defaults. Runtime APIs never read the filesystem.
- `extractOfficialMinecraftDataPacks` alone extracts core and built-in packs. `generateVanillaDataPackSources` consumes
  that declared artifact and emits a manifest plus independently loaded batches.
- Do not hand-edit generated source, combine generated payloads into one eager property, or let a generator inspect the
  official server JAR directly.
- Generic data-pack representations and stack transformations belong in `world-format`; vanilla-neutral protocol
  projections and world-Chunk adapters belong in `protocol-datapack`. Vanilla helpers return those public stages so
  callers can replace defaults and continue manually.
- Keep `VanillaDataPacks` limited to actual archives, parsed packs, and stacks. `VanillaRegistryData` owns static
  registry/block values; `VanillaProtocolData` owns Configuration defaults and their derived client view.
- Handwritten `WorldDataPackLoadResult` extensions may complete a persisted world selection against bundled packs and
  project it. Preserve its low-to-high order, load only selected bundled packs, insert required core at the bottom,
  report other unavailable IDs, and do not add filesystem access or unlisted-pack discovery policy.
- Own release-matched `vanillaDataPackRegistryProjectors` for every synchronized registry exposed by
  `VanillaProtocolData`. Derive that registry set from generated Configuration data rather than copying IDs; caller
  projectors override matching vanilla IDs and extend new mod IDs.
- Keep generated manifest descriptors and handwritten accessors aligned: `dataPackId`, `dataPackIndex`, archive,
  parsed-pack, stack, protocol-data, Configuration-snapshot, and client-registry-view names must describe their stage.

## Verification

Run `:protocol-datapack-vanilla:jvmTest` after model, generator wiring, payload-loading, projector, or branch-selection
changes. Default registry projection also requires the server JVM suite, whose official-client scenario projects every
bundled synchronized vanilla registry entry from disk JSON before entering Play.
