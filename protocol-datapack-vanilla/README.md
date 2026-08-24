# protocol-datapack-vanilla

`protocol-datapack-vanilla` supplies the complete generated defaults for the repository-selected Minecraft release. It
starts with exact core and built-in data-pack files extracted from the official implementation JAR, and also includes
the official static registries, global block-state palette, Known Packs branches, feature flags, synchronized
registries, and tags.

The module depends on [`protocol-datapack`](../protocol-datapack/README.md). Generic pack parsing, projection, and
client runtime types stay there; this module contributes only release-matched vanilla inputs and convenience
conversions.

## Unified entry point

`VanillaDataPacks` exposes a default at every useful stage while preserving the underlying public types:

| Stage                       | Default                               |
|-----------------------------|---------------------------------------|
| Raw official files          | `archive(id)` / `archives`            |
| Parsed packs                | `core`, `builtIn`, `packs`            |
| Enabled stack               | `coreStack` / `stack(enabledBuiltIn)` |
| Merged resources            | `resolvedCore`                        |
| Resource-to-protocol policy | `protocolProjection`                  |
| Server negotiation data     | `protocolData`                        |
| Client packet capture       | `clientConfiguration`                 |
| Client runtime lookup view  | `clientRuntime`                       |

In the example, `customWorldStack` is an application-constructed `DataPackStack`, such as one parsed by `world-io`, and
`applicationRegistryProjectors` contains the application's conversions for synchronized registries changed by that
stack. Pass `emptyList()` when the stack does not change one of those registries.

```kotlin
val rawCore = VanillaDataPacks.archive(VanillaDataPacks.coreId)
val parsedCore = VanillaDataPacks.core
val protocolData = customWorldStack.toVanillaProtocolDataSet(
    registryProjectors = applicationRegistryProjectors,
)
val serverOptions = MinecraftServerNegotiationOptions(protocolData = protocolData)
```

`toVanillaProtocolDataSet` starts with the exact captured vanilla Configuration data and treats the core `vanilla` pack
as already projected. A built-in or custom pack that changes a synchronized registry still needs the matching
`DataPackSynchronizedRegistryProjector`; the library does not guess that registry JSON is its network NBT codec.

Applications remain free to replace any stage. They can parse an archive with custom decoders, modify a `DataPack`,
construct a `ResolvedDataPackStack`, create a different `DataPackProtocolProjection`, or bypass files entirely with a
manually constructed `DataPackProtocolDataSet`.

## Lazy generated payload

The official data-pack archive is generated as Kotlin because the supported KMP target set has no single reliable
runtime-resource lookup API. The generated manifest contains only the release, format, pack IDs, and batch counts.
Compressed Base64 payloads live in independent generated batch functions.

On JVM, loading the manifest or reading `minecraftVersion`, `formatVersion`, or `packIds` does not initialize the
archive strings. `archive(id)` and `parsePack(id)` request only that pack's batches, one at a time. Accessing `archives`
or `packs` intentionally materializes every pack.

## Gradle pipeline

The tasks have three distinct responsibilities:

```text
official server JAR
  -> extractOfficialServerRuntime
  -> extractOfficialMinecraftDataPacks
  -> :protocol-datapack-vanilla:generateVanillaDataPackSources

official reports / Configuration capture
  -> generateVanillaStaticDataSource
  -> generateVanillaConfigurationSource
```

Each consumer takes the producer's declared artifact Provider as an input, so Gradle supplies the dependency edge
without hand-written lifecycle `dependsOn`. `prepareOfficialMinecraftData` is the optional root aggregate for preparing
all official analysis and extraction artifacts. Generated Kotlin and extracted evidence stay below `build/` and are not
committed.

## Protocol defaults

`VanillaProtocolData` returns compact registry entries only when the client's Known Packs response exactly matches the
official offer; otherwise it returns complete network NBT. `VanillaStaticData` exposes protocol IDs and the complete
global block-state schema. Both feed `VanillaDataPacks.protocolData`, which is the default used by `protocol-client` and
`protocol-server` but can be replaced through their negotiation options.
