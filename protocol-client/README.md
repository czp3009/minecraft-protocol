# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

The TCP API targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. Browser, D8, and
Wasm/WASI variants are not published for this socket-owning module.

`MinecraftClientConnection` connects through Ktor TCP and retains the underlying socket, transport, and typed session.
Each connection performs one Status or Login handshake.

```kotlin
SelectorManager(Dispatchers.Default).use { selector ->
    MinecraftClientConnection.connect(
        selectorManager = selector,
        host = "127.0.0.1",
    ).use { connection ->
        val status = connection.protocol.queryStatus()
    }
}
```

The protocol supports Status, offline or online Login, Configuration, extensible cookie/plugin/Configuration hooks,
negotiated registry context, and a typed Play-ready result while retaining the live `MinecraftSession`.

## Login identities

Identities come from `protocol-auth`. Offline Login needs no HTTP API:

```kotlin
val result = connection.protocol.login(
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
val result = connection.protocol.login(
    identity = identity,
    sessionHttpClient = applicationHttpClient,
)
```

How a launcher obtains, stores, or transfers those values is outside this module. `protocol-client` does not depend on
`account-auth`.

When the server sends Encryption Request, the client creates the shared secret and response. If
`shouldAuthenticate` is true, it requires an online identity and the supplied `HttpClient`, internally uses
`MinecraftSessionApi` to complete `/join`, sends Encryption Response, and enables the transport cipher at the official
boundary. It never silently downgrades to offline authentication. When `shouldAuthenticate` is false, either identity
can still complete encrypted Login without a Session Server call or HTTP client.

The application creates, configures, and closes the `HttpClient`; this library does not impose timeout, retry, or engine
policy.
