# protocol-configuration-vanilla

This module owns generated immutable Configuration defaults for the repository-selected official release.

- Own static registries, block schemas, Known Packs branches, feature flags, tags and `VanillaConfigurationData`.
  Actual archives, parsed packs and world-selection completion belong to datapack-vanilla; neither provider acquires a
  runtime dependency on the other.
- Decode generated Configuration packet payloads through protocol-serialization. Keep that physical-format dependency
  here; generic Configuration projection and the archive provider do not require it.
- Consume declared root analysis artifacts. The data-pack extraction manifest provides the format version without
  copying archive payloads or reading another module's generated output.
- `vanillaDataPackRegistryProjectors` covers the synchronized registries exposed by generated Configuration data.
  Derive that set from the artifact instead of copying IDs; caller projectors replace matching IDs or extend new IDs.
- Keep stack-to-Configuration projection separate from world-selection completion and filesystem reads. Accept world
  feature flags explicitly; no convenience reconstructs a hidden combined world/configuration/codec object.
- `VanillaRegistryData` owns static schemas; `VanillaConfigurationData` owns complete/compact Configuration data and its
  derived client view. Preserve those stage names in handwritten and generated declarations.

Run `:protocol-configuration-vanilla:jvmTest`. Registry projection also requires the server JVM suite, whose official
client scenario projects bundled synchronized registry entries from disk JSON before entering Play.
