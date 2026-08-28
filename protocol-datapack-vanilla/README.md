# protocol-datapack-vanilla

`protocol-datapack-vanilla` supplies generated, release-matched vanilla inputs for
[`protocol-datapack`](../protocol-datapack/README.md).

It includes:

- the complete core and built-in official data-pack archives;
- parsed packs and stack helpers;
- official static registries and the global block-state schema;
- both Known Packs response branches;
- feature flags, synchronized registries, and tags;
- default disk-JSON-to-network-NBT projectors for every synchronized vanilla registry;
- ready-to-use server protocol data, client Configuration snapshots, and registry views.

Everything is available as programmatic Kotlin values on every configured target; runtime filesystem access is not
required.

## Use the ready-made defaults

The three public objects have distinct responsibilities: `VanillaDataPacks` owns actual pack content,
`VanillaRegistryData` owns static registries and block states, and `VanillaProtocolData` owns Configuration protocol
defaults.

`VanillaDataPacks` exposes the pack stages:

| Need                           | Entry point                                                 |
|--------------------------------|-------------------------------------------------------------|
| One raw official archive       | `VanillaDataPacks.dataPackArchive(dataPackId)`              |
| One parsed bundled pack        | `VanillaDataPacks.dataPackOrNull(dataPackId)`               |
| Parsed core pack               | `VanillaDataPacks.coreDataPack`                             |
| Parsed core and built-in packs | `VanillaDataPacks.dataPacks`                                |
| Core-only stack                | `VanillaDataPacks.coreDataPackStack`                        |
| Stack with selected built-ins  | `VanillaDataPacks.dataPackStack(enabledBuiltInDataPackIds)` |

Raw archives are decoded only when requested and are not retained beside the parsed-pack cache.

The remaining ready-made values stay on the stage that owns them:

| Need                                 | Entry point                                         |
|--------------------------------------|-----------------------------------------------------|
| Static registry and block data       | `VanillaRegistryData`                               |
| Server Configuration defaults        | `VanillaProtocolData`                               |
| Equivalent client Configuration data | `VanillaProtocolData.dataPackConfigurationSnapshot` |
| Resolved client lookup view          | `VanillaProtocolData.clientRegistryView`            |
| Default registry projectors          | `vanillaDataPackRegistryProjectors`                 |
| Datapack-to-protocol conversion      | `dataPackStack.toVanillaProtocolData()`             |
| Complete one world selection         | `worldDataPackLoadResult.toVanillaDataPackStack()`  |
| World selection to protocol data     | `worldDataPackLoadResult.toVanillaProtocolData()`   |

The high-level client and server defaults already use `VanillaProtocolData`; callers construct negotiation options only
when overriding another behavior. The root guide owns the zero-configuration client and server examples.

## Add custom world packs

The direct world bridge consumes the detached result returned by either `world-io` facade:

```kotlin
fun resolveWorldDataPackProtocolData(
    worldDataPackLoadResult: WorldDataPackLoadResult,
): ResolvedProtocolData = worldDataPackLoadResult.toVanillaProtocolData()
```

The helper fills only IDs in the persisted enabled selection, loading selected bundled packs independently and keeping
the exact low-to-high priority order. It inserts the required core pack at the bottom when the persisted list omits it,
and reports every other unavailable ID together. It does not discover unlisted packs or simulate the official server's
next-start repository reconfiguration. Persisted enabled features and selected pack metadata augment the generated
vanilla defaults; disabled pack IDs and removed historical feature IDs remain diagnostic world data.

The official core `vanilla` resources are already represented by the generated protocol base. The release-matched
`vanillaDataPackRegistryProjectors` then applies file and built-in overlays for every synchronized registry, so ordinary
vanilla datapacks—including packs that replace or add registry entries—need no caller-written mapping.

An application that already owns a complete stack can use the lower conversion directly:

```kotlin
fun resolvePreparedDataPackProtocolData(
    dataPackStack: DataPackStack,
): ResolvedProtocolData = dataPackStack.toVanillaProtocolData()
```

Mods remain an explicit escape hatch. The `modDataPackRegistryProjectors` parameter below contains projectors supplied
by the loader or application. A matching registry ID replaces the corresponding vanilla default and a new registry ID is
added after the vanilla set:

```kotlin
fun resolveModdedDataPackProtocolData(
    dataPackStack: DataPackStack,
    modDataPackRegistryProjectors: List<DataPackRegistryProjector>,
): ResolvedProtocolData = dataPackStack.toVanillaProtocolData(
    dataPackRegistryProjectorOverrides = modDataPackRegistryProjectors,
)
```

Applications can replace any stage: parse an archive with custom decoders, edit a `DataPack`, construct a
`ResolvedDataPackStack`, construct `DataPackProtocolProjector` directly to replace the entire policy, or construct
`ResolvedProtocolData` directly.

## Select built-in packs

Use the generated IDs rather than spelling release-specific pack names:

```kotlin
fun resolveBuiltInDataPacks(enabledBuiltInDataPackIds: Set<DataPackId>): ResolvedDataPackStack =
    VanillaDataPacks.dataPackStack(enabledBuiltInDataPackIds).resolve(VanillaDataPacks.dataPackFormatVersion)
```

`VanillaDataPacks.dataPackIds` lists the available generated IDs, and `coreDataPackId` identifies the core pack.

## Use static registries and client values

`VanillaRegistryData` exposes official protocol registry IDs and block-state schemas. `VanillaProtocolData` exposes the
matching Configuration data and chooses compact synchronized registries only when the client's accepted Known Packs list
exactly matches the official offer.

Most callers use these values directly:

```kotlin
val protocolData: ProtocolData = VanillaProtocolData
val staticRegistrySchema: StaticRegistrySchema = protocolData.staticRegistrySchema
val clientRegistryView: ClientRegistryView = VanillaProtocolData.clientRegistryView
```

The generated values always follow the repository-selected Minecraft release.
