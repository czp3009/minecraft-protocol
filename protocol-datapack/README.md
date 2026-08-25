# protocol-datapack

`protocol-datapack` is the vanilla-neutral bridge between filesystem-independent data-pack values and Minecraft
Configuration data. It also owns the client-side conversion from received Configuration packets to runtime registry,
block-state, and tag lookups.

```text
DataPackArchive -> DataPack -> DataPackStack -> ResolvedDataPackStack
    -> DataPackProtocolProjector -> ResolvedProtocolData -> server Configuration packets

received Configuration packets -> DataPackConfigurationSnapshot -> ClientRegistryView
```

Every stage is publicly constructible. Applications may start with parsed disk files from `world-io`, an archive from
another source, programmatic JSON/NBT/custom `DataPackFileContent`, an already resolved stack, or exact final protocol
packets. This module contains no bundled vanilla values and performs no filesystem or socket I/O.

## Parse and resolve packs

The filesystem-independent archive, parser, resource, overlay, filter, and stack types are owned by `world-format` and
are exposed to this layer through its public dependency. `DataPackFormat` classifies every file without imposing
file-count or size policy. JSON remains a lossless `JsonElement`; compressed NBT is decoded from retained in-memory
bytes when its `nbtDocument` is requested; and mods may provide `DataPackFileDecoder` plus custom
`DataPackFileContent` implementations.

`WorldDataPackReader.readDataPack` parses directly while reading and need not retain a `DataPackArchive`. Use the
archive stage only when the caller needs a complete raw byte snapshot.

In the example, `dataPackId` identifies the pack, `dataPackFileBytesByPath` is its detached path-to-bytes map,
`dataPackFileDecoders` is the optional list supplied by a mod, and `dataPackFormatVersion` is selected by the caller:

```kotlin
val dataPackArchive = DataPackArchive(dataPackId, dataPackFileBytesByPath)
val dataPack = DataPackFormat(dataPackFileDecoders = dataPackFileDecoders).decode(dataPackArchive)
val dataPackStack = DataPackStack(dataPack)
val resolvedDataPackStack = dataPackStack.resolve(dataPackFormatVersion)
```

## Build server Configuration data

`ProtocolData` is the final server/client negotiation boundary. Its properties are domain values rather than prebuilt
Feature Flags or Update Tags packets. Construct `ResolvedProtocolData` directly when a loader or application already
owns exact Known Packs, synchronized registries, registry tags, static block schemas, and feature flags.

To derive it from resources, supply an explicit base and conversion policy. Here `dataPackStack` and
`dataPackFormatVersion` come from the preceding parse example; `applicationProtocolDefaults`,
`applicationRegistryProjectors`, and
`applicationPreprojectedPackIds` are values explicitly selected by the application or loader:

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
