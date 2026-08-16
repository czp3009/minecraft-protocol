# protocol-vanilla-data

Versioned vanilla data needed to communicate with the matching Minecraft Java Edition client.

The module exposes typed static block-state and entity-type catalogues, Configuration registry snapshots, dimension
layouts, feature flags, Known Packs, tags, and a loader-neutral `StaticRegistrySchema`/`ProtocolRegistryContext`.
Version-matched generated data is bundled with the module. It does not implement block behavior, entity AI, world
generation, or a general Datapack loader.

`VanillaProtocolData.Default` selects compact registry entries when a client accepts the offered Known Packs and
complete network NBT otherwise:

```kotlin
val blocks = VanillaStaticData.requireRegistry(Identifier("block"))
val stoneProtocolId = blocks.requireProtocolId(Identifier("stone"))

val compactRegistries = VanillaProtocolData.registryPackets(
    VanillaProtocolData.knownPacks,
)
val completeRegistries = VanillaProtocolData.completeRegistryPackets()
```

`VanillaStaticData.registrySchema` is the local vanilla schema and `VanillaProtocolData.registryContext` is its resolved
default context. A modded caller supplies a schema containing every locally known mod block and its ordered states, then
resolves the loader-provided remote raw-ID snapshot:

```kotlin
val connectionContext = moddedStaticSchema.resolve(remoteRegistrySnapshot)
```

Resolution follows remote registry order when assigning global block-state IDs and fails if a non-blocked remote block
has no local state schema. The resulting immutable context supplies block-state/biome sizes and lookup helpers to
serialization and server initial-world APIs. Context derivations retain their large registry and block-state collections
by reference.
