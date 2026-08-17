# protocol-auth

Authentication capabilities used by a Minecraft game client or server during connection setup:

- sealed online and offline identities;
- Minecraft Session Server `/join` and `/hasJoined` calls;
- the signed SHA-1 server hash;
- Minecraft Login RSA challenge/response and shared-secret generation.

Microsoft OAuth, Xbox authentication, and Minecraft Services account login are independent HTTP APIs in
[`account-auth`](../account-auth/README.md); neither module depends on the other. The shared secret produced here is
only key material—[`protocol-transport`](../protocol-transport/README.md) performs the continuous stream encryption and
the connection modules apply it at the correct wire boundary. Optional extensions adapt identities and Session profiles
to `protocol-model` types when that module is also on the classpath.

## Identities

`MinecraftIdentity` is a sealed interface so downstream connection code can exhaustively distinguish offline and online
identities:

```kotlin
val offline: MinecraftIdentity = MinecraftOfflineIdentity("Player")

val online: MinecraftIdentity = MinecraftOnlineIdentity(
    id = profileId,
    name = profileName,
    accessToken = minecraftAccessToken,
)
```

The vanilla offline UUID helper is public through the offline identity type rather than as a global function:

```kotlin
val id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player")
```

Identity types are ordinary data classes. Credential logging and storage are caller responsibilities.
`MinecraftOfflineIdentity.toGameProfile()` is an optional `protocol-model` extension.

## Minecraft Session Server

`MinecraftSessionApi` is stateless apart from its reference to a caller-owned `HttpClient`. It does not install an
engine, alter client configuration, close the client, retry, or refresh credentials:

```kotlin
val sessions = MinecraftSessionApi(applicationHttpClient)

sessions.join(
    MinecraftSessionJoinRequest(
        accessToken = minecraftAccessToken,
        selectedProfile = profileId.toHexString(),
        serverId = serverHash.value,
    ),
)

val joined = sessions.hasJoined(
    MinecraftSessionHasJoinedRequest(
        username = playerName,
        serverId = serverHash.value,
        ip = observedClientAddress,
    ),
)
```

An extension connects the online identity model to the low-level endpoint:

```kotlin
sessions.join(onlineIdentity, serverHash)
```

`hasJoined` returns `null` for the documented `204 No Content` unverified-player response and otherwise decodes the
profile response directly. Other HTTP failures throw `MinecraftSessionResponseException`, which exposes the raw body and
the decoded service error.

## Login key exchange

Client-side response creation is explicit:

```kotlin
val exchange = MinecraftClientKeyExchange.respond(encryptionRequest)

if (encryptionRequest.shouldAuthenticate) {
    sessions.join(onlineIdentity, exchange.serverHash)
}

send(exchange.toEncryptionResponsePacket())
val secret = exchange.sharedSecret
try {
    session.enableEncryption(secret)
} finally {
    secret.fill(0)
}
```

Server-side key-pair and per-connection challenge creation are separate operations:

```kotlin
val keyPair = MinecraftServerKeyPair.generate()
val challenge = keyPair.createChallenge(shouldAuthenticate = true)

send(challenge.toEncryptionRequestPacket())
val exchange = challenge.accept(receiveEncryptionResponse())

val secret = exchange.sharedSecret
try {
    session.enableEncryption(secret)
    val profile = sessions.hasJoined(
        username = loginName,
        serverId = exchange.serverHash,
        ip = observedClientAddress,
    )?.toGameProfile(loginName)
} finally {
    secret.fill(0)
}
```

Instead of generating a key pair, callers may construct `MinecraftServerKeyPair` from DER-encoded public and private
keys. The server key-pair object is intentionally opaque because it owns private-key material; response and result
models are data classes. Backend cryptography failures use `MinecraftCryptographyException`; coroutine cancellation
propagates as `CancellationException` instead of being wrapped as a cryptography failure.
