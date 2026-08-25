# protocol-datapack

This module owns vanilla-neutral, filesystem-independent data-pack resolution and Configuration projection.

## Local invariants

- Own data-pack resource models, stack resolution, projection into `ResolvedProtocolData`, the constructible
  `ProtocolData` contract, and `ClientRegistryView` values derived from `DataPackConfigurationSnapshot`.
- Keep `ProtocolData` domain-oriented: feature flags and registry tags are values; client/server orchestration creates
  the corresponding wire packets at the send boundary.
- Keep every public stage manually constructible. Generic conversion requires explicit base/default data and registry
  projectors.
- Never assume disk and network codecs are equivalent; registry projection uses caller-supplied
  `DataPackRegistryProjector` values in `DataPackProtocolProjector`.
- Do not depend on `protocol-datapack-vanilla`, access a filesystem, open a socket, or supply release-specific defaults.
- Received Configuration data contains only what the server transmitted. Do not present it as a reconstruction of
  recipes, loot tables, functions, or other server-only resources.

## Verification

Run `:protocol-datapack:jvmTest`.
