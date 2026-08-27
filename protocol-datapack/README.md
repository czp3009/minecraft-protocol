# protocol-datapack

`protocol-datapack` is the vanilla-neutral bridge between filesystem-independent data-pack values and Minecraft
Configuration data. It owns `ProtocolData`/`ResolvedProtocolData` for the server side and
`DataPackConfigurationSnapshot`/`ClientRegistryView` for the client side. It also adapts active Configuration-derived
dimension and registry facts to the semantic Chunk contracts in `world-format`.

Its projector, resolved server data, received snapshot, and client view are all caller-constructible. This module
contains no bundled vanilla values and performs no filesystem or socket I/O.

The cross-module path keeps each representation explicit:

```text
disk directory/ZIP --world-io--> DataPack --world-format--> DataPackStack -> ResolvedDataPackStack
    --protocol-datapack--> ResolvedProtocolData --protocol-server--> Configuration packets

optional raw snapshot: DataPackArchive -> DataPackFormat -> DataPack

received Configuration values -> DataPackConfigurationSnapshot -> ClientRegistryView

active dimension/registry context -> ChunkLayout + ChunkDataRegistries
```

## Inputs from data packs

The filesystem-independent archive, parser, resource, overlay, filter, and stack types belong to
[`world-format`](../world-format/README.md#structured-files-and-data-packs). Directory and ZIP loading belongs to
[`world-io`](../world-io/README.md#read-world-data-packs). This module accepts those parsed stacks but does not redefine
their parsing or filesystem policy.

## Build server Configuration data

`ProtocolData` is the final server/client negotiation boundary. Its properties are domain values rather than prebuilt
Feature Flags or Update Tags packets. Construct `ResolvedProtocolData` directly when a loader or application already
owns exact Known Packs, synchronized registries, registry tags, static block schemas, and feature flags.

To derive it from resources, supply an explicit base and conversion policy. Here `dataPackStack` is a caller-prepared
`DataPackStack` from `world-format`; `dataPackFormatVersion`, `applicationProtocolDefaults`,
`applicationRegistryProjectors`, and `applicationPreprojectedPackIds` are values explicitly selected by the application
or loader:

```kotlin
val dataPackProtocolProjector = DataPackProtocolProjector(
    baseProtocolData = applicationProtocolDefaults,
    dataPackRegistryProjectors = applicationRegistryProjectors,
    preprojectedDataPackIds = applicationPreprojectedPackIds,
)
val resolvedProtocolData = dataPackStack.toProtocolData(dataPackProtocolProjector, dataPackFormatVersion)
```

Disk JSON and synchronized network NBT are different codecs. A changed registry therefore requires a
`DataPackRegistryProjector`; unresolved registry types are reported together through
`MissingDataPackRegistryProjectorsException`. A projector failure becomes `DataPackRegistryProjectionException`, which
retains the registry ID, registry-entry ID, source data-pack ID, source file path, and original cause. Filters,
overlays, enabled feature flags, and tags are projected generically.

That explicit policy is the vanilla-neutral contract of this module. For generated official defaults—including
release-matched projectors for every synchronized vanilla registry—and the zero-argument `toVanillaProtocolData()`
path, add [`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md). Mod projectors can override or extend
those defaults, while constructing `DataPackProtocolProjector` directly remains the full replacement escape hatch.

## Resolve client Configuration data

The wire transmits Known Packs, feature flags, synchronized registry entries, and tags. It does not transmit recipes,
loot tables, functions, advancements, or other server-only resources, so the client result deliberately is not a
`DataPack`.

The four `received...` values below are retained from the corresponding Configuration packets. The matching protocol
defaults and local schemas come from the client implementation, while `loaderRegistrySnapshot` is supplied by its mod
loader, or is `RemoteRegistrySnapshot.Empty` on an unmodded client:

```kotlin
val dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
    offeredKnownPacks = receivedOfferedKnownPacks,
    enabledFeatureFlags = receivedFeatureFlags,
    synchronizedRegistryPackets = receivedRegistryPackets,
    registryTags = receivedRegistryTags,
)
val clientRegistryView = dataPackConfigurationSnapshot.resolveClientRegistryView(
    protocolData = matchingProtocolDefaults,
    staticRegistrySchema = localVanillaAndModSchemas,
    remoteRegistrySnapshot = loaderRegistrySnapshot,
)
```

An application may instead pass an already resolved `ProtocolRegistryContext`. `RemoteRegistrySnapshot` supplies loader
raw IDs, aliases, overrides, and blocked entries. `StaticRegistrySchema` supplies the ordered states of every locally
implemented block. Missing block schemas fail with `MissingStaticBlockSchemas` rather than silently producing incorrect
global block-state IDs.

## Adapt protocol context to semantic Chunks

`MinecraftDimensionLayout.toChunkLayout()` converts the bounds of one Configuration-resolved dimension without assuming
a release-global default. `ProtocolRegistryContext.toChunkDataRegistries()` resolves persisted block descriptors and
biome names against the active block-state and synchronized biome registries.

The inputs in this example are explicit parameters supplied by a completed client or server negotiation:

```kotlin
fun createProtocolChunkNbtContext(
    minecraftDimensionLayout: MinecraftDimensionLayout,
    protocolRegistryContext: ProtocolRegistryContext,
    expectedDataVersion: Int,
): ChunkNbtContext<ProtocolBlockState, ProtocolRegistryEntry> {
    val chunkLayout = minecraftDimensionLayout.toChunkLayout()
    val chunkDataRegistries = protocolRegistryContext.toChunkDataRegistries()
    return ChunkNbtContext(
        chunkLayout = chunkLayout,
        chunkDataRegistries = chunkDataRegistries,
        expectedDataVersion = expectedDataVersion,
    )
}
```

The registry adapter retains the active immutable context by reference and defaults to its `minecraft:air` block state
and `minecraft:plains` biome entry. Callers may select different defaults explicitly. Neither conversion reads a world
file, encodes a packet, or owns connection state.
