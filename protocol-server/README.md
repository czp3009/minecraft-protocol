# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

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

The preset `negotiate` extension supports Status or Login, synchronizes `ProtocolDataSet`, installs the negotiated
registry context, and returns a Play-ready connection. Applications can instead write their own complete negotiation
with `incoming`, `outgoing`, `awaitState`, `installRegistryContext`, and `activateExtensionRoutes`.

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

Fabric and Forge equivalents are in [`protocol-session`](../protocol-session/README.md). All three compose caller packet
codecs and activate only negotiated Configuration/Play routes; custom Login queries and unknown mod payloads remain
available through the same public packet channels.

## Authentication

Offline mode is the default. It derives the vanilla offline UUID and performs no Session Server I/O or stream
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

The server validates Encryption Response, enables encryption at the official boundary, and uses the Session Server for
`/hasJoined`. Authentication failure never downgrades to offline mode. The application owns the `HttpClient` and its
timeout, retry, engine, and lifetime policies.

## Application policy

This module does not read `server.properties` or implement game services. `MinecraftServerNegotiationOptions` owns
protocol-visible defaults and `MinecraftServerNegotiationPolicy` supplies status, admission, Play Login, optional
Configuration packets/tasks, and an unknown-query decision; whitelists, operators, rate limits, permissions, worlds,
ticking, and management services remain application responsibilities. The library never sends a disconnect merely
because negotiation, encoding, or decoding failed; rejection exceptions expose a ready-to-send failure packet that the
caller chooses to send.

## Initial world projection

`MinecraftInitialWorld` projects a finite initial chunk/entity view; it is not an authoritative world or game loop. Use
its registry-aware snapshot overloads so block-state, biome, and entity-type IDs come from the installed
`ProtocolRegistryContext`. Once preset negotiation reaches Play, one call sends the stateless bootstrap a client needs
to place the player and accept chunks and entities:

```kotlin
val world = MinecraftInitialWorld.flatVanilla(
    options = options,
    chunkRadius = 0,
)

val synchronization = connection.synchronizeInitialWorld(world)
```
