# protocol-vanilla-data

Vanilla protocol data needed to communicate with the matching Minecraft Java Edition client: typed block-state and
entity-type catalogues, Configuration registry snapshots, dimension layouts, feature flags, Known Packs, tags, and a
loader-neutral `StaticRegistrySchema`/`ProtocolRegistryContext`. The generated data matches the project-selected
Minecraft release. The module does not implement block behavior, entity AI, world generation, or a general Datapack
loader.

`VanillaProtocolData.Default` selects compact registry entries when a client accepts the offered Known Packs and
complete network NBT otherwise:

```kotlin
val blocks = VanillaStaticData.requireRegistry(Identifier("block"))
val stoneProtocolId = blocks.requireProtocolId(Identifier("stone"))

val compactRegistries = VanillaProtocolData.registryPackets(
    VanillaProtocolData.knownPacks,
)
val completeRegistries = VanillaProtocolData.registryPackets(emptyList())
```

A modded caller supplies a schema containing every locally known mod block and its ordered states, then resolves the
loader-provided remote raw-ID snapshot:

```kotlin
val connectionContext = moddedStaticSchema.resolve(remoteRegistrySnapshot)
```

Resolution follows remote registry order when assigning global block-state IDs. The resulting immutable context supplies
block-state/biome palette sizes and lookup helpers to serialization and the server's initial-world APIs.
