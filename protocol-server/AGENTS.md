# protocol-server

This module owns server-side socket acceptance and protocol orchestration. It negotiates Status or Login, supports
offline and injected online authentication, synchronizes `ProtocolDataSet`, enters Play, and returns control to the
application. Initial-world APIs project finite chunks and entity snapshots; gameplay remains outside the module.

## Application boundary

Per-connection state belongs to `MinecraftServerConnection` and `MinecraftServerProtocol`. Application concurrency,
players, worlds, persistence, and gameplay remain caller-owned.

The module does not read `server.properties`. Protocol-visible choices belong in `MinecraftServerConfiguration`, and
application decisions belong in `MinecraftServerHandler`. Fire-and-forget Configuration traffic uses
`configurationPackets`; response-gated exchanges use ordered `configurationTasks`. Each task validates packet state and
direction and continues dispatching client responses until the task and Finish Configuration are acknowledged.

Do not hardcode difficulty, game mode, abilities, Status behavior, transfer admission, resource-pack policy, or
secure-chat claims.

## Tests

Portable in-process client/server behavior belongs in `commonTest`. The matching external official-client scenario also
belongs in `commonTest` under the `fixturetest` package and uses dummy offline credentials through
`minecraft-test-support`; the Fixture Host owns the exact prepared HeadlessMC wrapper, Fabric profile, upstream
HMC-Specifics mod, Mojang client, process, files, and logs. Default optional client settings automatically clone the
title-ready template. The scenario verifies initial world acceptance, client acknowledgements and ticks, and
bidirectional Play traffic from packets observed by this server; HMC text is control/readiness evidence, not a Play
oracle. GUI client and account-backed tests are outside the repository gate.

The published TCP server targets JVM, Android, supported Native platforms, JS Node, and WasmJS Node. Browser, D8, and
Wasm/WASI are not TCP targets.

Run `:protocol-server:jvmTest` after changes.
