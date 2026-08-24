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
- Generic models and transformations belong in `protocol-datapack`. Vanilla helpers return the same public generic
  stages so callers can replace defaults and continue manually.

## Verification

Run `:protocol-datapack-vanilla:jvmTest` after model, generator wiring, payload-loading, or branch-selection changes.
