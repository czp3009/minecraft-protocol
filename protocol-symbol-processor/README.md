# protocol-symbol-processor

This unpublished JVM module holds the KSP processor that turns [`protocol-model`](../protocol-model/README.md) source
annotations into that module's runtime dispatch tables. Nothing here runs standalone: `protocol-model` applies the
processor during compilation, and the generated Kotlin appears in that module's build directory.

## Inputs

- `@PacketInfo` on a packet class records its connection state, direction, packet ID, and official packet name.
- `@DataComponentInfo` on a component model records the `DataComponentType` it represents.
- The `minecraft.packetsReport` KSP option names the official packets report that root official analysis produced for
  the repository-selected Minecraft release.

## Validation

Generation is refused with KSP errors that point at the offending declarations when packet keys collide, when the
annotated models and the official report do not cover each other exactly, when `officialName` disagrees with the report,
or when data-component annotations are duplicated, missing, or name an unknown type. The legacy server-list ping
(serverbound handshake `0xFE`) is the only packet without a report entry; it is generated with legacy unframed framing.

## Generated output

Two portable objects are written through KSP's standard output and regenerated on every build; neither is committed:

- `GeneratedPacketDefinitions.entries` lists every packet as a `PacketDefinition` in protocol order.
- `GeneratedDataComponentSerializers` maps `DataComponent` values and types to each other and to their serializers.
