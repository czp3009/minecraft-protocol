---
name: minecraft-world-format
description: Implement, update, test, or audit handwritten filesystem-independent Minecraft world formats in world-format for the selected release. Use for data-pack archives/parsing/stacks, standalone world schemas, .mca containers and compression, coordinates, semantic Chunk/Entity Chunk/POI Chunk values and codecs, or region-record NBT composition. Do not use for Okio paths, FileSystem, FileHandle, backup policy, locking, or on-disk store lifecycle.
---

# Minecraft world format

Implement portable world schemas, data-pack values, Anvil containers, and compression streams without filesystem
behavior. Load the NBT skill when the underlying tag or binary format changes.

## Establish the format

1. Confirm the selected release with `./gradlew -q minecraftVersion`.
2. Read [references/anvil-workflow.md](references/anvil-workflow.md) for Anvil or compression work, and
   [references/datapack-workflow.md](references/datapack-workflow.md) for data-pack parsing, selection, or stack work.
3. Inspect the matching official region implementation, compression registry, chunk storage call sites, and emitted
   files. Treat executable official behavior as the final check.

Derive identifiers, flags, sizes, limits, and wrapper formats from the selected release rather than skill prose or older
format documentation.

## Implement the portable boundary

Keep coordinates, headers, sector-backed containers, compression dispatch, external-payload representation, and
composition of caller-supplied NBT documents into region records in `world-format`. Use `kotlinx.io.Source` and `Sink`;
preserve caller ownership and require complete bounded streams.

For semantic Chunk, Entity Chunk, and POI Chunk codecs, treat persisted `DataVersion` as an ordinary retained field. Do
not compare it with the repository-selected release or require an expected version before decoding; compatibility
preflight and migration policy belong to the caller. Continue to validate the structure that the selected-release schema
actually needs, and keep raw `NbtDocument` access available when that structure is not applicable. POI NBT gets its
absolute Chunk position from the enclosing Region entry and must validate every record against it.

Delegate raw GZIP, ZLIB, LZ4, and checksums to maintained multiplatform libraries where available. Handwrite only the
Minecraft-specific container, preprocessing, ownership guard, format-intrinsic framing validation, or compatibility
layer, and document that boundary beside the special logic.

Keep Okio paths, directory creation, random-access file mutation, sidecar placement, locks, and replacement policy in
`world-io`. Do not make a portable format API depend on a host filesystem.

Keep data-pack representation stages explicit: archive bytes, parsed pack, low-to-high stack, resolved resources, and a
detached partial world selection are different values. The partial selection may retain persisted IDs and feature
configuration, but filesystem discovery belongs to `world-io`, bundled-vanilla completion belongs to
`protocol-datapack-vanilla`, and network projection belongs to `protocol-datapack`.

## Verify and report

Run `./gradlew :world-format:jvmTest`. Add valid, boundary, malformed, truncated, overlapping, oversized, and
cross-codec tests at the changed layer. Compression changes also require `:world-format:jsNodeTest`,
`:world-format:wasmJsNodeTest`, and the configured host Native test after the JVM path is stable. When the change
affects bytes persisted by a real world store, also run `./gradlew :world-io:jvmTest`, whose standard suite owns
official generate/rewrite/reload interoperability.

Report the official format paths inspected, compression/container decisions, maintained libraries used, custom-extension
constraints, focused tasks, and official reload result when applicable.
