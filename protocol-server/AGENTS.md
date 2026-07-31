# protocol-server

This module owns server-side connection acceptance and protocol orchestration. Its public API exposes Ktor
`ServerSocket` and accepted `Socket` values.

The default protocol negotiates Status or Login, supports offline and injected online authentication, synchronizes
`ProtocolDataSet`, enters Play, and then returns control to the application. Initial-world APIs project finite chunk and
entity snapshots; the module contains no gameplay loop.

Keep per-connection state in `MinecraftServerConnection` and
`MinecraftServerProtocol`. Applications own concurrency, persistence, worlds, entities, and player behavior.

Do not read `server.properties` in this module. Keep protocol-visible server choices configurable through
`MinecraftServerConfiguration`, and keep application-specific decisions in `MinecraftServerHandler`. Optional
Fire-and-forget Configuration traffic belongs in `configurationPackets`; response-gated exchanges belong in ordered
`configurationTasks`. Validate every task packet's state/direction, and continue dispatching client responses until each
task and then Finish Configuration are acknowledged. Do not hardcode difficulty, game mode, abilities, Status behavior,
transfer admission, resource-pack policy, or secure-chat claims.

JVM unit and integration tests remain in process and display-free. The standard `jvmTest` task launches the matching
official client by calling the ordinary `minecraft-test-support` library with dummy offline credentials and a
hash-verified launcher adapter. It must prove initial chunks and entities are accepted, observe client acknowledgements
and ticks, complete a bidirectional Play packet, keep all runtime files under build directories, and avoid launcher
account credentials. GUI desktop-client testing is not part of the repository.
