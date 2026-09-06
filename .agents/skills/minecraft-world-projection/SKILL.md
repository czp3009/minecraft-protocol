---
name: minecraft-world-projection
description: "Implement or audit semantic world-to-packet conversion in protocol-world and its endpoint adapters. Use for Chunk, Entity or ItemStack packet encoders/decoders, missing-data providers, block-entity update tags, entity pairing, data-component mappings, or disk-memory-network user computation tests. Do not use for physical packet bytes, world file I/O or gameplay implementation."
---

# Minecraft world projection

## Inspect both representations

Read protocol-world's AGENTS and [the world field inventory](../../../world-format/CHUNK-DATA.md). Trace each affected
field through the selected official saved codec, runtime value, packet producer and packet consumer. Obtain matching
artifacts through the existing Gradle producers when further evidence is needed.

Build a small working table of field owner, canonical identity, saved form, packet form and omitted information. Do not
infer equivalence from matching field names. In particular:

- full Chunk packets contain terrain, height/light data and selected Block Entity update tags;
- inventory/menu packets may carry private content absent from a Block Entity update tag;
- Entity pairing is a finite sequence whose runtime IDs/relations are supplied separately;
- EntityChunk and PoiChunk have no whole-record vanilla network representation;
- an ItemStack component patch distinguishes inherited, removed and replaced values.

Keep findings in working notes; update the public inventory only when the supported contract changes.

## Trace the conversion path

1. Start from the explicitly constructed encoder/decoder and its complete directional context. Account for every
   required fact omitted by the input representation, including dimension defaults and per-operation pairing data.
2. Follow canonical IDs through the supplied registry mapping. Physical Section bytes go through
   `MinecraftChunkSectionPayloadFormat`; load the serialization skill if those bytes must change.
3. Verify read/write mappings operate on the current shared properties. A typed view must reach the same value as
   dynamic access; packet values must not install a second mutable property store.
4. Check the full-Chunk missing-data provider runs once and preserves supplied references. Required-data callbacks
   should run only for missing projection inputs, with no input mutation.
5. Follow endpoint adapters only for batch registration, relation resolution, ordering and enqueue. The plain codec
   remains the conversion owner; inspect reconfiguration/respawn bindings when context lifetime changes.

## Evaluate caller code

Write or extend portable tests as a user would write a server/client:

1. Decode saved data using explicit NBT contexts; inspect metadata separately from the mutable graph.
2. Wrap shared properties in test-only inventory/content types. Perform a meaningful computation such as deposit,
   withdrawal or moving an item between two inventories, then assert the shared data changed.
3. Encode the current graph for storage and reread it, including nested custom fields, deletion and primitive widths.
4. Project supported network fields, pass through the real packet registry/physical format, and decode into a client
   graph with explicit missing data. Assert both transmitted fields and the intended handling of untransmitted state.
5. Apply menu or incremental packets in test application code when a full Chunk projection does not carry the state.

Assess the caller code itself: repeated configuration, manual raw-ID conversions, parallel typed/raw copies or a need to
reach internal APIs can indicate an ownership/API defect. Fix the owning capability when justified; keep game-specific
keys, sizes, rules and wrappers in tests. Expected network omissions are not defects to repair with hidden defaults.

## Verify

Run `:protocol-world:jvmTest`. Changed physical projections also need `:protocol-serialization:jvmTest`; changed disk
mappings need world-format/world-io tests, and changed endpoint adapters need the affected client/server suite. Report
coverage by representation and identify data intentionally absent from the network.
