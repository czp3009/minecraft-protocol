# Gradle workflow

Run commands from the repository root. On Windows use `.\gradlew.bat`; on Unix-like systems use `./gradlew`.

## Version and generation

```powershell
.\gradlew.bat -q minecraftVersion
.\gradlew.bat refreshProtocolSpecification
```

The first command prints only the buildSrc-selected release. The refresh command downloads/verifies the official server,
runs official reports, captures both Configuration Known Packs branches, generates an expected specification under
`build/`, then replaces `protocol-specification/generated`. It never reads or writes the handwritten
`protocol-specification/README.md`.

Compilation automatically invokes only the deterministic prerequisites it needs:

- `protocol-model` generates `MinecraftProtocol.kt`, while KSP derives packet definitions and data-component dispatch
  from model annotations and validates packet coverage against the official report;
- `protocol-vanilla-data` generates static and Configuration payload sources.

Those tasks are internal implementation details and remain ungrouped. Generated Kotlin lives under module
`build/generated` and is included in source JARs. Each task validates its own downloads and outputs; do not create
separate verification tasks, generator snapshot tests, or buildSrc unit tests. Use KSP for source-to-source generation
and cacheable `buildSrc` task types for generation driven by non-source inputs.

Root `clean` removes build directories but preserves `protocol-specification`, including its handwritten overview and
checked-in generated evidence. Normal compilation and tests never read that directory; only the explicit refresh Sync
task writes `protocol-specification/generated`.

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
`protocol-server:jvmTest` includes the matching official client through the pinned headless launcher. These tests call
the ordinary `minecraft-test-support` JVM library to acquire and verify test-only artifacts at runtime; they do not
depend on Gradle preparation tasks, helper CLIs, or system-property wiring.

Use standard platform variants when deliberately validating a particular platform. Do not create filtered/layer test
tasks.

## Final gate

After JVM suites pass:

```powershell
.\gradlew.bat allTests
```

This is Gradle's standard task selector over every module's KMP `allTests` task; the root build does not define a
replacement `test` task. Browser-driver tests and GUI official-client tests are excluded.

## Cache checks

Version-dependent task inputs include the selected release and verified artifact content. A second unchanged invocation
must report artifact preparation and generation tasks `UP-TO-DATE` or `FROM-CACHE`. When changing task inputs/outputs,
forward-test configuration-cache storage and an unchanged rerun.

`MinecraftTarget.version` is the sole manually selected, target-dependent domain variable. Do not add application-level
freshness flags or comparisons; Gradle still tracks task implementation, source annotations, declared files, and task
dependency provenance normally.

All server/client libraries, assets, reports, worlds, logs, and generated source remain under `build/`. Gradle never
reads or writes `temp/` or `.agents/skills`.
