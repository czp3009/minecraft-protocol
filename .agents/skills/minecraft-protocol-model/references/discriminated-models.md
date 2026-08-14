# Discriminated protocol models

Use this workflow for any model family selected by a registry ID, ordinal, tag, flags, or nested discriminator.

## Establish the discriminator table

Inspect the matching official registry and codec together. Capture the exact identifier, protocol ID or ordering rule,
payload type, and codec for every supported entry. Do not assume that source declaration order, enum ordinal, or a
previous release remains the wire ID unless official evidence proves it.

For `DataComponentType`, compare every handwritten `wireName` and ordinal-backed `protocolId` with the selected official
`minecraft:data_component_type` registry, then inspect the official component codec for its payload. KSP only checks
that local enum entries and local `@DataComponentInfo` classes correspond; it does not compare the enum with the
official registry.

Apply the same discipline to command parser IDs, entity-data serializers, particle options, recipe/display variants,
holder forms, and other handwritten protocol tables. Use Gradle-produced registry data when it exposes the fact; inspect
official bytecode for payload semantics that reports do not contain.

## Model the logical family

- Use a sealed hierarchy when variants have different payload shapes.
- Keep logical, buffer-free discriminator serializers in `protocol-model`.
- Keep physical primitive interpretation and registry-aware byte access in `protocol-serialization`.
- Preserve unknown or extension forms only when the official protocol provides a lossless representation for them; do
  not fabricate an `Unknown` branch that cannot be decoded safely.
- Keep removal sets, patch semantics, defaults, and nested dispatch distinct when the official codec distinguishes them.

## Verify completeness

Require one valid sample for every changed or newly added discriminator entry, plus branch samples for optional and
nested variants. Verify invalid IDs and malformed payloads at the layer that reads the discriminator. Run the official
packet codec oracle for every affected family that appears on the network.

Report any official registry entry whose logical payload or nullability cannot be determined, and do not call a locally
complete KSP dispatch an official completeness result.
