# buildSrc

This guide applies to shared Gradle conventions, exact external-version selectors, official artifacts and analysis,
non-source generation, fixture templates, and Fixture Host service wiring.

## Version and artifact ownership

- `BuildVersions` selects toolchains and bytecode targets. `MinecraftTarget`, `HeadlessMcTarget`, `FabricLoaderTarget`,
  and `HmcSpecificsTarget` independently select exact external inputs; do not derive one selector from another.
- Official-data producers own separate target, packet/registry/block reports, Configuration captures, and extracted
  data-pack outputs.
- Fixture producers acquire every version-pinned server/client runtime, library, asset, wrapper, loader, and mod input
  before launch. Fixture processes do not download missing resources.
- Downloads rely on HTTP completion and declared provenance. Do not add duplicate checksums, expected sizes, freshness
  comparisons, or verification tasks; Mojang hashes remain only where upstream uses them as object locators.

## Task design

- Every producer declares exact inputs and outputs and supports build/configuration caches. Connect producers and
  consumers through lazy `Provider`, `DirectoryProperty`, and `RegularFileProperty` relationships without resolving them
  during configuration.
- Independent outputs remain independent tasks. Use bounded coroutine concurrency inside one task only for a cohesive
  collection.
- Downloads publish from a temporary sibling after success. Assembled directory outputs use Gradle `Sync` so stale
  destination files disappear.
- `prepareOfficialMinecraftData`, `prepareOfficialMinecraftServer`, `prepareHeadlessClient`,
  `prepareOfficialMinecraftCodecOracle`, and `prepareMinecraftTestFixtureHostRuntime` are actionless lifecycle gates
  over their real producers.
- `GenerateMinecraftProtocolSourceTask`, `GenerateVanillaRegistryDataSourceTask`,
  `GenerateVanillaConfigurationPacketPayloadSourceTask`, and `GenerateVanillaDataPackSourcesTask` are task types only;
  the runtime module that owns each generated source set registers its producer.
- `JvmProcessArguments` owns shared JVM arguments. Official processes and JVM tasks that load the affected native-backed
  libraries receive `--enable-native-access=ALL-UNNAMED` before application arguments.

## Immutable fixture templates

- Server template generation waits for the official ready event and a complete Status response/pong, stops normally,
  then publishes reusable runtime/template manifests. Per-process logs and generated `server.properties` are not
  template state.
- Headless client template generation waits for HMC-Specifics initialization and a correlated `TitleScreen` GUI
  observation, quits normally, then publishes its reusable game directory.
- Later workspaces never launch an immutable root in place. Preserve the established symlink, hard-link, and copy policy
  encoded by the preparation and host code; mutable files are always real copies.

## Fixture Host wiring

- `MinecraftTestFixtures` attaches precise lazy artifact inputs and the shared `MinecraftTestFixtureService` to
  supported standard test tasks. The service starts the host only from a test execution action and owns task cleanup.
- Exactly `jsBrowserTest`, `wasmJsBrowserTest`, `wasmJsD8Test`, and `wasmWasiNodeTest` filter `*.fixturetest.*` and
  receive no Fixture Host inputs or service wiring.
- `world-io`'s `hostFilesystemTest` is wired through supported source-set dependencies, not a fixture flag.
- Pass ordinary connection environment variables to normal test processes and `SIMCTL_CHILD_`-prefixed variants to
  `KotlinNativeSimulatorTest`. Do not add eager startup, launch tasks, or runtime path system properties.

## Verification

Run `./gradlew -p buildSrc test` for the focused build-logic unit suite. Verify task behavior through the narrowest
runtime-module consumer named by the changed registration.
