---
name: minecraft-protocol-vanilla-data
description: Implement, update, test, or audit the selected release's official vanilla protocol-data pipeline and handwritten consumers. Use for official packet/registry/block reports, protocol constants, official datapacks, static registries, block-state palettes, Configuration capture, Known Packs branches, synchronized registries, feature flags, tags, VanillaDataPacks, VanillaRegistryData, VanillaProtocolData, ProtocolData, ResolvedProtocolData, DataPackConfigurationSnapshot, ClientRegistryView, MinecraftDimensionLayout, or the Gradle analyzers and generators that own those artifacts. Never use this skill to hand-edit generated payload source.
---

# Minecraft vanilla protocol data

Maintain provenance from official server behavior to generated payloads and handwritten public loaders. Generated values
are task outputs, not an agent-maintained catalogue.

## Establish ownership

Confirm the target with `./gradlew -q minecraftVersion`, then read [references/pipeline.md](references/pipeline.md).

The handwritten surface includes official analyzer/capture/generator implementations in `buildSrc`,
`protocol-datapack/src`, `protocol-datapack-vanilla/src`, their tests, and any packet identity or schema assumptions
used by loaders. The following generated Kotlin is read-only:

- `MinecraftProtocol.kt`;
- `MinecraftWorldFormat.kt`;
- `VanillaRegistryDataPayloads.kt`;
- `VanillaConfigurationPacketPayloads.kt`;
- `VanillaDataPackPayload.kt` and every `VanillaDataPackPayloadBatch*.kt`.

KSP-generated protocol dispatch is also read-only. Do not copy generated payloads or analysis JSON into source
directories.

## Update the pipeline

1. After an explicit target change, run `./gradlew prepareOfficialMinecraftData`.
2. Inspect target, packet/registry/block reports, both captured Configuration branches, and extracted data-pack content
   as declared task outputs.
3. If an official output shape or negotiation changes, adapt the owning analyzer or capture implementation rather than
   transcribing values.
4. If generated source fails to load, fix the handwritten generator schema or public loader at its owning boundary.
5. Derive every handwritten packet ID, ordering assumption, registry lookup, and dimension-layout field from
   selected-release evidence; do not preserve an old constant by memory.

When completing a detached world data-pack selection, resolve only its selected IDs against bundled packs, preserve the
persisted low-to-high order, and load bundled payloads independently. Required-core insertion belongs here; filesystem
loading stays in `world-io`, generic selection/stack values stay in `world-format`, and unlisted-pack discovery is not a
read-side default.

Load the model and serialization skills when a data capture failure exposes a packet schema or codec change. Do not make
this module an alternate packet implementation.

## Verify and report

Run `./gradlew :protocol-datapack-vanilla:jvmTest`. For Configuration or synchronized-registry changes, also run
`./gradlew :protocol-client:jvmTest`; run the server JVM suite when its emitted vanilla data or initial Play context
changed. For generic parsing or projection changes, also run `./gradlew :protocol-datapack:jvmTest`. Let these standard
tasks invoke their declared producers and fixtures.

Report which official analysis artifact changed, which handwritten pipeline layer required adaptation, both Known Packs
branch results, downstream official-peer results, and any official data the current public API cannot represent.
