# Minecraft test support

This unpublished Kotlin Multiplatform module is the only official-peer fixture dependency used by subproject tests. It
contains the kotlinx.rpc contract, Ktor WebSocket JSON client, serializable models, and remote server/client handles; it
contains no process launcher or official-artifact implementation.

Tests in ordinary source sets call `MinecraftTestSupport.newOfficialServer()` or `newOfficialClient(...)`. Gradle
supplies the Fixture Host connection to supported standard JVM, Android host, Native, JS, and Wasm/JS test tasks.
Wasm/WASI has no network transport and does not run official-peer tests.

Remote handles expose suspend operations for endpoints, status, logs, commands, events, codec verification, and
idempotent non-blocking close requests. Codec success returns no report; failures carry diagnostics through the RPC
exception. `useRemote` provides structured cleanup. Official paths, process objects, and host file paths never cross the
protocol. World-I/O tests transfer snapshot contents through RPC and work in a local temporary sandbox owned by the test
process.
