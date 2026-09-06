# protocol-configuration-vanilla

This module supplies generated, release-matched defaults for
[protocol-configuration](../protocol-configuration/README.md): static registries, block-state schemas, both Known Packs
branches, synchronized registries, feature flags, tags and default registry projectors. Values are available on every
configured target without runtime filesystem access.

## Use Configuration defaults

| Need                                      | Entry point                                              |
|-------------------------------------------|----------------------------------------------------------|
| Static registry and block schemas         | `VanillaRegistryData`                                    |
| Server Configuration defaults             | `VanillaConfigurationData`                               |
| Complete default packet registry mappings | `VanillaConfigurationData.completePacketCodecContext`    |
| Matching client capture                   | `VanillaConfigurationData.dataPackConfigurationSnapshot` |
| Resolved client lookup                    | `VanillaConfigurationData.clientRegistryView`            |
| Default synchronized-registry projectors  | `VanillaConfigurationData.dataPackRegistryProjectors`    |
| Stack projection                          | `dataPackStack.toVanillaConfigurationData()`             |

The high-level client and server already use these defaults. `VanillaConfigurationData` selects the compact registry
branch only when accepted Known Packs exactly match the official offer. It does not supply missing Chunk or Entity
fields for a domain decoder.

## Contexts for a vanilla overworld

For a standard vanilla overworld, a standalone application can obtain the inputs to domain and packet codecs without
copying dimension heights or registry IDs. The resulting `chunkContext` and `packetCodecContext` are construction
inputs to the [NBT](../world-format/README.md#decode-and-encode-nbt) and
[packet codecs](../protocol-world/README.md#chunk-packet-conversion), respectively:

```kotlin
val minecraftDimensionContext = MinecraftDimensionContext(
    dimensionId = DimensionId.Overworld,
    minecraftDimensionLayout = MinecraftDimensionLayout.from(
        VanillaConfigurationData,
        Identifier("minecraft:overworld"),
    ),
    packetCodecContext = VanillaConfigurationData.completePacketCodecContext,
)
val chunkContext = minecraftDimensionContext.chunkContext(
    defaultBlockState = BlockState(BlockId("minecraft:air")),
    defaultBiome = BiomeId("minecraft:plains"),
)
val packetCodecContext = minecraftDimensionContext.packetCodecContext
```

`MinecraftDimensionContext` and `MinecraftDimensionLayout` come from `protocol-configuration`; `Identifier` comes from
`protocol-model`; domain types come from `world-format`. The default cells above are explicit application choices.
For an actual world's dimensions or a connection with data-pack/loader overrides, use
[the resolved world or connection data](../protocol-configuration/README.md#resolve-dimensions) instead of assuming this
vanilla snapshot. These contexts feed the [NBT codecs](../world-format/README.md#decode-and-encode-nbt) and
[packet codecs](../protocol-world/README.md#chunk-packet-conversion) independently.

## Add world packs

Actual official archives, parsed packs and world-selection completion belong to
[datapack-vanilla](../datapack-vanilla/README.md). Load file packs through world-io and complete their selection before
projection. Inside the lifetime of a `minecraftWorldAccess` opened with `MinecraftWorldAccess.open(worldPath)`, prepare
the shared inputs:

```kotlin
val worldDataPackLoadResult = minecraftWorldAccess.dataPacks.readEnabled()
val dataPackStack = worldDataPackLoadResult.toVanillaDataPackStack()
val enabledFeatureFlags = worldDataPackLoadResult.enabledFeatureFlags.mapTo(linkedSetOf(), Identifier::parse)
```

The plain path constructs the generic projector with explicit vanilla base, projectors, core-pack identity and world
flags:

```kotlin
val dataPackConfigurationProjector = DataPackConfigurationProjector(
    baseConfigurationData = VanillaConfigurationData,
    dataPackRegistryProjectors = VanillaConfigurationData.dataPackRegistryProjectors,
    preprojectedDataPackIds = setOf(DataPackId("vanilla")),
    enabledFeatureFlags = enabledFeatureFlags,
)
val resolvedConfigurationData = dataPackConfigurationProjector.project(
    dataPackStack, VanillaConfigurationData.dataPackFormatVersion,
)
```

The convenience path produces the same result from those inputs:

```kotlin
val resolvedConfigurationData = dataPackStack.toVanillaConfigurationData(enabledFeatureFlags = enabledFeatureFlags)
```

To reuse one projector for several stacks, construct it through
`VanillaConfigurationData.dataPackConfigurationProjector(enabledFeatureFlags = enabledFeatureFlags)` and call its
`project(...)` methods as on the plain path. Both conveniences accept `dataPackRegistryProjectorOverrides`:
`DataPackRegistryProjector(...)` values containing application resource-to-NBT callbacks. The default empty list uses
all matching official projectors. Neither vanilla provider depends on the other at runtime.

The application's world selection supplies `enabledFeatureFlags`; the no-argument convenience uses generated vanilla
flags. Core vanilla resources are already represented by the generated base. Default projectors cover every
synchronized vanilla registry. A matching override replaces a registry projector; a new ID extends the set. Construct
`DataPackConfigurationProjector` directly to replace the base or all projection policy.

Dimension resolution is a separate step on the resulting `ConfigurationData`. See
[protocol-configuration](../protocol-configuration/README.md#resolve-dimensions) for the domain-context and
server-negotiable dimension paths. This provider never opens Region files or constructs hidden NBT/packet codecs.

Generated defaults are shipped in the module's artifacts; applications need no official JAR or generator at runtime.
[buildSrc](../buildSrc/README.md) documents their preparation for repository contributors.
