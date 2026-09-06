# protocol-configuration

This module projects parsed [world-format](../world-format/README.md) data packs into Configuration values. It owns
`ConfigurationData`/`ResolvedConfigurationData` on the server side and `DataPackConfigurationSnapshot`/
`ClientRegistryView`
on the client side. It contains no physical packet encoding, filesystem access or release-specific defaults.

## Project a stack

`DataPackConfigurationProjector` takes base Configuration data and explicit per-registry projectors. Each
`DataPackRegistryProjector` chooses its resource type and overlay/replacement behavior; its entry callback converts a
resolved resource into the registry's network NBT value. Missing projectors fail instead of assuming disk JSON and
network NBT have identical semantics. Tags, enabled features, registry order and Known Packs remain values; endpoints
own the packet sequence.

Before this call, construct `DataPackStack(dataPack)` from a pack decoded with `DataPackFormat().decode(archive)` as
shown in [world-format](../world-format/README.md#structured-files-and-data-packs). Construct
`DataPackConfigurationProjector(baseConfigurationData, dataPackRegistryProjectors)` from the application's base and
`DataPackRegistryProjector(...)` callbacks, or use `VanillaConfigurationData.dataPackConfigurationProjector()` from the
[vanilla provider](../protocol-configuration-vanilla/README.md). Select the target with
`DataPackFormatVersion(major, minor)`;
`VanillaConfigurationData.dataPackFormatVersion` supplies the repository-selected value.

The plain path constructs the projector as described above and calls it directly:

```kotlin
fun projectPacks(
    dataPackStack: DataPackStack,
    dataPackConfigurationProjector: DataPackConfigurationProjector,
    dataPackFormatVersion: DataPackFormatVersion,
): ResolvedConfigurationData = dataPackConfigurationProjector.project(
    dataPackStack, dataPackFormatVersion,
)
```

The convenience path replaces that call with the following expression, using the same inputs and returning the same
`ResolvedConfigurationData`:

```kotlin
return dataPackStack.toConfigurationData(dataPackConfigurationProjector, dataPackFormatVersion)
```

Constructors are public at every stage. Callers with already projected registries can construct
`ResolvedConfigurationData` directly. Its complete registry context derives from the supplied static schema and packet
order unless explicitly supplied. [protocol-configuration-vanilla](../protocol-configuration-vanilla/README.md) provides
the release-matched base and synchronized-registry projectors.

## Resolve dimensions

Read `settings: WorldGenSettingsData` from
`minecraftWorldAccess.data.read<SavedDataFile<WorldGenSettingsData>>(SavedDataId("world_gen_settings"))?.data`, with a
world opened through [world-io](../world-io/README.md#quick-start). Alternatively construct `WorldGenSettingsData(...)`
from application-owned dimension definitions. Domain and network facts have separate results:

| Operation                                                                                | Result and contract                                                                                                                 |
|------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `ConfigurationData.resolveWorldChunkContexts(settings, defaultBlockState, defaultBiome)` | `WorldChunkContexts` with raw-ID-free `ChunkContext` values; supports referenced and inline dimension types                         |
| `ConfigurationData.resolveMinecraftDimensions(settings)`                                 | Dimension-ID map of `MinecraftDimensionContext`; requires synchronized dimension-type IDs for Play Login and rejects inline holders |

Both operations collect dimension-resolution failures and return no partial result. Referenced layouts come from the
complete Configuration registry. The generic
`WorldGenSettingsData.resolveWorldChunkContexts` in world-format instead accepts an explicit referenced-layout provider.

`MinecraftDimensionLayout` combines a synchronized type ID/raw ID with `DimensionTypeLayout`.
`MinecraftDimensionContext` combines that layout, the dimension ID and `PacketCodecContext`. Its
`chunkContext(defaultBlockState, defaultBiome)` method constructs a domain context from explicit defaults. It does not
create a codec or supply omitted domain fields. `PacketCodecContext` contains registry mappings, not Section count.

## Resolve received Configuration

`DataPackConfigurationSnapshot` captures offered Known Packs, feature flags, synchronized registry packets and tags.
Construct it from the corresponding packets collected during a custom Configuration flow, or take
`minecraftClientNegotiationResult.dataPackConfigurationSnapshot` after client `negotiate()`.
`configurationData` is the base produced by `projectPacks` above, or `VanillaConfigurationData` for the matching
defaults.
The snapshot does not reconstruct recipes, functions, loot tables or other server-only resources.

```kotlin
fun resolveClientData(
    dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    configurationData: ConfigurationData,
): ClientRegistryView = dataPackConfigurationSnapshot.resolveClientRegistryView(
    configurationData = configurationData,
)
```

The base supplies static registry mappings; received registry order defines synchronized raw IDs. The view retains the
received NBT as sent, including omitted values under Known Packs; this helper does not reconstruct those values.
Dimension-layout resolution separately consults complete Configuration data when its layout NBT was omitted.

For loader overrides, optionally pass a `StaticRegistrySchema(...)` describing local blocks and a
`RemoteRegistrySnapshot(...)` containing remote IDs, aliases, overrides and blocked entries. Missing local block
schemas fail with `MissingStaticBlockSchemas`. An overload accepts an already resolved `PacketCodecContext`, such as
`minecraftClientNegotiationResult.minecraftDimensionContext.packetCodecContext`. The high-level result's no-argument
`resolveClientRegistryView()` already uses that retained context.

`PacketCodecContext.withSynchronizedRegistries(packets)` overlays the raw-ID mappings defined by each registry packet's
entry order while retaining unrelated registries, block states and size overrides. Unchanged mappings reuse the same
context; duplicate registry packets are rejected. The server, client view and vanilla provider use this shared mapping.

Construct world NBT codecs in [world-format](../world-format/README.md), or packet codecs in
[protocol-world](../protocol-world/README.md), using their complete directional contexts. Bind these codecs once per
stable dimension/connection epoch; Configuration objects do not own conversion or world lifecycle.
