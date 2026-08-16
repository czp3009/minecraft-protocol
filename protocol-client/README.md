# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

`MinecraftClientConnection` is a typed packet connection exposing `ReceiveChannel<ClientboundPacket>`,
`SendChannel<ServerboundPacket>`, committed protocol state, active registries, and extension routes. It does not expose
the raw socket or frame stream.

Each connection performs one Status or Login handshake:

```kotlin
SelectorManager(Dispatchers.Default).use { selector ->
    MinecraftClientConnection.connect(
        selectorManager = selector,
        host = "127.0.0.1",
    ).use { connection ->
        val status = connection.queryStatus()
    }
}
```

The preset `negotiate` extension supports offline or online Login, cookies and custom queries, compression/encryption,
Configuration, dynamic registry context, optional loader profiles, and Play entry. Afterward the application owns the
packet loop:

```kotlin
val result = connection.negotiate(MinecraftOfflineIdentity("Player"))
for (packet in connection.incoming) {
    handlePlayPacket(packet)
}
```

The preset uses only the public connection API; applications may omit it and implement the entire flow from Handshake
through Play with `incoming`, `outgoing`, `awaitState`, `installRegistryContext`, and `activateExtensionRoutes`.

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
