# Gradle workflow

Run commands from the repository root. On Windows use `.\gradlew.bat`; on Unix-like systems use `./gradlew`.

## Version and generation

```powershell
.\gradlew.bat -q minecraftVersion
.\gradlew.bat refreshProtocolSpecification
```

The first command prints only the buildSrc-selected release. The refresh command downloads/verifies the official server,
runs official reports, captures both Configuration Known Packs branches, generates an expected specification under
`build/`, then synchronizes canonical evidence into `protocol-specification`.

Compilation automatically invokes only the deterministic prerequisites it needs:

- `protocol-model` generates `MinecraftProtocol.kt`;
- `protocol-serialization` generates packet registry entries;
- `protocol-vanilla-data` generates static and Configuration payload sources.

Those tasks are internal implementation details and remain ungrouped. Generated Kotlin lives under module
`build/generated` and is included in source JARs.

Root `clean` removes module build directories and `protocol-specification`. Because ordinary tests never rewrite the
source tree, run `refreshProtocolSpecification` after cleaning before expecting specification consistency tests to pass.

## Iteration order

Prefer the narrowest affected JVM suite:

```powershell
.\gradlew.bat :protocol-model:jvmTest
.\gradlew.bat :protocol-serialization:jvmTest
.\gradlew.bat :protocol-vanilla-data:jvmTest
.\gradlew.bat :protocol-transport:jvmTest
.\gradlew.bat :protocol-session:jvmTest
.\gradlew.bat :protocol-auth:jvmTest
.\gradlew.bat :protocol-client:jvmTest
.\gradlew.bat :protocol-server:jvmTest
```

`protocol-serialization:jvmTest` includes the official codec and raw official-server session.
`protocol-client:jvmTest` includes the production client against the official server.
`protocol-server:jvmTest` includes the matching official client through the pinned headless launcher.

Use standard platform variants when deliberately validating a particular platform. Do not create filtered/layer test
tasks.

## Final gate

After JVM suites pass:

```powershell
.\gradlew.bat test
```

The root task delegates to every module's standard KMP `allTests` task and buildSrc tests. It is the only documented
complete gate. Browser-driver tests and GUI official-client tests are excluded.

## Cache checks

Version-dependent task inputs include the selected release and verified artifact content. A second unchanged invocation
must report artifact preparation and generation tasks `UP-TO-DATE` or `FROM-CACHE`. When changing task inputs/outputs,
forward-test configuration-cache storage and an unchanged rerun.

All server/client libraries, assets, reports, worlds, logs, and generated source remain under `build/`. Gradle never
reads or writes `temp/` or `.agents/skills`.
