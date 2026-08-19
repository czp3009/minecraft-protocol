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
                connection.negotiate() ?: return@use
                for (packet in connection.incoming) {
                    handlePlayPacket(connection, packet)
                }
            }
        }
    }
}
```

The preset `negotiate` extension supports Status or Login, synchronizes `ProtocolDataSet`, installs the negotiated
registry context, and returns `MinecraftServerNegotiationResult`. A status ping is answered and closed completely before
`negotiate` returns null, so the caller has nothing left to do. A non-null result contains the exact Play Login and
application-level negotiation facts; `connection.registries` is the authoritative installed registry context. The
extension is the library's preset orchestration path over the same typed packet connection returned by `accept`.

`negotiate` runs sequentially in the calling coroutine. It neither launches a negotiation scope nor selects a
`Dispatcher`, and it exclusively borrows the connection's `incoming` and `outgoing` channels until return. The caller
must guarantee that no other coroutine reads or writes those channels while negotiation is active. There is no
negotiator lock that arbitrates competing users; packet theft, ordering failures, and other races caused by concurrent
application access are the application's responsibility.

## Writing your own negotiation

Applications can write their own `negotiate` function on the typed connection returned by `accept`. Read the maintained
[server
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/server/MinecraftServerProtocol.kt)
for the complete Status, Login, Configuration, and Play-entry ordering. It is built from the same public channels,
connection operations, authentication primitives, registry functions, and profile hooks available to callers. The
ownership contract is unchanged: one coroutine owns both packet channels for the complete sequence, and the library does
not add locks, a scope, or a dispatcher to caller code.

For vanilla offline Login, the server reads Handshake and Login Start, sends Login Success, receives Client Information
and Known Packs, then sends its registry and tag snapshot. Before Finish Configuration it resolves that exact snapshot
with `resolveSynchronizedRegistryContext`, applies the Play Login dimension with `withPlayLoginDimension`, invokes
`ServerNegotiationProfile.resolveRegistryContext`, and installs the result. The Finish acknowledgement performs the real
wire-state transition to Play; the server then sends the same Play Login value that it retains for later explicit world
synchronization.

The portable
[public-primitives end-to-end test](src/commonTest/kotlin/com/hiczp/minecraft/protocol/server/ClientToServerEndToEndTest.kt)
executes complete client and server implementations without calling either preset. It covers Status, compression,
offline Login, complete Configuration, public profile hooks and routes, active-dimension context, Play Login, chunks,
teleport/chunk acknowledgements, and a keepalive. The matching official-client suite continues to verify the preset and
the same public initial-world primitives.

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

val synchronization = connection.synchronizeInitialWorld(
    world = world,
    login = ready.playLogin,
)
```

`login` is explicit state, not a hidden connection marker. An application-defined negotiation passes the exact
`PlayLoginPacket` it sent; preset callers pass `MinecraftServerNegotiationResult.playLogin`. Reconfiguration and respawn
likewise pass the currently active Play Login, so synchronization never guesses from stale connection history.
