# Minecraft test support

This unpublished Kotlin Multiplatform module is the only official-peer fixture dependency used by subproject tests. It
contains the `MinecraftTestSupportService` RPC contract, its test-process Ktor WebSocket JSON client, and serializable
server/client resource values; it contains no process launcher or official-artifact implementation.

Tests in ordinary source sets call `MinecraftTestSupport.newOfficialServer()` or `newOfficialClient(...)`. Gradle
supplies the Fixture Host connection to supported standard JVM, Android host, Native, JS, and Wasm/JS test tasks.
Wasm/WASI has no network transport and does not run official-peer tests.

`MinecraftTestSupport` lazily creates at most one `MinecraftTestSupportServiceClient` at a time, reads its RPC URL and
task-owner ID from the Gradle-provided environment, and delegates directly to the generated RPC proxy. Creation returns
serializable typed resource values that subsequent support methods accept directly. Close requests are idempotent and
non-blocking; `use` provides structured cleanup and releases the client transport so Node test processes can exit. A
later operation reconnects lazily. Codec success returns no report, while failures carry diagnostics through the RPC
exception. Official paths, process objects, and host file paths never cross the protocol. World-I/O tests transfer
snapshot contents through RPC and work in a local temporary sandbox owned by the test process.
