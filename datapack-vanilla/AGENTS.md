# Bundled data-pack provider

This module owns release-matched official data-pack archives, parsed packs, and completion of a persisted world's pack
selection.

- Its runtime dependencies point to `world-format`; Configuration projection belongs to
  `protocol-configuration-vanilla`.
- `generateVanillaDataPackSources` consumes the root `officialMinecraftDataPacks` artifact. The module owns its
  generated
  source directory and does not share it with another provider.
- Keep archives available independently of the default parser. Preserve lazy per-pack loading and priority order when
  completing a world selection.
- Core insertion is this provider's explicit completion policy. Preserve selected pack order and aggregate unresolved
  IDs without decoding unrelated built-ins. Configuration integration belongs to the Configuration provider's tests.

Run `./gradlew :datapack-vanilla:jvmTest` first, then the affected configured portable targets.
