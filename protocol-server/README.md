# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

The TCP API targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. Browser, D8, and
Wasm/WASI variants are not published for this socket-owning module.

`MinecraftServer` binds a Ktor TCP listener. `accept` returns a raw typed `MinecraftServerConnection`; it does not run
negotiation or install per-connection callbacks. The application owns the accept loop and concurrency:

```kotlin
MinecraftServer.bind(selectorManager = selector).use { server ->
    while (server.isOpen) {
        val connection = server.accept()
        launch {
            connection.use {
                when (val result = connection.negotiate()) {
                    MinecraftServerNegotiationResult.StatusCompleted -> Unit
                    is MinecraftServerNegotiationResult.PlayReady -> {
                        for (packet in connection.incoming) {
                            handlePlayPacket(connection, packet)
                        }
                    }
                }
            }
        }
    }
}
```

The preset supports Status or Login, synchronizes `ProtocolDataSet`, installs the negotiated registry context, and
returns a Play-ready connection. It exclusively borrows the channels until it returns and uses only public connection
primitives. Applications can instead write their own complete negotiation with `incoming`, `outgoing`, `awaitState`,
`installRegistryContext`, and `activateExtensionRoutes`.

Status is terminal: after sending Pong, the preset closes `outgoing` and waits for its accepted packets to drain before
returning `StatusCompleted`. Login returns with the connection open in Play.

## Shared definitions and modded profiles

Build immutable protocol data at the application lifetime you need, then pass references into a shareable connection and
profile definition. `MinecraftServer` reuses its `MinecraftConnectionDefinition` for every accepted connection:

```kotlin
val connectionDefinition = NeoForgeProtocol.connectionDefinition(
    extensionCodecs = myModPacketCodecs,
    registries = myResolvedRegistryContext,
)
val profileDefinition = NeoForgeServerProfileDefinition(
    network = myNetworkConfiguration,
    frozenRegistries = myFrozenRegistrySync,
    resolvedRegistryContext = myResolvedRegistryContext,
)
val server = MinecraftServer.bind(
    selectorManager = selector,
    definition = connectionDefinition,
)
```

Create only the small mutable profile state per connection:

```kotlin
val result = connection.negotiate(
    profile = NeoForgeServerProfile(profileDefinition),
    options = serverOptions,
    policy = applicationPolicy,
)
```

Fabric and Forge equivalents are in `protocol-session`. Fabric profiles accept shareable registry snapshots/contexts
directly; Forge uses `ForgeServerProfileDefinition`. All three compose caller packet codecs and activate only negotiated
Configuration/Play routes. Custom Login queries and unknown mod payloads remain available through the same public packet
channels.

## Authentication

Offline mode is the default. It derives the vanilla offline UUID and performs no RSA, Session Server I/O, or stream
encryption. Online mode receives a caller-owned `HttpClient`:

```kotlin
val authentication = MinecraftServerAuthentication.online(
    sessionHttpClient = applicationHttpClient,
)
val server = MinecraftServer.bind(
    selectorManager = selector,
    authentication = authentication,
)
```

The suspend factory creates one reusable RSA-1024 key pair; every connection receives a fresh verify token and shared
secret. The server validates Encryption Response, enables encryption at the official boundary, and uses
`MinecraftSessionApi` for `/hasJoined`. Authentication failure never downgrades to offline mode. The application owns
the `HttpClient` and its timeout, retry, engine, and lifetime policies.

## Application policy and errors

This module does not read `server.properties`. `MinecraftServerNegotiationOptions` owns protocol-visible defaults and
`MinecraftServerNegotiationPolicy` supplies status, admission, Play Login, optional Configuration packets/tasks, and an
unknown-query decision. Whitelists, operators, rate limits, permissions, worlds, ticking, watchdogs, Query, RCON, JMX,
and management services remain outside the library.

The library never sends a disconnect or loader failure packet merely because negotiation, encoding, decoding, or state
validation failed. `MinecraftLoginRejectedException.reason` can be placed in a caller-sent `LoginDisconnectPacket` when
the state permits it; its `failurePacket` property provides that default packet without sending it. NeoForge and Forge
mismatch exceptions expose their protocol-defined `failurePacket` for the same caller-controlled choice. Malformed
wire/payload and pump failures propagate as channel close causes and through `awaitClosed`.

`MinecraftInitialWorld` projects a finite initial chunk/entity view; it is not an authoritative world or game loop. Use
the `MinecraftChunkSnapshot.flat(registries = ...)` overload for modded connections so block-state and biome IDs come
from the installed `ProtocolRegistryContext`. `MinecraftEntitySnapshot.packets(registries)` resolves entity-type IDs
from the same context. The lower-level numeric-ID overloads require every ID explicitly.
