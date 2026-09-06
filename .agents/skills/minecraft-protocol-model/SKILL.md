---
name: minecraft-protocol-model
description: "Implement or audit protocol-model packet classes, official class/member names, nested records, logical serializers, components, commands, particles, metadata, registries and wire annotations. Also use for PacketInfo/DataComponentInfo KSP contracts; physical bytes belong to minecraft-protocol-serialization."
---

# Minecraft protocol models

Confirm the release with `./gradlew -q minecraftVersion`. Use the declared `downloadMinecraftClientJar` producer when
matching client bytecode is required.

Read [packet-models.md](references/packet-models.md) for identity, official naming, field shape and nullability.
Read [discriminated-models.md](references/discriminated-models.md) for ID-selected component, parser, particle, metadata
or recipe families.

## Implement from evidence

1. Follow official registration and `PacketType` to the exact class; inspect its producer, codec and consumer.
2. Compare the complete Kotlin class name, including direction, enclosing class and suffix, to that official name.
   Follow the root naming rule even when the official class omits `Packet`; inspect existing exceptions instead of
   treating them as precedent.
3. Derive logical field names/order, types, absence and invariants. Populate route metadata from the generated report;
   `officialName` is the resource identity, not the Java class name.
4. Use buffer-independent Kotlin values and logical serializers. Put byte interpretation in protocol-serialization.
   Record justified name and shape differences independently beside the declaration and in PACKET-MODELS.md.
5. Add behavior tests for changed invariants/variants and update caller examples affected by public naming changes.

KSP consumes `officialMinecraftPackets` through `minecraft.packetClasses` to validate coverage, names and ordered
fields. It does not prove nested types, nullability or codec semantics. Change protocol-symbol-processor only when the
annotation contract, diagnostics or source-derived handoff changes; deterministic JAR analysis remains in buildSrc.

## Verify

Run `:protocol-model:jvmTest` and, for wire-visible changes, `:protocol-serialization:jvmTest`. Run affected endpoint
suites when renamed or reshaped packets are used by negotiation. Report the scope of the official comparison,
intentional exceptions and unresolved `@UnknownNullability` facts separately from sampled byte compatibility.
