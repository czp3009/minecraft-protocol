---
name: minecraft-protocol-vanilla-data
description: Implement, update, test, or audit the selected release's official vanilla protocol-data pipeline and handwritten consumers. Use for official packet/registry/block reports, protocol constants, static registries, block-state palettes, Configuration capture, Known Packs branches, synchronized registries, feature flags, tags, VanillaStaticData, VanillaProtocolData, ProtocolDataSet, MinecraftDimensionLayout, or the Gradle analyzers and generators that own those artifacts. Never use this skill to hand-edit generated payload source.
---

# Minecraft vanilla protocol data

Maintain provenance from official server behavior to generated payloads and handwritten public loaders. Generated values
are task outputs, not an agent-maintained catalogue.

## Establish ownership

Confirm the target with `./gradlew -q minecraftVersion`, then read [references/pipeline.md](references/pipeline.md).

The handwritten surface includes official analyzer/capture/generator implementations in `buildSrc`,
`protocol-vanilla-data/src`, its tests, and any packet identity or schema assumptions used by loaders. The following
generated Kotlin is read-only:

- `MinecraftProtocol.kt`;
- `VanillaStaticDataPayloads.kt`;
- `VanillaConfigurationPayloads.kt`.

KSP-generated protocol dispatch is also read-only. Do not copy generated payloads or analysis JSON into source
directories.

## Update the pipeline

1. After an explicit target change, run `./gradlew officialMinecraftAnalysis`.
2. Inspect target, packet/registry/block reports, and both captured Configuration branches as declared task outputs.
3. If an official output shape or negotiation changes, adapt the owning analyzer or capture implementation rather than
   transcribing values.
4. If generated source fails to load, fix the handwritten generator schema or public loader at its owning boundary.
5. Derive every handwritten packet ID, ordering assumption, registry lookup, and dimension-layout field from
   selected-release evidence; do not preserve an old constant by memory.

Load the model and serialization skills when a data capture failure exposes a packet schema or codec change. Do not make
this module an alternate packet implementation.

## Verify and report

Run `./gradlew :protocol-vanilla-data:jvmTest`. For Configuration or synchronized-registry changes, also run
`./gradlew :protocol-client:jvmTest`; run the server JVM suite when its emitted vanilla data or initial Play context
changed. Let these standard tasks invoke their declared producers and fixtures.

Report which official analysis artifact changed, which handwritten pipeline layer required adaptation, both Known Packs
branch results, downstream official-peer results, and any official data the current public API cannot represent.
