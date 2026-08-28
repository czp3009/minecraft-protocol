# Repository build logic

`buildSrc` is the repository's private Gradle build-logic layer. It is not a library dependency. Gradle compiles it
automatically so the root build can share platform configuration, prepare exact official artifacts, generate
release-matched source, and provide official server/client fixtures to tests.

## What it provides

- one repository-wide Minecraft release selector and shared Java/Android build versions;
- reusable Kotlin Multiplatform target and test-task configuration;
- downloads and analysis of matching official client/server artifacts;
- generation of protocol and world-format constants, packet reports, vanilla registries, block states, Configuration
  data, and data-pack source consumed by the owning runtime modules;
- prepared official-server, headless-client, and codec-oracle runtimes;
- lazy Fixture Host startup and cleanup for supported Gradle test tasks.

These facilities are development infrastructure. Applications using the runtime modules do not need
`buildSrc`, the official JARs, generators, or fixture processes on their runtime classpath.

## Useful root tasks

Run these commands from the repository root with the checked-in wrapper:

| Task                                     | Result                                                                                                     |
|------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `minecraftVersion`                       | Prints the repository-selected Minecraft release without preparing artifacts                               |
| `prepareOfficialMinecraftData`           | Produces the official target, reports, Configuration captures, and extracted data packs used by generators |
| `prepareOfficialMinecraftServer`         | Prepares an immutable official-server runtime and stopped default template                                 |
| `prepareHeadlessClient`                  | Prepares an immutable headless official-client runtime and stopped default template                        |
| `prepareOfficialMinecraftCodecOracle`    | Prepares the official codec oracle used by interoperability tests                                          |
| `prepareMinecraftTestFixtureHostRuntime` | Assembles the JVM Fixture Host runtime consumed by test tasks                                              |

For example:

```shell
./gradlew -q minecraftVersion
./gradlew prepareOfficialMinecraftData
```

Preparation tasks may download large, version-pinned artifacts or briefly start an official process while constructing a
stopped template. Normal module tests request only the fixture inputs they actually consume.

## Generated and prepared output

All derived output stays below Gradle `build/` directories. Generated Kotlin is attached to the source set owned by the
runtime module; prepared runtimes and templates remain private test inputs. Do not edit or commit these outputs. Change
the selector, producer, generator, or handwritten semantic source instead, then let Gradle rebuild the affected output.

For build-logic ownership, cacheability, generation, and Fixture Host invariants, read [AGENTS.md](AGENTS.md). After a
build-logic change, run the narrowest affected consumer task and repeat it unchanged to check cache reuse. The focused
unit suite for this build is:

```shell
./gradlew -p buildSrc test
```
