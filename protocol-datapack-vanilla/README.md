# protocol-datapack-vanilla

`protocol-datapack-vanilla` supplies generated, release-matched vanilla inputs for [
`protocol-datapack`](../protocol-datapack/README.md).

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

The high-level client and server modules already use these values. A normal vanilla server does not construct or pass a
`ProtocolData`, connection definition, profile, or negotiation options:

```kotlin
suspend fun negotiateVanilla(connection: MinecraftServerConnection): MinecraftServerNegotiationResult? =
    connection.negotiate()
```

`MinecraftServerNegotiationOptions` and `MinecraftClientNegotiationOptions` use `VanillaProtocolData` automatically when
an application does need to override another option.

## Add custom world packs

Start with the generated vanilla base and project an application-supplied stack:

```kotlin
fun resolveWorldDataPackProtocolData(
    worldDataPackStack: DataPackStack,
): ResolvedProtocolData = worldDataPackStack.toVanillaProtocolData()
```

The helper treats the official core `vanilla` pack as already projected and applies the world stack above the captured
defaults. `vanillaDataPackRegistryProjectors` covers every synchronized registry in the repository-selected release, so
ordinary vanilla datapacks—including packs that replace or add registry entries—need no caller-written mapping. The
official-client interoperability suite exercises all bundled vanilla registry entries through this path.

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

The generated values always follow the repository-selected Minecraft release; application documentation should not copy
their literal version.
