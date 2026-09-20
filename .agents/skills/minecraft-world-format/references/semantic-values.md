# Semantic world values and NBT schemas

## Inspect the official partition

Trace terrain, Block Entities, Entities and POI through their independent saved codecs and runtime owners. Compare
LevelChunk/Section values, pending Block Entity data, entity storage and POI sections. Retain the data needed by caller
computation while leaving official ticking, ownership and lifecycle machinery outside the domain graph.

Start with caller computation before choosing saved/packet layouts. Inspect common official loops and updates:
Section lookup, palette writes, state transitions, height/light scans, inventory splitting and Entity traversal.
Inventory density, ordering, value identity, boxing, copies and amortized resize work throughout reachable built-in
values. Compare arrays and standard maps first; record a concrete hot-path reason before adding a custom container.
Evaluate palette retention across the caller's whole maintenance cycle: normal writes retain history, explicit
compaction precedes a detached background-save copy when the application chooses that workflow. Verify compaction
releases oversized internal collection capacity; encoding compact copies alone do not reclaim the live palette's
history.
Distinguish generic numeric boxing from avoidable wrapper allocation. Consider eager derived values for shared
immutable layouts, weighing retained memory and instance count rather than caching every computed property.

For each mutable owner, identify structural fields, array origins, map-key identities and open properties. Check
constructors retain
supplied references and empty construction creates data without generation. Exercise typed and dynamic access to the
same stored value, nested references, replacement and deletion from the root. Missing data must remain distinguishable
from known empty values wherever the model exposes that distinction. For coupled palette/immutable state internals,
check stable equality/hash keys and snapshot isolation; do not extend that encapsulation to ordinary mutable fields.
Test explicit item copying separately from constructor aliasing, including custom copy callbacks and shared acyclic
subtrees. Use counted lookups/comparisons for complexity regressions instead of wall-clock assertions.

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
- Check compact-copy encoding does not mutate the input, including stable indices before explicit compaction,
  packed-width growth, uniform fills and duplicate equal entries from external palettes. Verify `copy()` retains
  history/IDs and `compactCopy()` removes history; mutate, fill and compact either result to prove storage independence.
  Uniform historical IDs and packed arrays with partial final words need coverage. Keep diagnostics distinct from
  copies.
- Mutate retained primitive arrays and nested list entries, then replace whole containers and encode again. Check
  Section origins after context replacement and distinguish old detached aliases from current root reachability.

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
