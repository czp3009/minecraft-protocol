---
name: minecraft-nbt
description: "Implement or audit NBT/SNBT values and formats in nbt and nbt-serialization: tags, lists, arrays, root modes, modified UTF, Kotlin serialization, malformed input or official NbtIo oracle behavior. Do not use for Anvil framing or world filesystem/compression policy."
---

# Minecraft NBT

Confirm the selected release and read [format-workflow.md](references/format-workflow.md). Trace official tag classes,
NbtIo roots, readers/writers and callers; obtain matching client bytecode through the declared Gradle producer when
client/server usage differs.

Identify whether the change belongs to logical tag/document values and serializer handoff in nbt, or physical binary
and SNBT mapping in nbt-serialization. Read that module's AGENTS for invariants. Test the actual root mode and logical
list semantics instead of assuming older NBT documentation still applies.

Run `:nbt:jvmTest :nbt-serialization:jvmTest`; the latter includes the official oracle. Changed network wrappers require
protocol-serialization tests, while world composition requires the affected world-format/world-io suites. Report the
root/tag methods inspected, behavior changes, oracle cases and platform-specific gaps.
