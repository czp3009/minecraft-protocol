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

WorldGenSettingsData + ResolvedProtocolData --> resolveMinecraftChunkContexts()
    --> per-dimension raw-ID-free MinecraftChunkContext --> disk Chunk codec or Chunk packet adapter

referenced-only server branch --> resolveMinecraftWorld() --> ResolvedMinecraftWorld
    --> Configuration/Play Login identity + the same semantic Chunk contexts

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

## Resolve stored dimensions

`ResolvedProtocolData` contains the registry order sent during Configuration. `WorldGenSettingsData` contains the
persisted set of level stems and their referenced or inline dimension types. A disk tool or custom endpoint resolves the
semantic Chunk contexts directly:

```kotlin
fun resolveMinecraftChunkContexts(
    resolvedProtocolData: ResolvedProtocolData,
    worldGenSettingsData: WorldGenSettingsData,
): Map<DimensionId, MinecraftChunkContext> =
    resolvedProtocolData.resolveMinecraftChunkContexts(worldGenSettingsData)
```

Referenced layouts come from the exact projected dimension-type registry, while inline layouts are decoded from their
stored NBT. Each resulting context contains the persisted `DimensionId`, `DimensionTypeLayout`, active registries, and
ready `ChunkNbtCodec`; it deliberately contains no synchronized dimension-type raw ID.

A server that will advertise the same world uses the stricter operation:

```kotlin
fun resolveMinecraftWorld(
    resolvedProtocolData: ResolvedProtocolData,
    worldGenSettingsData: WorldGenSettingsData,
): ResolvedMinecraftWorld = resolvedProtocolData.resolveMinecraftWorld(worldGenSettingsData)
```

`ResolvedMinecraftWorld` retains the same `ResolvedProtocolData` and exposes one `MinecraftChunkContext` per
`DimensionId`:

```kotlin
val minecraftChunkContext = resolvedMinecraftWorld.dimension(dimensionId)
val chunkNbtCodec = minecraftChunkContext.chunkNbtCodec
val chunkLayout = minecraftChunkContext.chunkLayout
val dimensionTypeLayout = minecraftChunkContext.dimensionTypeLayout
val protocolRegistryContext = minecraftChunkContext.protocolRegistryContext
```

This server branch verifies that every dimension type has a synchronized ID/raw ID before returning any Chunk context.
Disk decoding, Play Login, and packet encoding then use one registry order when the server passes
`resolvedMinecraftWorld.protocolData`, its dimension keys, and the selected dimension to its negotiation options. The
raw ID remains in the network-facing protocol data and later `MinecraftDimensionContext`; it is not copied into the
disk/Chunk-packet context. The world object does not guess unrelated Status, connection, or initial-world policy.

`resolveMinecraftWorld()` accepts only referenced dimension types because Play Login requires a synchronized raw ID.
`resolveMinecraftChunkContexts()` accepts both holder shapes because semantic Chunk work does not. Both operations check
every declared dimension and report missing references or invalid layouts together in
`MinecraftWorldResolutionException`; neither returns a partial result or creates a synthetic registry entry.

The default Chunk values are `minecraft:air` and `minecraft:plains`. Modded registries select different identifiers once
through the optional `defaultBlock` and `defaultBiome` arguments on either resolver.

## Resolve client Configuration data

The wire transmits Known Packs, feature flags, synchronized registry entries, and tags. It does not transmit recipes,
loot tables, functions, advancements, or other server-only resources, so the client result deliberately is not a
`DataPack`.

The four `received...` values below are captured from the corresponding Configuration packets. The matching protocol
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

`MinecraftDimensionLayout` combines one synchronized dimension-type ID/raw ID with a shared `DimensionTypeLayout`.
`MinecraftDimensionContext` composes that layout with an active `ProtocolRegistryContext`, installs the selected
layout's Section count, and retains the three values as the shared network-negotiation handoff. It does not compare the
layout's identity or Section count with parallel facts already present in the registry context. A custom network decoder
can branch at this point without adopting the semantic world model.

When the application does want semantic Chunks, `ProtocolRegistryContext.toChunkDataRegistries()` resolves persisted
block descriptors and biome names against the active block-state and synchronized biome registries. After network
negotiation, the standard factory treats the selected layout and synchronized raw ID as authoritative for their own
fields before adding the semantic defaults. Applications that need cross-source consistency checks perform them before
calling the factory:

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

For disk or custom endpoint work, `MinecraftChunkContext.create(dimensionId, dimensionTypeLayout,
protocolRegistryContext)` performs the same semantic composition without requiring a `MinecraftDimensionLayout` or raw
ID. `MinecraftChunkContext` is the normal endpoint for disk and semantic Chunk-packet work. `ChunkDataRegistries`
remains public for a caller that only needs registry mapping, while `ChunkCodecContext` remains the filesystem- and
protocol-identity-free input for `ChunkNbtCodec` or a custom codec. Packet decoding and encoding stay in
`protocol-client` and `protocol-server`; this module performs no filesystem or socket I/O.
