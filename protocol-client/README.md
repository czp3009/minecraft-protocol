# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

The TCP API targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. Browser, D8, and
Wasm/WASI variants are not published for this socket-owning module.

`MinecraftClientConnection` connects through Ktor TCP and retains the underlying `Socket`, transport, and typed session.
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

The protocol supports:

- Status request and ping;
- offline Login and Configuration;
- online Login when supplied a verified account and session service;
- extensible cookie, plugin, Known Packs, and Configuration packet hooks;
- automatic chunk/biome decode context derived from synchronized registries;
- a typed Play-ready result while retaining the live `MinecraftSession`.

Open a fresh connection and call `connection.protocol.login(MinecraftOfflineIdentity("Player"))` for offline Login.
Online Login does not require a caller-supplied cryptography provider. Obtain a `MinecraftOnlineAccount` from
`protocol-auth`, then pass the same caller-owned HTTP client to the account and session services:

```kotlin
val httpClient = applicationHttpClient
val oauth = MicrosoftOAuthService(httpClient, applicationRegistration)
val accounts = MinecraftAccountService(httpClient)
val sessions = MinecraftSessionService(httpClient)

val authorization = oauth.beginDeviceCodeLogin()
applicationUi.show(authorization)
val microsoftTokens = oauth.awaitDeviceCodeLogin(authorization)
val login = accounts.loginWithMicrosoftTokens(microsoftTokens)

val identity = MinecraftOnlineIdentity(login.account, sessions)
val result = connection.protocol.login(identity)
```

Applications using their own broker or launcher can instead call
`MinecraftOnlineAccount.fromExistingCredentials(...)`. The account keeps its Minecraft access token opaque and redacted.
The caller creates, configures, and closes the `HttpClient`.

When the server requests encryption, the client computes the signed server hash, completes `/join`, sends Encryption
Response, and only then enables the existing transport stream cipher. A rejected online login never falls back to an
offline identity.
