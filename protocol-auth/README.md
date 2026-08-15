# protocol-auth

`protocol-auth` contains the authentication capabilities used by a Minecraft game client or server during connection
setup:

- sealed online and offline identities;
- Minecraft Session Server `/join` and `/hasJoined` calls;
- the signed SHA-1 server hash;
- Minecraft Login RSA challenge/response and shared-secret generation.

It does not perform Microsoft OAuth, Xbox authentication, or Minecraft Services account login; those independent HTTP
APIs live in [`account-auth`](../account-auth/README.md). Neither module depends on the other.

The shared secret produced here is only key material. `protocol-transport` performs continuous AES-128/CFB8 stream
encryption, while `protocol-session`, `protocol-client`, and `protocol-server` apply it at the correct wire boundary.

The direct API uses Kotlin standard types and models owned by `protocol-auth`; it does not require `protocol-model`.
When callers also supply `protocol-model`, optional extensions adapt identities and Session profiles to `GameProfile`
and adapt Login encryption packets to the byte-oriented key-exchange API. `protocol-model` is not added transitively.

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
engine, alter client configuration, close the client, retry, or refresh credentials.

The low-level methods accept the serializable endpoint models directly:

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

An extension connects the online identity model to the low-level client endpoint:

```kotlin
sessions.join(onlineIdentity, serverHash)
```

`hasJoined` returns `null` for the documented `204 No Content` unverified-player response and otherwise decodes
`MinecraftSessionHasJoinedResponse` directly. Other HTTP failures throw `MinecraftSessionResponseException`, a Ktor
`ResponseException`; `responseBody` contains the raw body and `parsedErrorBody` contains the decoded
`MinecraftSessionErrorResponse`. Successful and error response decoding failures, transport failures, timeouts,
cancellation, and caller-plugin failures propagate unchanged.

The wire response preserves nullable `properties` and `profileActions`. The optional `toGameProfile(username)` extension
converts it to `protocol-model`, treating absent properties as an empty profile property list.

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

Instead of generating a key pair, callers may construct `MinecraftServerKeyPair` from a DER-encoded X.509
SubjectPublicKeyInfo public key and PKCS#8 private key.

The server key-pair object is intentionally opaque rather than a data class because it owns non-public private-key
material. Response/result models are data classes. Without `protocol-model`, call
`MinecraftClientKeyExchange.respond(serverId, encodedPublicKey, verifyToken)` and
`challenge.accept(encryptedSharedSecret, encryptedVerifyToken)` directly.

## Platforms

Identity, hash, Session Server, and Login key-exchange APIs are available on JVM, Android, supported Native, Kotlin/JS
Node/browser, and Kotlin/WasmJS Node/browser targets. Actual RSA providers remain internal to the module. Socket-owning
client and server modules publish only their networking-capable target subsets.
