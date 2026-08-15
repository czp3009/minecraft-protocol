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
                }
            }
        }
    }
}
```

The application owns the accept loop, concurrency, player state, later Play packets, persistence, and gameplay.
`MinecraftInitialWorld` projects a finite initial chunk/entity view; it is not an authoritative world or game loop.

## Authentication

Offline mode is the default. It derives the vanilla offline UUID and does not perform RSA, Session Server I/O, or stream
encryption:

```kotlin
val configuration = MinecraftServerConfiguration(
    authentication = MinecraftServerAuthentication.Offline,
)
```

Online mode receives a caller-owned `HttpClient`:

```kotlin
val authentication = MinecraftServerAuthentication.online(
    sessionHttpClient = applicationHttpClient,
)

val configuration = MinecraftServerConfiguration(
    authentication = authentication,
    preventProxyConnections = true,
)
```

The suspend factory creates one reusable RSA-1024 key pair; every connection receives a fresh verify token and shared
secret. The server validates Encryption Response, enables `protocol-transport` encryption at the official boundary, and
internally uses `MinecraftSessionApi` to call `/hasJoined`. With `preventProxyConnections`, it includes the
caller-supplied observed client address. Authentication failure is terminal and never downgrades the connection to
offline mode.

Callers that need explicit key lifetime control can construct
`MinecraftServerAuthentication.Online(sessionHttpClient, keyPair)` with a separately generated or DER-imported
`MinecraftServerKeyPair`.

The application creates, configures, and closes the `HttpClient`; this module does not own timeout, retry, engine, or
account-login policy. It depends on `protocol-auth`, not `account-auth`.

## Application configuration

This module does not read `server.properties`. `MinecraftServerConfiguration` owns protocol-visible choices and
`MinecraftServerHandler` supplies application decisions such as status, admission, Play Login, and optional ordered
Configuration exchanges. Whitelists, operators, rate limits, permissions, world generation, ticking, watchdogs, Query,
RCON, JMX, and management services remain outside the library.
