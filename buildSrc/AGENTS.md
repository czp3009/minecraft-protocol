# buildSrc

This build layer owns shared build constants, exact external release selectors, official artifacts and analysis,
cacheable generation from non-source inputs, immutable fixture templates, and the Gradle-managed Fixture Host service.

## Task ownership

- `BuildVersions` defines Gradle toolchains and bytecode targets. `MinecraftTarget`, `HeadlessMcTarget`,
  `FabricLoaderTarget`, and `HmcSpecificsTarget` independently select the exact Minecraft, HeadlessMC wrapper, Fabric
  Loader profile, and HMC-Specifics release coordinate. Normal builds never query a latest release or derive one
  product's version from another.
- Official fixture-preparation tasks acquire the server, Mojang client, Fabric profile and libraries, HeadlessMC
  wrapper, HMC-Specifics Fabric asset, and every client asset needed at runtime. The layout uses HeadlessMC's OGG, PNG,
  and JSON replacements and Mojang's content-addressed object paths for remaining formats. No fixture process may
  download a missing resource after launch. Server runtime, codec oracle, templates, and fixture inputs stay below root
  `build/`.
- `generateOfficialMinecraftServerTemplate` starts the assembled server, requires a complete Status response and pong,
  stops it normally, separates its extracted immutable runtime, and publishes all reusable files and empty directories.
  It excludes only the fixed per-process log content and dynamic `server.properties` recorded by its manifest. Later
  workspaces expose the read-only server library directory through one directory symbolic link when supported, with a
  private per-file hard-link-or-copy tree as fallback.
- `generateHeadlessClientTemplate` starts the assembled HeadlessMC/Fabric/HMC-Specifics client, observes HMC-Specifics
  initialization and `TitleScreen`, quits normally, and publishes the complete reusable game directory. The Fabric
  processed-mod cache, sole HMC-Specifics mod, generated options, and empty directory skeleton are retained; only
  crash/log contents and the resource-pack download event log are excluded by fixed manifest rules. Later workspaces
  expose the complete read-only client Minecraft runtime through one directory symbolic link when supported, with a
  private per-file hard-link-or-copy tree as fallback. The mod and processed-mod cache are fixed immutable subtrees:
  template publication and later workspaces use hard links with copy fallback for their files, while generated options
  and other mutable state use real copies.
- Root official-analysis tasks own separate target, report, and Configuration output directories and publish precise
  consumable artifacts. Lifecycle tasks aggregate producers but declare no duplicate output.
- Cacheable source-generation task types live here, while the runtime module owning the generated source registers the
  task and output directory.
- `MinecraftTestFixtures` attaches required fixture providers and the shared service to standard test tasks.
  `MinecraftTestFixtureService` owns lazy Fixture Host startup and task-owner cleanup.

## Gradle implementation

- HTTP downloads use the Ktor client with timeout and retry plugins and stream large bodies through `kotlinx-io`.
  Download tasks do not use `java.net`, sleeps, or custom retry schedulers.
- Every task declares exact inputs and outputs, validates the semantic structure it consumes or generates, and remains
  compatible with the build cache and configuration cache. HTTP completion is sufficient for downloaded bytes: do not
  add content-digest, checksum, expected-size, or duplicate integrity validation. Mojang asset hashes are retained only
  as upstream object URL and path locators. Do not add a separate verification task or manual freshness comparison.
- Functional producer edges flow through `Provider`, `DirectoryProperty`, and `RegularFileProperty` relationships.
  Logically independent outputs use separate cacheable tasks and have no ordering edge: the client JAR, client library
  set, and asset index are independent producers, while the filtered asset objects wait only for the index and dummy
  files. A cohesive collection such as the client libraries or asset objects uses bounded coroutine concurrency inside
  its owning task.
- `prepareOfficialMinecraftServer`, `prepareHeadlessClient`, `prepareOfficialMinecraftCodecOracle`, and
  `prepareMinecraftTestFixtureHostRuntime` are actionless lifecycle gates. Fixture consumers use their lazy output
  providers, not direct dependencies on individual downloads, assembly steps, or template workers. The server and client
  gates include their actual template producers. Do not resolve providers during configuration.
- Downloads stream to a temporary sibling and publish atomically after a successful HTTP response. Cohesive assembled
  directories use Gradle Sync so stale destination files are removed automatically.
- Root analysis is the only build-task layer that inspects the official server JAR. The declared server-template
  producer may execute it without inspection; data-to-source tasks consume analysis JSON rather than the JAR.
- Kotlin generation uses KotlinPoet and Java generation uses JavaPoet. Generated output stays in the owning module's
  build directory.
- Gradle task code logs through Gradle's logger and reports actionable validation errors. It does not use
  kotlin-logging, success `println`, or ad hoc process output as a result format.
- `JvmProcessArguments` owns JVM flags shared by affected module test conventions and build logic. JVM tests in modules
  that load native-backed libraries, template workers, official processes started by build tasks, and the Fixture Host
  JVM pass `--enable-native-access=ALL-UNNAMED` before their application entry points.

## Fixture Host wiring

Official-peer capability flags map to exact lazy artifact collections on supported standard KMP test tasks. A consuming
test task obtains `MinecraftTestFixtureService` from its execution action, after its file inputs have produced the
Fixture Host classpath and requested official fixtures. Exactly `jsBrowserTest`, `wasmJsBrowserTest`, `wasmJsD8Test`,
and `wasmWasiNodeTest` exclude `*.fixturetest.*` and receive no Fixture Host wiring; do not replace these exact
leaf-task names with prefixes, suffixes, or class-name conventions. Host-filesystem access is not a Fixture capability
flag.
`world-io` isolates the annotated test and all code that dereferences the Host working-directory backdoor in
`hostFilesystemTest`. JVM, Node, and desktop Native standard test source sets depend on it directly and need no repeated
platform entry files.

The service passes explicit artifact and work-directory paths to the JVM host, injects connection data into the test
process environment, associates resources with the consuming task, releases that owner on task completion, and closes
the host at build shutdown. A `KotlinNativeSimulatorTest` receives the same two values through `SIMCTL_CHILD_`-prefixed
variables so `simctl spawn` forwards their ordinary names to the child; other tasks receive the ordinary names directly.
The host JVM receives the shared native-access argument because the official codec oracle runs inside that process. The
service does not add a fixture-launch task, runtime path property, or eager service startup.

## Verification

Run the narrowest task that consumes changed build logic, then repeat it unchanged to verify cache reuse. Changes to
task inputs, outputs, or Build Service wiring also require a configuration-cache store and reuse check. Keep the build
cache enabled.
