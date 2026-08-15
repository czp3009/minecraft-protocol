# protocol-client

This module owns client-side Status, Login, Configuration, and Play-entry orchestration over `MinecraftSession`. It
exposes the connected Ktor socket and delegates packet bytes, state, authentication primitives, and vanilla data to
their owning modules.

The high-level Login path handles cookies and plugins, compression, online-mode encryption, client information, Known
Packs, Configuration keepalives, and active chunk/biome decode context. Extension hooks answer server-specific requests
without changing the core state machine.

High-level online Login receives a caller-owned `HttpClient` and constructs the stateless `MinecraftSessionApi`
internally. The caller configures and closes the client; this module owns when `/join` occurs.

Scripted peers exercise local branches in `commonTest`. The production-client scenario against the exact official
offline server also lives in `commonTest` under the `fixturetest` package; it requests a remote server and reads status
and logs only through `minecraft-test-support`. Its all-default configuration automatically clones the stopped server
template. Live account authentication is not a deterministic test dependency.

The published TCP client targets JVM, Android, supported Native platforms, JS Node, and WasmJS Node. Browser, D8, and
Wasm/WASI are not TCP targets.

Run `:protocol-client:jvmTest` after changes.
