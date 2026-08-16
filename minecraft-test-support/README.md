# Minecraft test support

This unpublished Kotlin Multiplatform module is the only official-peer fixture dependency used by subproject tests. It
contains the `MinecraftTestSupportService` RPC contract, its test-process Ktor WebSocket JSON client, and serializable
server/client resource values; it contains no process launcher or official-artifact implementation. Process ownership
and official artifacts live in [`minecraft-test-fixture-host`](../minecraft-test-fixture-host/README.md).

## Creating fixtures

Tests in ordinary source sets call `MinecraftTestSupport.newOfficialServer()` or `newHeadlessClient(...)` and receive
serializable typed resource values that subsequent support methods accept directly; `use` provides structured cleanup.
Gradle supplies the Fixture Host connection to supported standard JVM, Android host, Native, JS Node, and WasmJS Node
test tasks. JS Browser, WasmJS Browser, WasmJS D8, and Wasm/WASI do not run fixture entries; the private Wasm/WASI
target is a compile scaffold only.

Creation chooses workspace reuse automatically. A completely default server configuration clones the stopped official
server template; changing any server field starts from the prepared runtime without the template world. The headless
client's required offline player name is not an optional customization, so any name with default startup and stop limits
clones the stopped client template. Changing either optional limit uses a fresh game directory from the prepared
runtime. There is no public template/fresh policy switch.

## Headless client lifecycle

`newHeadlessClient` returns after HMC-Specifics has initialized and a correlated GUI query observes the title screen. It
does not connect. `connectHeadlessClient` and `disconnectHeadlessClient` control the explicit connection lifecycle;
`sendHeadlessClientCommand` exposes the narrow audited action surface. Play is established by packet observations in the
consuming protocol scenario rather than HeadlessMC text.

## RPC client behavior

`MinecraftTestSupport` lazily creates at most one `MinecraftTestSupportServiceClient` at a time, reads its RPC URL and
task-owner ID from the Gradle-provided environment, and delegates directly to the generated RPC proxy. Close requests
are idempotent and non-blocking; `use` provides structured cleanup and releases the client transport so Node test
processes can exit. A later operation reconnects lazily.

Codec success returns no report, while failures carry diagnostics through the RPC exception. `closeProcess` and
`deleteWorkingDirectory` expose the two synchronous resource-lifecycle stages, `closeAndAwait` performs both, and
`close` asynchronously schedules both. Process objects and official artifact paths never cross the protocol.

`hostWorkingDirectory` is the explicitly documented backdoor that returns the Host's absolute working-directory path to
same-filesystem tests; `world-io` uses it only after the official process has exited.
