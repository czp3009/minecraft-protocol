# protocol-server

This module owns server-side socket acceptance and protocol orchestration. It negotiates Status or Login, supports
offline and caller-configured online authentication, synchronizes `ProtocolDataSet`, enters Play, and returns control to
the application. Initial-world APIs separate the fixed Play bootstrap from optional finite Chunk and Entity snapshots;
gameplay remains outside the module.

The module may depend on filesystem-independent `world-format` to project strong semantic Chunks into initial-world
snapshots and clientbound Chunk packets. It never depends on `world-io` or opens world files; applications compose that
module when their initial view comes from disk. Palette packing and light projection stay stateless, use the installed
registry context, and require caller-owned block semantics that registry IDs cannot express.

## Application boundary

Per-connection state belongs to `MinecraftServerConnection` and the negotiation extension. Application concurrency,
players, worlds, persistence, and gameplay remain caller-owned. `accept` returns a raw channel-first connection and does
not install callbacks or automatically begin negotiation.

Online authentication receives a caller-owned `HttpClient` and constructs the stateless `MinecraftSessionApi`
internally. The caller configures and closes the client; this module owns when `/hasJoined` occurs.

The module does not read `server.properties`. Protocol-visible choices belong in
`MinecraftServerNegotiationOptions`, and application decisions belong in `MinecraftServerNegotiationPolicy`.
Fire-and-forget Configuration traffic uses `configurationPackets`; response-gated exchanges use ordered
`configurationTasks`. Policy packet lists are caller-owned extension traffic and are not rescanned for framework-owned
packet types; each task continues dispatching client responses until the task and Finish Configuration are acknowledged.

The caller may share one immutable `MinecraftConnectionDefinition`, loader profile definition, static registry schema,
and resolved registry context across all connections. The library does not clone large immutable registries per client.
Negotiation, codec, and state failures propagate; never add automatic disconnect or loader-failure replies.

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
