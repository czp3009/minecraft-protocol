# Semantic world values and NBT schemas

## Inspect the official partition

Trace terrain, Block Entities, Entities and POI through their independent saved codecs and runtime owners. Compare
LevelChunk/Section values, pending Block Entity data, entity storage and POI sections. Retain the data needed by caller
computation while leaving official ticking, ownership and lifecycle machinery outside the domain graph.

For each mutable owner, identify structural fields, map-key identities and open properties. Check constructors retain
supplied references and empty construction creates data without generation. Exercise typed and dynamic access to the
same stored value, nested references, replacement and deletion from the root. Missing data must remain distinguishable
from known empty values wherever the model exposes that distinction.

## Audit domain codecs

- Pair each decoder and encoder with its complete context. Check streams and document entry points share semantics.
- Verify unknown fields and primitive widths at every supported open owner; test structural-name collisions and
  custom writers that recursively use the supplied mappings. Distinguish shared acyclic values from actual cycles.
- Verify persistence metadata remains outside the root domain value. Terrain ticks are absolute in memory and
  relative Int delays on disk: test explicit tick-base addition/subtraction and list-derived sub-tick order separately
  from `LastUpdate`.
- Check POI reads receive the position absent from the saved record. Terrain/entity stored positions are authoritative.
- Test completed-schema decoding with non-full status without status-based rejection. Unsupported generation-only
  content belongs to raw NBT preservation, not a ProtoChunk state machine.
- Check palette snapshot encoding does not mutate the input, including stable indices before explicit compaction.

Use minecraft-world-projection for the application simulation spanning disk, memory and packets. Keep content-specific
wrappers in tests and evaluate whether callers can express their changes through public mutable data alone.

## Audit standalone-file schemas

Inventory `LevelDat` and nested values, `PlayerData`, `SavedDataFile` and every root/dimension payload, advancements and
statistics. For each, inspect official writer, reader, codec and an emitted file even if current serializers compile.
Record fields, types, presence conditions, defaults, dynamic IDs and registry-dependent/raw subtrees. Do not infer a
required read field solely from the writer always emitting it; verify official missing-field behavior too.

Check fixed structures use the intended generated serializers, representation adapters remain at the type/property
boundary and the heterogeneous advancement root uses its map-composite serializer. Test strict typed unknown-field
handling separately from raw NBT/JSON preservation. Route filename, compression policy, fallback and recovery changes
to minecraft-world-io.
