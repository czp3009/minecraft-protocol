# buildSrc

This build layer owns shared build constants, the selected Minecraft release, verified official artifacts, official
analysis, cacheable generation from non-source inputs, and the Gradle-managed Fixture Host service.

## Task ownership

- `BuildVersions` defines Gradle toolchains and bytecode targets. `MinecraftTarget.MINECRAFT_VERSION` and
  `HeadlessMcTarget.HEADLESS_MC_VERSION` are the manually selected Minecraft and HeadlessMC releases.
- Official fixture-preparation tasks acquire and verify the server, client, metadata, libraries, and HeadlessMC adapter.
  The HeadlessMC client layout uses its OGG, PNG, and JSON placeholders and verified official objects for every
  remaining asset format. Server-runtime, codec-oracle, and fixture inputs stay below the root `build/` directory.
- Root official-analysis tasks own separate target, report, and Configuration output directories and publish precise
  consumable artifacts. Lifecycle tasks aggregate producers but declare no duplicate output.
- Cacheable source-generation task types live here, while the runtime module owning the generated source registers the
  task and output directory.
- `MinecraftTestFixtures` attaches required fixture providers and the shared service to standard test tasks.
  `MinecraftTestFixtureService` owns lazy Fixture Host startup and task-owner cleanup.

## Gradle implementation

- HTTP downloads use the Ktor client with timeout and retry plugins and stream large bodies through `kotlinx-io`.
  Download tasks do not use `java.net`, sleeps, or custom retry schedulers.
- Every task declares exact inputs and outputs, validates downloaded or generated content itself, and remains compatible
  with the build cache and configuration cache. Do not add a separate verification task, a manual freshness comparison,
  or a `buildSrc` unit test for behavior the task validates during execution.
- Functional producer edges flow through `Provider`, `DirectoryProperty`, and `RegularFileProperty` relationships.
  Logically independent outputs use separate cacheable tasks and have no ordering edge: the client JAR, client library
  set, and asset index are independent producers, while the filtered asset objects wait only for the index and dummy
  files. A cohesive collection such as the client libraries or asset objects uses bounded coroutine concurrency inside
  its owning task.
- `prepareOfficialMinecraftServer`, `prepareHeadlessMc`, `prepareOfficialMinecraftClient`, and
  `prepareOfficialMinecraftCodecOracle` are actionless lifecycle gates. Fixture consumers depend on these gates through
  `dependsOn` or `builtBy`, not directly on their download, extraction, compilation, or layout tasks.
  `prepareHeadlessMc` owns the launcher, while `prepareOfficialMinecraftClient` owns the official client, its filtered
  assets and dummy files, and its HeadlessMC-compatible layout. A client fixture is built by both independent gates. Do
  not resolve providers during configuration.
- Download tasks write directly to their final paths whenever possible and remain cacheable. Unavoidable compatibility
  copies use Gradle Sync so stale destination files are removed automatically.
- Root analysis is the only build-task layer that opens or executes the official server JAR. Data-to-source tasks
  consume its declared JSON artifacts.
- Kotlin generation uses KotlinPoet and Java generation uses JavaPoet. Generated output stays in the owning module's
  build directory.
- Gradle task code logs through Gradle's logger and reports actionable validation errors. It does not use
  kotlin-logging, success `println`, or ad hoc process output as a result format.

## Fixture Host wiring

Official-peer capability flags map to exact lazy artifact collections on supported standard KMP test tasks. A consuming
test task obtains `MinecraftTestFixtureService` from its execution action, after its file inputs have produced the
Fixture Host classpath and requested official fixtures. Browser tasks are not an official-peer gate, and Wasm/WASI has
no network transport; both exclude official-peer tests before the Fixture Host is attached. Host-filesystem access is
not a Fixture capability flag. Consumers isolate code that dereferences the Host working-directory backdoor behind thin
test entries in standard source sets whose runtimes share the Host filesystem namespace.

The service passes explicit artifact and work-directory paths to the JVM host, injects connection data into the test
process environment, associates resources with the consuming task, releases that owner on task completion, and closes
the host at build shutdown. It does not add a fixture-launch task, runtime path property, or eager service startup.

## Verification

Run the narrowest task that consumes changed build logic, then repeat it unchanged to verify cache reuse. Changes to
task inputs, outputs, or Build Service wiring also require a configuration-cache store and reuse check. Keep the build
cache enabled.
