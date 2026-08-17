---
name: minecraft-nbt
description: Implement, update, test, or audit handwritten Minecraft NBT behavior in nbt and nbt-serialization for the selected release. Use for the NBT value algebra, tag IDs and payloads, compound/list/array semantics, heterogeneous logical lists, NbtDocument and root modes, modified UTF, binary NBT Source/Sink streams, the NBT kotlinx.serialization format, recursion or allocation limits, malformed input, network-NBT dependencies, or official NbtIo and NBT-oracle compatibility. Do not use for Anvil region framing or filesystem compression policy.
---

# Minecraft NBT

Keep the logical tag algebra separate from physical binary streams and from files that happen to contain compressed NBT.

## Establish the selected behavior

1. Confirm the selected release with `./gradlew -q minecraftVersion`.
2. Read [references/format-workflow.md](references/format-workflow.md).
3. Inspect the matching official NBT tag classes, `NbtIo` entry points, readers/writers, accounting, and actual call
   sites. Inspect both official artifacts when client and server usage can differ; obtain the client JAR through
   `./gradlew downloadMinecraftClientJar` rather than a manual download.

Use the root evidence order for facts the official implementation does not expose. Do not preserve historical list,
root, or UTF behavior merely because older NBT documentation describes it.

## Implement at the owning boundary

Keep tag values, documents, and the logical raw-tag serializer handoff in `nbt`. Keep binary tag IDs, root forms,
modified UTF, format-intrinsic lengths, and the `kotlinx.serialization` format in `nbt-serialization`. Use
`kotlinx.io.Source` and `Sink`; preserve caller ownership.

Do not put GZIP/ZLIB file policy, region compression identifiers, paths, or file handles in either module. Load the
world-format or world-io skill when those wrappers change.

Reject malformed types, negative lengths, and truncated input, but do not add policy-sized byte, collection, array, or
nesting ceilings. Preserve equivalent public result and exception semantics across supported targets.

## Verify and report

Run:

```shell
./gradlew :nbt:jvmTest :nbt-serialization:jvmTest
```

The binary suite includes the matching official NBT oracle. Also run `:protocol-serialization:jvmTest` when network NBT
changes, `:world-format:jvmTest` when chunk/region composition changes, and `:world-io:jvmTest` when standalone files
are affected.

Report the official root/tag methods inspected, logical or binary changes, limit decisions, oracle coverage, downstream
suites, and any target-specific capability gap.
