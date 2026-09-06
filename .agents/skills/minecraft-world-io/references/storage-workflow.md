# World filesystem audit

## Trace a file family

Compare official path constructors with an emitted world. Include level/previous files, player data/stats/advancements,
root and dimension saved data, selected directory/ZIP packs, region/entities/poi directories, sidecars and session.lock.
Record compression/detection, missing-file result, replacement and durability, fallback/promotion and failure cleanup.
Use minecraft-world-format to audit the schema itself.

For recovery, test binary corruption separately from valid bytes rejected by the caller's serializer. Inject promotion
failure after successful previous-level parsing and verify the parsed value still returns. Check the different player
empty-result and corrupt-current-evidence policy. Exercise raw callbacks and typed reads through the same admission.

For packs, compare inspection, borrowed one-file reads, complete archives and parsed/enabled selection. Test directory
and ZIP paths, including Node's selected-entry materialization. Completing official packs or enabling unlisted packs is
not filesystem reader behavior.

## Trace Region operations

Follow coordinates, filename, allocation/header update, compression and external placement through one public operation.
Count physical opens: one overlapping mutable Region state should own one MCA handle; separate one-shot calls need not
reuse an idle file. Test both known compressed-length streaming and the single-final-payload staging path.

Use filesystem gates for these concurrency cases:

- same-key readers overlap, a waiting writer precedes new readers and unrelated keys remain independent;
- close seals admission, waits for active callbacks and releases resources after the final pin;
- failure during commit/flush/close is observed by its owner and wakes waiters;
- a closing barrier sees cleanup it was already waiting for, without replaying failures from older completed calls;
- cancellation during rollback preserves the primary cancellation and attaches cleanup failure.

Test live handles independently: each owns its MCA, a missing-at-open path stays missing for that handle, ordinary calls
reread headers and a callback reuses only its header. Simulate replacement/torn data and finish callbacks before close.
Do not transfer mutable coordination guarantees to this observer.

## Review the public overload matrix

Compare mutable/live world facades and `players`, `data`, `dataPacks`, `dimensions`, `directFiles`, plus each Region
kind.
Check corresponding names, parameter order, nullability and defaults, accounting for suspension, writes and lifetime.

Compile ordinary caller examples for:

- built-in, explicit-strategy and reified player/standalone operations;
- document/JSON-tree and callback stream branches;
- contextual serializer lookup through the actual configured format;
- absolute and region/local coordinates at directory owners, and local/absolute coordinates at Region owners;
- codec-bound terrain/Entity views and per-read POI contexts, plus per-write encoder configuration.

Use portable compilation and behavior assertions, not JVM reflection. Check optional-file nullability and endpoint
ownership at runtime; avoid tests that simply restate every signature.

## Use the existing official scenario

The world-io scenario creates a world, stops the official server before direct access, exercises stores and compression,
rewrites marked data and requires official reload/save/restart. Extend it for new interoperability evidence rather than
creating a launcher or second gate. Inspect buildSrc/Fixture Host diagnostics when failure occurs before storage work.
