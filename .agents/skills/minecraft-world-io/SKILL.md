---
name: minecraft-world-io
description: Implement, update, test, or audit handwritten selected-release Minecraft standalone world-file schemas and world filesystem behavior. Use for provided serializers for level.dat, player data, dimension saved data, advancements, or statistics; world data-pack directory/ZIP reads; Okio filesystem APIs; dimension mapping; region/entities/POI directories; sidecars; NBT compression; replacement and recovery; session.lock; live reads; region lifecycle; or official world generate/rewrite/reload interoperability.
---

# Minecraft world I/O

Implement real filesystem behavior over the portable NBT and Anvil layers. Do not duplicate their binary formats in
file-store code.

## Establish official disk behavior

1. Confirm the selected release with `./gradlew -q minecraftVersion`.
2. Read [references/storage-workflow.md](references/storage-workflow.md).
3. Read `../minecraft-nbt/SKILL.md` when standalone NBT semantics change and `../minecraft-world-format/SKILL.md` when
   region bytes or compression change.
4. Inspect the matching official path/resource keys, level storage, player/saved-data stores, region storage, directory
   lock, and actual generated world.

Historical compatibility paths are valid only when intentionally supported and tested. Do not infer current official
placement from Wiki prose or an older world tree.

For every project-provided `kotlinx.serialization` model of a standalone world file, inspect the matching official
writer, reader, codec, and generated file. Treat the model, nested declarations, custom serializers, field names and
types, nullability, defaults, and dynamic/raw subtree boundaries as selected-release handwritten contracts. If that
release always writes a field, model it as required and non-null without an old-version missing-field default. Update
the models, serializers, tests, and user documentation with the selected release; do not retain old schema branches
unless historical compatibility is explicitly in scope.

The current model audit is never implicit: review `LevelDat` and every nested declaration, `PlayerData`,
`SavedDataFile<T>` plus every provided dimension saved-data payload, `PlayerAdvancements` and its heterogeneous root-map
serializer, and `PlayerStatistics` individually. Confirm that dynamic player subtrees remain raw only where the official
codec is registry-dependent, that the advancement serializer still uses composite map events rather than
`JsonElement`, and that each typed file path connects the stream directly to the caller-selected serializer without a
byte-array, string, NBT-tree, or JSON-tree intermediary.

Region encoding is the narrow exception to retaining no complete byte payload: Anvil record allocation needs the exact
compressed length before commit, so a typed or raw-NBT Region write may retain its one final compressed result. Stream
the uncompressed NBT directly into that compression target, and use the known-length sink overload without staging when
the caller already has `compressedByteCount`.

## Implement filesystem policy

Use Okio `Path`, `FileSystem`, and `FileHandle`. Keep format parsing in lower modules and keep host-only primitives
behind the smallest platform boundary. Preserve ownership of caller-provided filesystems and handles.

Do not rely on the JVM's shared `IOException` actual type to prove the public Okio boundary. A terminal lower-format
parser or serializer returns a value rather than a stream that can be adapted back, so route any kotlinx-io I/O failure
leaving that call through the official reverse adapter. Test this on a non-JVM target; never hand-copy stream bytes or
instantiate a replacement exception in `world-io`.

Match official file compression, backup, replacement, durable-write, recovery, lock, external-sidecar, and directory
lifecycle semantics. Do not add policy-sized read, write, decompression, tree-depth, or allocation ceilings; preserve
only format-intrinsic bounds and exact framing lengths. Never expose Fixture Host paths or process machinery as
production APIs.

Match recovery continuation as well as successful file placement. In particular, after the official level loader has
successfully parsed `level.dat_old`, a failed best-effort restoration does not replace that value with an I/O failure.
Keep the primary/previous NBT streaming, synced-temporary, and backup replacement mechanism shared between level and
player storage; keep promotion, corrupt-evidence, fallback, and empty-result decisions in the file-family policy. Treat
only filesystem, compression, and intrinsic `NbtBinaryFormatException` failures as an unusable candidate. A valid NBT
document rejected by the selected serializer is a schema/program failure and must not trigger fallback or mutation.

Keep live Region ownership local to the caller-owned handle: one handle may retain its own `.mca` resource for repeated
reads, but live world access must not introduce a shared cache, reference count, lock, or mutation coordinator. Treat a
callback-cached Region header strictly as an optimization; external mutation can still produce stale or torn
header/payload combinations and their read failures.

For mutable coordinated access, retain one physical `.mca` handle per active logical Region and close it only after the
last overlapping operation or caller-owned handle releases that state. Do not impose a world-wide write mutex: distinct
metadata keys and distinct Chunk, Entity, or POI directory/position keys must progress independently. Prove both rules
with explicit filesystem gates and physical open/close counts rather than coroutine scheduling assumptions.

Keep mutable and live high-level read APIs shape-compatible apart from suspend execution and lifecycle. POI handles own
their selected-release semantic codec because it needs no caller registry or layout; do not require a meaningless codec
argument. Strong player and named dimension saved-data methods delegate once to their generic serializer/stream path so
they reuse the same logical key, admission, physical open, and replacement policy.

Treat world data packs as immutable read inputs in both facades. The no-argument high-level operation reads the
`level.dat` selection through its existing coordinated recovery path, then loads only selected `file/...` directory or
ZIP members without adding a second logical admission. Return a detached world-format result with strong `DataPackId`
values and the persisted feature configuration. Bundled core/built-in completion and protocol projection belong above
`world-io`; repository discovery and unlisted-pack auto-enablement are not implicit read behavior.

## Verify and report

Run:

```shell
./gradlew :world-io:jvmTest
```

This standard task includes focused FakeFileSystem coverage and the matching official world generate/rewrite/reload
scenario on the supported host-filesystem path. Use `:nbt-serialization:jvmTest` and `:world-format:jvmTest` first when
their contracts changed. After JVM stability, run applicable Node or desktop Native standard tasks under the repository
capability matrix.

Report path and replacement decisions, compression choices, official classes and generated paths inspected, focused
tasks, model and serializer schema decisions when applicable, official reload result, unsupported platform capabilities,
and any intentionally retained historical behavior.
