# protocol-symbol-processor

This unpublished JVM module holds the KSP processor that turns [`protocol-model`](../protocol-model/README.md) source
annotations into that module's runtime dispatch tables. Nothing here runs standalone: `protocol-model` applies the
processor during compilation, and the generated Kotlin appears in that module's build directory.

## Inputs

- `@PacketInfo` on a packet class records its connection state, direction, packet ID, and official packet name.
- `@DataComponentInfo` on a component model records the `DataComponentType` it represents.
- The `minecraft.packetClasses` KSP option names the official class/member artifact that root packet analysis produced
  for
  the repository-selected Minecraft release.

## Validation

Generation is refused with KSP errors that point at the offending declarations when packet keys collide, when the
annotated models and the official report do not cover each other exactly, when `officialName` disagrees with the report,
when official class nesting or ordered field names disagree without a documented exception, or when data-component
annotations are duplicated, missing, or name an unknown type. Repeated `@PacketInfo` annotations allow an official
shared class in several states; a registration may select its own serializer when official state codecs differ.
The legacy server-list ping (serverbound handshake `0xFE`) is the only packet without a report entry; it is generated
with legacy unframed framing.

These checks validate declaration coverage, names and order. They do not prove nested types, nullability, conditional
payload semantics or physical bytes; those require source review and codec tests against both official peers.

## Generated output

Two portable objects are written below the consuming module's build directory when KSP inputs change; neither is
committed:

- `GeneratedPacketDefinitions.entries` lists every packet as a `PacketDefinition` in protocol order.
- `GeneratedDataComponentSerializers` maps `DataComponent` values and types to each other and to their serializers.
