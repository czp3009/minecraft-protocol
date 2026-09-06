---
name: minecraft-vanilla-data
description: "Implement or audit official data analysis/generation and Configuration consumers: reports, vanilla packs, static block/registry data, Known Packs capture, synchronized registries, feature flags, tags and dimension resolution. Covers buildSrc producers, datapack-vanilla, protocol-configuration and protocol-configuration-vanilla."
---

# Minecraft vanilla data

Confirm the release, then read [pipeline.md](references/pipeline.md). Locate the declared producer and handwritten
consumer for the failing artifact. Generated payloads are evidence/output, not an editable data catalogue.

## Update the affected stage

1. After an authorized target change, prepare official data through the existing root task.
2. Inspect reports, class/member analysis, both Configuration capture branches and extracted packs as needed.
3. Fix changed official output/capture behavior in its analyzer; fix source rendering in its generator; fix public
   decoding or lookup behavior in the owning handwritten consumer.
4. For world selection, inspect preserved priority, core insertion and lazy per-pack loading in datapack-vanilla.
   Configuration projection is a separate step with explicit world feature flags.
5. Check generic Configuration values and dimension resolvers independently of generated defaults. Trace every
   handwritten packet-ID, registry-order or layout assumption back to the artifact or official codec.

Load model/serialization workflows for schema/byte failures exposed by capture. Use world-format for generic pack
parsing and world-projection for Chunk/Entity packet conversion.

## Verify

Run `:datapack-vanilla:jvmTest` for pack loading/completion, `:protocol-configuration:jvmTest` for generic projection
and
`:protocol-configuration-vanilla:jvmTest` for defaults. Registry changes additionally require affected endpoint suites;
the server's official-client scenario tests bundled synchronized-registry projection. Report producer/consumer changes,
Known Packs coverage and any unsupported official data.
