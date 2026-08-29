# protocol-datapack

`protocol-datapack` is the vanilla-neutral bridge between filesystem-independent data-pack values and Minecraft
Configuration data. It owns `ProtocolData`/`ResolvedProtocolData` for the server side and
`DataPackConfigurationSnapshot`/`ClientRegistryView` for the client side. It also adapts active Configuration-derived
dimension and registry facts to the semantic Chunk contracts in `world-format`.

Its projector, resolved server data, received snapshot, and client view are all caller-constructible. This module
contains no bundled vanilla values and performs no filesystem or socket I/O.

The cross-module path keeps each representation explicit:

```text
disk directory/ZIP --world-io--> WorldDataPackLoadResult --source completion--> DataPackStack
    --world-format--> ResolvedDataPackStack
    --protocol-datapack--> ResolvedProtocolData --protocol-server--> Configuration packets

WorldGenSettingsData + ResolvedProtocolData --> ResolvedMinecraftWorld
    --> per-dimension MinecraftChunkContext --> disk Chunk codec or network Chunk adapter

optional raw snapshot: DataPackArchive -> DataPackFormat -> DataPack

received Configuration values -> DataPackConfigurationSnapshot -> ClientRegistryView

Configuration + Play Login --> MinecraftDimensionContext --> caller-selected defaults --> MinecraftChunkContext
```

## Inputs from data packs

The filesystem-independent archive, parser, resource, overlay, filter, partial world-selection result, and stack types
belong to
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

## Resolve a complete server world

`ResolvedProtocolData` contains the registry order sent during Configuration. `WorldGenSettingsData` contains the
persisted set of level stems and their dimension-type references. Resolve them together before reading Chunks for a
server:

```kotlin
fun resolveMinecraftWorld(
    resolvedProtocolData: ResolvedProtocolData,
    worldGenSettingsData: WorldGenSettingsData,
): ResolvedMinecraftWorld = resolvedProtocolData.resolveMinecraftWorld(worldGenSettingsData)
```

The result retains the same `ResolvedProtocolData` and exposes one `MinecraftChunkContext` per `DimensionId`:

```kotlin
val minecraftChunkContext = resolvedMinecraftWorld.dimension(dimensionId)
val chunkNbtCodec = minecraftChunkContext.chunkNbtCodec
val chunkLayout = minecraftChunkContext.chunkCodecContext.chunkLayout
val protocolRegistryContext = minecraftChunkContext.protocolRegistryContext
```

Each context binds the persisted dimension, its synchronized dimension-type ID and raw ID, its validated
`DimensionTypeLayout`, the active protocol registries, and a ready `ChunkNbtCodec`. Disk decoding, Play Login, and
packet encoding use one registry order when the server passes `resolvedMinecraftWorld.protocolData`, its dimension keys,
and the selected dimension to its negotiation options. The world object does not guess unrelated Status, connection, or
initial-world policy.

The complete server path accepts only referenced dimension types because Play Login requires a synchronized
dimension-type raw ID. Resolution checks every declared dimension first and reports inline holders, missing references,
and invalid dimension-type data together in `MinecraftWorldResolutionException`; it never returns a partial world or
creates a synthetic registry entry. Tools that only inspect an inline holder can stay on the lower-level
`DimensionTypeLayout`/`ChunkCodecContext` path in `world-format`.

The default Chunk values are `minecraft:air` and `minecraft:plains`. Modded registries select different identifiers once
through the optional `defaultBlock` and `defaultBiome` arguments to `resolveMinecraftWorld`.

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

`MinecraftDimensionLayout` combines one synchronized dimension-type ID/raw ID with a shared
`DimensionTypeLayout`; its `chunkLayout` is already validated. `MinecraftDimensionContext` validates that layout against
an active `ProtocolRegistryContext`, activates the required Section count, and retains the three values as the shared
network-negotiation handoff. A custom decoder can branch at this point without adopting the semantic world model.

When the application does want semantic Chunks, `ProtocolRegistryContext.toChunkDataRegistries()` resolves persisted
block descriptors and biome names against the active block-state and synchronized biome registries. The standard fluent
path chooses the semantic defaults once:

The standard factory performs the complete composition and cross-checks the dimension-type raw ID, Section count, and
default registry entries:

```kotlin
fun createMinecraftChunkContext(
    dimensionId: DimensionId,
    minecraftDimensionLayout: MinecraftDimensionLayout,
    protocolRegistryContext: ProtocolRegistryContext,
): MinecraftChunkContext {
    val minecraftDimensionContext = MinecraftDimensionContext.create(
        dimensionId = dimensionId,
        minecraftDimensionLayout = minecraftDimensionLayout,
        protocolRegistryContext = protocolRegistryContext,
    )
    return minecraftDimensionContext.createMinecraftChunkContext()
}
```

`MinecraftChunkContext.create(...)` remains the direct equivalent when the caller already has the three source values.
`MinecraftChunkContext` is the normal endpoint for disk and semantic packet work. `ChunkDataRegistries` remains public
for a caller that only needs registry mapping, while `ChunkCodecContext` remains the filesystem- and
protocol-identity-free input for `ChunkNbtCodec` or a custom codec. Packet decoding and encoding stay in
`protocol-client` and `protocol-server`; this module performs no filesystem or socket I/O.
