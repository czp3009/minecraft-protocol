# protocol-server

This module owns server-side connection acceptance and protocol orchestration. Its public API exposes Ktor
`ServerSocket` and accepted `Socket` values.

The default protocol negotiates Status or Login, supports offline and injected online authentication, synchronizes
`ProtocolDataSet`, enters Play, and then returns control to the application. Initial-world APIs project finite chunk and
entity snapshots; the module contains no gameplay loop.

Keep per-connection state in `MinecraftServerConnection` and
`MinecraftServerProtocol`. Applications own concurrency, persistence, worlds, entities, and player behavior.

JVM unit and integration tests remain in process and display-free. The headless environment test launches the matching
official client prepared under the root project's `build/` tree with dummy offline credentials and a hash-verified
launcher adapter. It must prove initial chunks and entities are accepted, observe client acknowledgements and ticks,
complete a bidirectional Play packet, keep all runtime files under Gradle's build directory, and avoid launcher account
credentials. Direct desktop launch remains an additional acceptance path.
