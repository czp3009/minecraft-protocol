# protocol-datapack

`protocol-datapack` is the vanilla-neutral bridge between filesystem-independent data-pack values and Minecraft
Configuration data. It also owns the client-side conversion from received Configuration packets to runtime registry,
block-state, and tag lookups.

```text
DataPackArchive -> DataPack -> DataPackStack -> ResolvedDataPackStack
    -> DataPackProtocolProjection -> DataPackProtocolDataSet -> server Configuration packets

received Configuration packets -> ReceivedDataPackConfiguration -> ClientDataPackRuntime
```

Every stage is publicly constructible. Applications may start with parsed disk files from `world-io`, an archive from
another source, programmatic JSON/NBT/custom `DataPackFileContent`, an already resolved stack, or exact final protocol
packets. This module contains no bundled vanilla values and performs no filesystem or socket I/O.

## Parse and resolve packs

The filesystem-independent archive, parser, resource, overlay, filter, and stack types are owned by `world-format` and
are exposed to this layer through its public dependency. `DataPackFormat` parses every file without imposing file-count
or size policy. JSON remains a lossless `JsonElement` and can be decoded into caller-selected strong types; mods may
provide `DataPackFileDecoder` and custom `DataPackFileContent` implementations.

In the example, `packId` identifies the pack, `files` is its detached path-to-content map, `modDecoders` is the optional
list supplied by a mod, and `selectedFormat` is the data-pack format selected by the caller:

```kotlin
val archive = DataPackArchive(packId, files)
val pack = DataPackFormat(customDecoders = modDecoders).decode(archive)
val stack = DataPackStack(pack)
val resolved = stack.resolve(selectedFormat)
```

## Build server Configuration data

`ProtocolDataSet` is the final server/client negotiation boundary. Construct `DataPackProtocolDataSet` directly when a
loader or application already owns exact Known Packs, registries, tags, static block schemas, and feature flags.

To derive it from resources, supply an explicit base and conversion policy. Here `stack` and `selectedFormat` come from
the preceding parse example; `applicationProtocolDefaults`, `applicationRegistryProjectors`, and
`applicationPreprojectedPackIds` are values explicitly selected by the application or loader:

```kotlin
val projection = DataPackProtocolProjection(
    base = applicationProtocolDefaults,
    registryProjectors = applicationRegistryProjectors,
    preprojectedPacks = applicationPreprojectedPackIds,
)
val protocolData = stack.toProtocolDataSet(projection, selectedFormat)
```

Disk JSON and synchronized network NBT are different codecs. A changed registry therefore requires a
`DataPackSynchronizedRegistryProjector`; unresolved registry types are reported together through
`MissingDataPackRegistryProjectors`. Filters, overlays, enabled feature flags, and tags are projected generically.

For generated official defaults and the shorter `toVanillaProtocolDataSet` path, add
[`protocol-datapack-vanilla`](../protocol-datapack-vanilla/README.md).

## Resolve client Configuration data

The wire transmits Known Packs, feature flags, synchronized registry entries, and tags. It does not transmit recipes,
loot tables, functions, advancements, or other server-only resources, so the client result deliberately is not a
`DataPack`.

The four `received...` values below are retained from the corresponding Configuration packets. The matching protocol
defaults and local schemas come from the client implementation, while `loaderRegistrySnapshot` is supplied by its mod
loader, or is `RemoteRegistrySnapshot.Empty` on an unmodded client:

```kotlin
val received = ReceivedDataPackConfiguration(
    knownPacks = receivedKnownPacks,
    featureFlags = receivedFeatureFlags,
    registries = receivedRegistryPackets,
    tags = receivedTags,
)
val runtime = received.resolveRuntime(
    protocolData = matchingProtocolDefaults,
    staticRegistries = localVanillaAndModSchemas,
    remoteRegistries = loaderRegistrySnapshot,
)
```

An application may instead pass an already resolved `ProtocolRegistryContext`. `RemoteRegistrySnapshot` supplies loader
raw IDs, aliases, overrides, and blocked entries. `StaticRegistrySchema` supplies the ordered states of every locally
implemented block. Missing block schemas fail with `MissingStaticBlockSchemas` rather than silently producing incorrect
global block-state IDs.
