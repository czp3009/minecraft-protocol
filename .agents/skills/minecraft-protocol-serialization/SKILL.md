---
name: minecraft-protocol-serialization
description: Implement, update, test, or audit the physical Minecraft Java Edition packet encoding in protocol-serialization for the selected release. Use for MinecraftProtocolFormat, Source/Sink encoding, VarInt and other primitives, wire-annotation interpretation, strings and collections, optionals and sentinels, discriminated payloads, registry-aware values, network NBT, chunk palettes, packet limits, malformed input, or official STREAM_CODEC and codec-oracle compatibility. Do not use for framing, compression envelopes, stream encryption, sockets, or filesystem I/O.
---

# Minecraft protocol serialization

Implement physical packet bytes through the repository's `kotlinx.serialization` format. Keep payload models buffer-free
and keep framing, compression, encryption, and sockets in `protocol-transport`.

## Establish the contract

1. Confirm the selected release with `./gradlew -q minecraftVersion`.
2. Read [references/codec-workflow.md](references/codec-workflow.md).
3. Read `../minecraft-protocol-model/SKILL.md` when logical declarations or wire metadata change. Read
   `../minecraft-nbt/SKILL.md` only when the underlying NBT format changes rather than its packet wrapper.

Inspect the exact official `STREAM_CODEC` or manual read/write implementation, constructors, validations, and the
direction-specific producer and consumer. Obtain matching client bytecode through `./gradlew downloadMinecraftClientJar`
when required; never replace that producer with a manual download. Use secondary sources only under the root evidence
policy.

## Implement at the owning boundary

Prefer an existing reusable wire annotation and format path when it exactly expresses the official encoding. Introduce a
new annotation only for reusable declarative wire semantics. Keep a buffer-independent discriminator or conditional
logical serializer in `protocol-model`; keep primitive bytes, lengths, registry context, and stream access here.

Use `kotlinx.io.Source` and `Sink` as the canonical physical API. Preserve caller ownership, consume exactly the
declared payload, reject malformed or allocation-amplifying input at the narrowest boundary, and retain equivalent
exception semantics across platforms.

Do not edit KSP-generated packet registry source. The runtime registry consumes handwritten serializers and generated
dispatch, but it does not prove their wire correctness.

## Verify and report

Add focused tests for exact bytes, every changed branch, boundaries, truncation, invalid discriminators, trailing data,
and configured limits. Run:

```shell
./gradlew :protocol-serialization:jvmTest
```

This standard suite includes the matching official codec oracle. Also run `:protocol-model:jvmTest` when models changed,
`:nbt-serialization:jvmTest` when base NBT changed, and the affected client/server JVM suite when connection behavior
depends on the codec.

Report the official codec or read/write path used, non-obvious wire choices, oracle branch coverage added, and
unresolved official/secondary-source disagreements.
