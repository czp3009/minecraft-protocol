# protocol-configuration

- Consume world-format's pack stages. Own server `ConfigurationData`/`ResolvedConfigurationData` and client
  `DataPackConfigurationSnapshot`/`ClientRegistryView`; endpoints turn those values into packet sequences.
- Generic projection requires explicit base data and per-registry projectors. Do not assume persisted JSON and
  registry network NBT are equivalent. Received snapshots contain only Configuration-visible data.
- Keep physical serialization out of this module. The vanilla provider owns decoding its generated packet payloads.
- `MinecraftDimensionLayout` combines synchronized type identity/raw ID with `DimensionTypeLayout`.
  `MinecraftDimensionContext` combines that layout, dimension identity and `PacketCodecContext`; domain defaults enter
  only when constructing a `ChunkContext`. Configuration values do not retain world codecs.
- `resolveMinecraftDimensions` resolves referenced types against the complete synchronized registry order, rejects
  inline holders and aggregates all failures before returning. It creates no partial map or synthetic registry entry.
- `resolveWorldChunkContexts` supports both referenced and inline holders and returns raw-ID-free domain contexts.
- `ResolvedConfigurationData` is an ordinary class because its default complete packet context derives from the static
  schema and registry packet sequence. A generated `copy` could retain stale derived data after replacing an input.
- `PacketCodecContext.withSynchronizedRegistries` is the shared registry overlay. Preserve unrelated mappings and reuse
  an unchanged context only after validating duplicate registry packets.

Run `:protocol-configuration:jvmTest`.
