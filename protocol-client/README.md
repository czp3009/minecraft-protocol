# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

`MinecraftClientConnection` is a typed packet connection exposing `ReceiveChannel<ClientboundPacket>`,
`SendChannel<ServerboundPacket>`, committed protocol state, active registries, and extension routes. It does not expose
the raw socket or frame stream.

Each connection performs one Status or Login handshake. To ping a server as the multiplayer server list does, call
`queryStatus()`; it obtains the Status response and completes the Ping/Pong exchange without running `negotiate()`:

```kotlin
SelectorManager(Dispatchers.Default).use { selector ->
    MinecraftClientConnection.connect(
        selectorManager = selector,
        host = "127.0.0.1",
    ).use { connection ->
        val status = connection.queryStatus()
       val description = status.response.jsonResponse
       val echoedPingPayload = status.pong.timestamp
    }
}
```

Status cannot continue into Login. Close the Status connection after the ping and create a fresh connection before
calling `negotiate()` to join the server.

The preset `negotiate` extension supports offline or online Login, cookies and custom queries, compression/encryption,
Configuration, dynamic registry context, optional loader profiles, and Play entry. Afterward the application owns the
packet loop:

```kotlin
val result = connection.negotiate(MinecraftOfflineIdentity("Player"))
for (packet in connection.incoming) {
    handlePlayPacket(packet)
}
```

`negotiate` is a suspending function, not a background job. Its orchestration runs sequentially in the calling
coroutine, does not create a scope or select a `Dispatcher`, and exclusively borrows `incoming` and `outgoing` until it
returns. The caller must guarantee that no other coroutine reads or writes either channel during that interval. The
preset does not add a lock to arbitrate competing channel users; concurrent access is a caller error and can steal a
packet, reorder a phase, or fail the connection.

The same ownership precondition applies when an application implements negotiation itself. Keep the entire
Handshake/Login/Configuration sequence and its channel reads and writes in one coroutine; the library assumes this and
does not detect or repair races created by application code. After negotiation hands the Play connection back, the
application may establish whatever packet-loop ownership model it needs.

## Writing your own negotiation

Applications can write their own `negotiate` function instead of calling the preset. Read the maintained
[client
`negotiate` implementation](src/commonMain/kotlin/com/hiczp/minecraft/protocol/client/MinecraftClientProtocol.kt)
for the complete packet ordering and optional branches. It uses only the public connection API: `incoming`, `outgoing`,
`awaitState`, `installRegistryContext`, `activateExtensionRoutes`, authentication primitives, and profile hooks. A
vanilla offline implementation has these registry-sensitive steps:

1. Send Handshake and Login Start, handle Login packets, send Login Acknowledged, and await Configuration.
2. Send Client Information; collect Known Packs, Feature Flags, every Registry Data packet, and tags.
3. Resolve the Configuration context with `resolveSynchronizedRegistryContext`, pass it through
   `ClientNegotiationProfile.resolveRegistryContext`, install it, call `preparePlay`, then acknowledge Finish
   Configuration.
4. Receive Play Login, derive the active dimension with `withPlayLoginDimension`, install the result, and only then
   continue decoding Play packets such as chunks.

The portable
[public-primitives end-to-end test](../protocol-server/src/commonTest/kotlin/com/hiczp/minecraft/protocol/server/ClientToServerEndToEndTest.kt)
executes complete client and server implementations without either preset, including compression, a fake profile,
initial chunks, acknowledgements, and a Play keepalive. The
[official-server client scenario](src/commonTest/kotlin/com/hiczp/minecraft/protocol/client/OfficialServerClientScenario.kt)
also runs the public client sequence against the matching official server. These tests verify the production ordering
reference; reaching `ConnectionState.PLAY` alone is not treated as completed Play initialization.

## Custom protocols and loader profiles

Declare every possible custom packet before connecting through a shareable `MinecraftConnectionDefinition`:

```kotlin
val definition = FabricProtocol.connectionDefinition(
    extensionCodecs = myModPacketCodecs,
)
val connection = MinecraftClientConnection.connect(
    selectorManager = selector,
    host = host,
    definition = definition,
)
val result = connection.negotiate(
    identity = identity,
    profile = FabricClientProfile(myModdedStaticRegistrySchema),
)
```

Equivalent NeoForge and Forge APIs live in [`protocol-session`](../protocol-session/README.md). An unregistered query or
payload reaches the application or profile as `UnknownPacket.Clientbound`; during preset negotiation,
`MinecraftClientNegotiationOptions.onUnhandledQuery` can return an explicit response or rejection.

## Login identities

Identities come from [`protocol-auth`](../protocol-auth/README.md). Offline Login needs no HTTP API:

```kotlin
val result = connection.negotiate(MinecraftOfflineIdentity("Player"))
```

Online Login receives account data already available to the game process plus a caller-owned HTTP client:

```kotlin
val identity = MinecraftOnlineIdentity(
    id = profileId,
    name = profileName,
    accessToken = minecraftAccessToken,
)
val result = connection.negotiate(
    identity = identity,
    sessionHttpClient = applicationHttpClient,
)
```

How a launcher obtains, stores, or transfers those values is outside this module. When the server sends Encryption
Request, the client performs the Session Server `/join` call and enables encryption at the official boundary; it never
silently downgrades authentication, and it imposes no timeout, retry, or engine policy on the caller-owned client.
Malformed frames and known packet bodies, encoding failures, and invalid packet ordering close the channel with the
original cause instead of being swallowed or converted to automatic replies.
