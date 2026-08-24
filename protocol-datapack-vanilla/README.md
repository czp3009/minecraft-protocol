# protocol-datapack-vanilla

`protocol-datapack-vanilla` supplies generated, release-matched vanilla inputs for [
`protocol-datapack`](../protocol-datapack/README.md).

It includes:

- the complete core and built-in official data-pack archives;
- parsed packs and stack helpers;
- official static registries and the global block-state schema;
- both Known Packs response branches;
- feature flags, synchronized registries, and tags;
- ready-to-use server protocol data and client Configuration runtime values.

Everything is available as programmatic Kotlin values on every configured target; runtime filesystem access is not
required.

## Use the ready-made defaults

`VanillaDataPacks` exposes each useful stage:

| Need                               | Entry point             |
|------------------------------------|-------------------------|
| One raw official archive           | `archive(id)`           |
| All raw archives                   | `archives`              |
| Parsed core pack                   | `core`                  |
| Parsed core and built-in packs     | `packs`                 |
| Core-only stack                    | `coreStack`             |
| Stack with selected built-ins      | `stack(enabledBuiltIn)` |
| Merged core resources              | `resolvedCore`          |
| Server Configuration defaults      | `protocolData`          |
| Equivalent received client packets | `clientConfiguration`   |
| Resolved client lookup view        | `clientRuntime`         |

For a normal vanilla server:

```kotlin
val options = MinecraftServerNegotiationOptions(
    protocolData = VanillaDataPacks.protocolData,
)
```

This is already the default used by `MinecraftServerNegotiationOptions` and `MinecraftClientNegotiationOptions`; pass it
explicitly only when doing so makes application composition clearer.

## Add custom world packs

Start with the generated vanilla base and project an application-supplied stack:

```kotlin
fun createProtocolData(
    worldStack: DataPackStack,
    registryProjectors: List<DataPackSynchronizedRegistryProjector>,
): ProtocolDataSet = worldStack.toVanillaProtocolDataSet(
    registryProjectors = registryProjectors,
)
```

The helper treats the official core `vanilla` pack as already projected and applies the world stack above the captured
defaults. If a custom pack changes a synchronized registry, supply a projector that converts its disk resource into the
registry's network NBT representation. Packs that change only tags or server-only resources need no registry projector.

Applications can replace any stage: parse an archive with custom decoders, edit a `DataPack`, construct a
`ResolvedDataPackStack`, use a custom `DataPackProtocolProjection`, or construct `DataPackProtocolDataSet` directly.

## Select built-in packs

Use the generated IDs rather than spelling release-specific pack names:

```kotlin
fun resolveBuiltIns(enabledBuiltIns: Set<DataPackId>): ResolvedDataPackStack =
    VanillaDataPacks.stack(enabledBuiltIns).resolve(VanillaDataPacks.formatVersion)
```

`VanillaDataPacks.packIds` lists the available generated IDs, and `coreId` identifies the core pack.

## Use static registries and client values

`VanillaStaticData` exposes official protocol registry IDs and block-state schemas. `VanillaProtocolData` exposes the
matching Configuration packets and chooses compact Known Packs entries only when the client's accepted set matches the
official offer.

Most callers use the combined values:

```kotlin
val serverData: ProtocolDataSet = VanillaDataPacks.protocolData
val staticRegistries: StaticRegistrySchema = serverData.staticRegistries
val clientRuntime: ClientDataPackRuntime = VanillaDataPacks.clientRuntime
```

The generated values always follow the repository-selected Minecraft release; application documentation should not copy
their literal version.
