# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

The TCP API targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. Browser, D8, and
Wasm/WASI variants are not published for this socket-owning module.

`MinecraftServer` binds a Ktor TCP socket. Each accepted `MinecraftServerConnection` negotiates Status or Login,
synchronizes vanilla Configuration data, and returns either a completed Status exchange or a Play-ready connection.

```kotlin
SelectorManager(Dispatchers.Default).use { selector ->
    MinecraftServer.bind(selectorManager = selector).use { server ->
        server.accept().use { connection ->
            when (val result = connection.negotiate()) {
                MinecraftServerNegotiationResult.StatusCompleted -> Unit
                is MinecraftServerNegotiationResult.PlayReady -> {
                  val playerProfile = result.profile
                  val playSession = connection.session
                  // Use playerProfile while processing Play packets through playSession.
                }
            }
        }
    }
}
```

The application owns the accept loop, connection concurrency, player state, subsequent Play packets, persistence, and
gameplay. `MinecraftInitialWorld` can project a finite flat set of chunks and initial entity snapshots; it is a
bootstrap view, not an authoritative world or game loop.

## Application configuration

This module does not read `server.properties`. Applications map their configuration into these APIs:

- `MinecraftServer.bind` selects the bind address and port.
- `MinecraftServerConfiguration` selects authentication, compression, Status and transfer behavior, distances, player
  metadata, game mode, difficulty, and the protocol-visible secure-chat claim.
- `MinecraftServerHandler` supplies status JSON, profile admission, Play Login, optional Configuration packets, and
  ordered response-gated Configuration tasks.
- `MinecraftInitialWorld` supplies difficulty, abilities, chunks, and entities for initial synchronization.

A negative `network-compression-threshold` maps to `compressionThreshold = null`; non-negative values map directly.
`enforcesSecureChat` is valid only when the application actually validates secure profiles and signed chat.

Operational settings such as whitelist and operator data, rate and idle limits, permissions, world generation, ticking,
watchdogs, spawn protection, Query, RCON, JMX, and management services remain outside this library.
