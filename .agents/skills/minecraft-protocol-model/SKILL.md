---
name: minecraft-protocol-model
description: Implement, update, test, or audit handwritten Kotlin Multiplatform models for the selected Minecraft Java Edition protocol. Use for packet payload data classes, shared protocol values, logical sealed variants and serializers, data components, command parsers, particles, entity metadata, protocol-facing registries, wire annotations, @PacketInfo metadata, KSP packet validation and dispatch, official field names and order, or protocol nullability.
---

# Minecraft protocol model

Model the selected official protocol without buffers, sockets, or filesystem behavior. Include
`protocol-symbol-processor` only when KSP annotation handling, report validation, diagnostics, or generated handoff
shape changes.

## Select the detailed workflow

- Read [references/packet-models.md](references/packet-models.md) for packets, shared record-like values, field naming,
  optionals, or nullability.
- Also read [references/discriminated-models.md](references/discriminated-models.md) for data components, command
  parsers, particles, entity metadata, recipes, or another ID-selected sealed family.

Confirm the selected release with `./gradlew -q minecraftVersion` before inspecting release-specific evidence. When
client bytecode is required, obtain the matching artifact through `./gradlew downloadMinecraftClientJar`, never a manual
download.

## Respect handwritten and generated ownership

Handwrite packet and shared-value declarations, logical serializers, wire metadata, invariants, `@PacketInfo`,
`DataComponentType`, and `@DataComponentInfo` declarations in `src`.

Do not edit:

- `MinecraftProtocol.kt`, produced by `generateMinecraftProtocolSource`;
- `GeneratedPacketDefinitions.kt`, produced by KSP from `@PacketInfo` declarations;
- `GeneratedDataComponentSerializers.kt`, produced by KSP from `@DataComponentInfo` declarations;
- official analysis JSON below `build/generated`.

KSP validates packet identity coverage and local data-component dispatch completeness; it does not validate field names,
order, types, nullability, or codec semantics. Establish those manually from official evidence.

`protocol-symbol-processor/src` is handwritten development infrastructure. Modify it only when the source-derived
annotation contract, official-report validation, diagnostics, or generated handoff structure changes; keep deterministic
non-source analysis in Gradle and never patch its generated Kotlin outputs.

## Implement the model

1. Identify the exact official type and inspect both the producing and consuming side when available.
2. Derive logical components, exact official field names, declaration order, types, absence representation, and
   invariants.
3. Derive `@PacketInfo` state, direction, ID, and namespace-free official name from the matching Gradle-produced packet
   report.
4. Express buffer-independent variants with Kotlin types and logical `KSerializer` implementations. Add only descriptive
   wire metadata to models; implement byte interpretation in `protocol-serialization`.
5. Add focused model tests for constructors, variants, defaults, and invalid logical states.

Use the official name as the Kotlin property name, including backticks for a Kotlin keyword. Do not conceal a renamed
property behind `@SerialName`. If no stable official logical name can be established, report the evidence conflict
rather than inventing a confident name.

## Verify and report

Run `./gradlew :protocol-model:jvmTest`. For every wire-visible model change, also run
`./gradlew :protocol-serialization:jvmTest`; load the serialization skill only when physical encoding code or wire
semantics must change. Run downstream client/server tests when orchestration-visible packets changed.

Report official client/server disagreements, non-obvious type or name mappings, secondary-source disagreements, and
every unresolved nullable property carrying `@UnknownNullability`.
