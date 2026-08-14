---
name: minecraft-world-format
description: Implement, update, test, or audit handwritten filesystem-independent Minecraft Anvil storage formats in world-format for the selected release. Use for .mca region headers, coordinates, sector allocation, chunk records, timestamps, external-chunk markers, compression IDs and dispatch, GZIP/ZLIB/uncompressed/LZ4/custom region streams, region limits, malformed containers, or region-record NBT composition. Do not use for Okio paths, FileSystem, FileHandle, backup policy, locking, or on-disk store lifecycle.
---

# Minecraft world format

Implement portable Anvil containers and compression streams without filesystem behavior. Load the NBT skill when the
underlying tag or binary format changes.

## Establish the format

1. Confirm the selected release with `./gradlew -q minecraftVersion`.
2. Read [references/anvil-workflow.md](references/anvil-workflow.md).
3. Inspect the matching official region implementation, compression registry, chunk storage call sites, and emitted
   files. Treat executable official behavior as the final check.

Derive identifiers, flags, sizes, limits, and wrapper formats from the selected release rather than skill prose or older
format documentation.

## Implement the portable boundary

Keep coordinates, headers, sector-backed containers, compression dispatch, external-payload representation, and
composition of caller-supplied NBT documents into region records in `world-format`. Use `kotlinx.io.Source` and `Sink`;
preserve caller ownership and require complete bounded streams.

Delegate raw GZIP, ZLIB, LZ4, and checksums to maintained multiplatform libraries where available. Handwrite only the
Minecraft-specific container, preprocessing, ownership guard, limit enforcement, or compatibility layer, and document
that boundary beside the special logic.

Keep Okio paths, directory creation, random-access file mutation, sidecar placement, locks, and replacement policy in
`world-io`. Do not make a portable format API depend on a host filesystem.

## Verify and report

Run `./gradlew :world-format:jvmTest`. Add valid, boundary, malformed, truncated, overlapping, oversized, and
cross-codec tests at the changed layer. Compression changes also require `:world-format:jsNodeTest`,
`:world-format:wasmJsNodeTest`, and the configured host Native test after the JVM path is stable. When the change
affects bytes persisted by a real world store, also run `./gradlew :world-io:jvmTest`, whose standard suite owns
official generate/rewrite/reload interoperability.

Report the official format paths inspected, compression/container decisions, maintained libraries used, custom-extension
limits, focused tasks, and official reload result when applicable.
