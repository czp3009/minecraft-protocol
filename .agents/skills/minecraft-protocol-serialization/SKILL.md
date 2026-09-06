---
name: minecraft-protocol-serialization
description: "Implement or audit physical packet payload encoding in protocol-serialization: primitives, wire annotations, optional/discriminated fields, registries, network NBT, Section palettes, intrinsic bounds and official codec-oracle compatibility. Do not use for framing, encryption, sockets, world domain projection or filesystem I/O."
---

# Minecraft packet serialization

Confirm the selected release and read [codec-workflow.md](references/codec-workflow.md). Trace the exact official
STREAM_CODEC or manual reader/writer through nested codecs, constructors, producer and consumer.

Use existing wire metadata when it expresses the encoding. Route a logical sealed/conditional serializer to
protocol-model; extend the physical format only for byte-level behavior. Use minecraft-nbt for changes to the base NBT
format, and minecraft-world-projection when the defect is semantic conversion rather than bytes.

Test ordinary exact bytes, changed conditional branches, boundary lengths, truncation, trailing data and invalid
IDs/discriminators at the owning format boundary. Shared policy ceilings are not part of the format contract.

Run `:protocol-serialization:jvmTest`, including its official oracle. Add affected model/NBT or endpoint suites only
when those contracts changed. Report the official codec path, byte-level decisions and exercised oracle branches;
a local round trip and a generated minimal sample alone do not establish complete compatibility.
