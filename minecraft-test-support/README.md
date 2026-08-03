# Minecraft test support

This private Kotlin Multiplatform library provides reusable, Testcontainers-style fixtures for the repository's standard
tests. Its external-process resources run on JVM, host desktop Native, and Node-based JS/Wasm targets. Root Gradle tasks
prepare immutable official artifacts as lazy inputs of those standard test tasks; runtime APIs validate and use only the
local files, without internet access or fixture path properties.

`MinecraftTestSupport.newOfficialServer()` and `newOfficialClient(...)` return ready `AutoCloseable` resources. Each
resource owns its process, endpoint, bounded logs, and one
`<module>/build/test-runtimes/official-{server,client}/<version>/<UUID>` directory. `.use {}` schedules idempotent
process shutdown and directory deletion on the singleton cleanup scope; process-exit hooks drain outstanding cleanup as
a best-effort fallback.

Tests that need this capability live in the ordinary `hostProcessTest` source set. The shared Gradle helper adds this
library dependency automatically and maps semantic fixture requirements to the actual standard platform test task
inputs. This module is unpublished and does not add custom interoperability runners or verification task types.
