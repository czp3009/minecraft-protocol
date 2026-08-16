# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

The TCP API targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. Browser, D8, and
Wasm/WASI variants are not published for this socket-owning module.

`MinecraftClientConnection` is a typed packet connection. It exposes
`ReceiveChannel<ClientboundPacket>`, `SendChannel<ServerboundPacket>`, committed protocol state, active registries and
extension routes, but does not expose the raw socket, frame stream, or mutable low-level session.

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

The preset `negotiate` extension exclusively borrows `incoming` and `outgoing` until it returns. It supports offline or
online Login, cookies and custom queries, compression/encryption, Configuration, dynamic registry context, optional
loader profiles, and Play entry. Afterward the application owns the packet loop:

```kotlin
val result = connection.negotiate(identity)
for (packet in connection.incoming) {
    handlePlayPacket(packet)
}
```

The preset uses only the public connection API. Applications may omit it and implement the entire flow from Handshake
through Play with `incoming.receive`, `outgoing.send`, `awaitState`, `installRegistryContext`, and
`activateExtensionRoutes`.

## Custom protocols and loader profiles

Declare every possible custom packet before connecting. A shareable `MinecraftConnectionDefinition` retains its packet
registry and initial registry context by reference:

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
    options = MinecraftClientNegotiationOptions(
        staticRegistries = myModdedStaticRegistrySchema,
    ),
)
```

If the modpack adds Configuration-backed dimension types or Known Packs, provide its `ProtocolDataSet` in the same
options; it becomes the source for local compact registry data and active-dimension layout instead of falling back to
vanilla data.

Equivalent `NeoForgeProtocol`/`NeoForgeClientProfileDefinition`/`NeoForgeClientProfile` and
`ForgeProtocol`/`ForgeClientProfileDefinition`/`ForgeClientProfile` APIs live in `protocol-session`. Profiles are
one-connection state machines; reusable definitions, static schemas, codec lists, and immutable snapshots may be shared.

An unregistered query or payload reaches the application/profile as `UnknownPacket.Clientbound`. During preset
negotiation, `MinecraftClientNegotiationOptions.onUnhandledQuery` can return an explicit response or rejection; the
library does not invent one. Registered codecs lift custom bodies directly to the application packet subtype.

## Login identities

Identities come from `protocol-auth`. Offline Login needs no HTTP API:

```kotlin
val result = connection.negotiate(
    MinecraftOfflineIdentity("Player"),
)
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

How a launcher obtains, stores, or transfers those values is outside this module. `protocol-client` does not depend on
`account-auth`.

When the server sends Encryption Request, the client creates the shared secret and response. If
`shouldAuthenticate` is true, it requires an online identity and supplied `HttpClient`, uses `MinecraftSessionApi` for
`/join`, sends Encryption Response, and enables the transport cipher at the official boundary. It never silently
downgrades authentication. The application configures and closes the `HttpClient`; this module imposes no timeout,
retry, or engine policy.

## Failures

Malformed frames and known packet bodies, encoding failures, invalid packet ordering, and state-machine failures are not
swallowed or converted to automatic replies. They close the channel with the original cause, observable from channel
operations or `awaitClosed`. Preset policy/negotiation exceptions are thrown directly in the calling coroutine.
