---
name: minecraft-world-io
description: Implement, update, test, or audit handwritten selected-release Minecraft standalone world-file schemas and world filesystem behavior. Use for provided kotlinx.serialization models and serializers for level.dat, advancements, or statistics; Okio Path/FileSystem/FileHandle APIs; dimension-to-directory mapping; playerdata; saved data; region/entities/POI directories; external chunk sidecars; standalone NBT compression; atomic or backup replacement; durable writes; session.lock; live read-only access; region-store lifecycle; or official world generate/rewrite/reload interoperability.
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

## Implement filesystem policy

Use Okio `Path`, `FileSystem`, and `FileHandle`. Keep format parsing in lower modules and keep host-only primitives
behind the smallest platform boundary. Preserve ownership of caller-provided filesystems and handles.

Match official file compression, backup, replacement, durable-write, recovery, lock, external-sidecar, and directory
lifecycle semantics. Do not add policy-sized read, write, decompression, tree-depth, or allocation ceilings; preserve
only format-intrinsic bounds and exact framing lengths. Never expose Fixture Host paths or process machinery as
production APIs.

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
