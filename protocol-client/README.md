# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

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
- online Login when supplied a session service and cryptography provider;
- extensible cookie, plugin, Known Packs, and Configuration packet hooks;
- automatic chunk/biome decode context derived from synchronized registries;
- a typed Play-ready result while retaining the live `MinecraftSession`.

Open a fresh connection and call `connection.protocol.login(MinecraftOfflineIdentity("Player"))` for offline Login.
Online Login additionally requires an application-supplied session service, access token, UUID, and cryptography
provider.
