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

## Authentication mode

Offline mode is the default and performs no RSA generation, session-service request, or encryption:

```kotlin
val configuration = MinecraftServerConfiguration(
  authentication = MinecraftServerAuthentication.Offline,
)
```

Online mode uses a caller-owned HTTP client and the platform cryptography selected by `protocol-auth`:

```kotlin
val httpClient = applicationHttpClient
val sessionService = MinecraftSessionService(httpClient)
val authentication = MinecraftServerAuthentication.online(sessionService)

val configuration = MinecraftServerConfiguration(
  authentication = authentication,
  preventProxyConnections = true,
)
```

The suspend factory generates one RSA-1024 key pair for the online configuration; each connection gets a fresh verify
token. The server validates Encryption Response, enables the existing transport stream cipher, and then calls
`hasJoined`. `preventProxyConnections` includes the observed client IP in that verification. Authentication failure is
terminal and never downgrades the player to offline mode. The application creates and closes the `HttpClient` and owns
every engine and client configuration choice.
