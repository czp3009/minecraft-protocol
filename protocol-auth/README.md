# protocol-auth

Authentication capabilities used by a Minecraft game client or server during connection setup and signed Play chat:

- sealed online and offline identities;
- Minecraft Session Server `/join` and `/hasJoined` calls;
- the signed SHA-1 server hash;
- Minecraft Login RSA challenge/response and shared-secret generation;
- Minecraft Services `/player/certificates` and `/publickeys` calls;
- profile-public-key credential verification and player chat signing/verification;
- locked sender-chain signers and serverbound/clientbound chain verifiers.

Microsoft OAuth, Xbox authentication, and Minecraft Services account login are independent HTTP APIs in
[`account-auth`](../account-auth/README.md); neither module depends on the other. The shared secret produced here is
only key material—[`protocol-transport`](../protocol-transport/README.md) performs the continuous stream encryption and
the connection modules apply it at the correct wire boundary. `protocol-model` is a direct API dependency because the
authentication and signed-chat APIs naturally consume its profiles, packets, and shared wire values. Reconstructed
signature bodies, chain links, and Brigadier-derived signable arguments remain `protocol-auth` values.

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
`MinecraftOfflineIdentity.toGameProfile()` adapts the identity to a `protocol-model` profile.

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

## Profile keys

`MinecraftProfileKeyApi` uses the same caller-owned `HttpClient` pattern. It does not decide when the game requests or
refreshes a key, cache Mojang service keys, retry, or persist private material:

```kotlin
val profileKeys = MinecraftProfileKeyApi(applicationHttpClient)
val keyPair = profileKeys.fetchProfileKeyPair(onlineIdentity).toMinecraftProfileKeyPair()
val servicesKeys = profileKeys.fetchServicesPublicKeys().toMinecraftServicesPublicKeySet()

val credentialIsValid = servicesKeys.verifyProfilePublicKey(
    profileId = onlineIdentity.id,
    publicKeyData = keyPair.publicKeyData,
)
```

The official service exposes raw RSA SubjectPublicKeyInfo values in `playerCertificateKeys`; profile credentials use
`SHA1withRSA`. This is not an X.509 root-certificate chain. `MinecraftProfilePublicKey` parsing is deliberately separate
from credential trust, so callers can fetch keys elsewhere or construct their own key set. Expiry and refresh helpers
take an explicit epoch millisecond value and never read the clock implicitly.

## Signed chat

`MinecraftChatSignatures` is the stateless payload/sign/verify layer. `MinecraftChatChainSigner` adds only a locked
sender/session index. A batch—especially a signed command's arguments—is allocated contiguously and committed only when
every signature succeeds:

```kotlin
val signer = MinecraftChatChainSigner(
    sender = onlineIdentity.id,
    sessionId = chatSessionId,
    keyPair = keyPair,
)

val packet = signer.signChatMessagePacket(
    message = text,
    timestampEpochMillis = timestamp,
    salt = salt,
    lastSeen = expandedLastSeenSignatures,
    lastSeenMessages = lastSeenUpdate,
)
```

The serverbound chat and signed-command packets do not carry their chain index. The server therefore keeps one
`MinecraftServerboundChatChainVerifier` per accepted player chat session; each valid message or signed command argument
advances its implicit index:

```kotlin
val verifier = MinecraftServerboundChatChainVerifier(
    sender = playerId,
    sessionId = sessionId,
    publicKey = profilePublicKey,
)

when (val result = verifier.verify(packet, expandedLastSeenSignatures)) {
    is MinecraftChatVerificationResult.Valid -> handle(result.message)
    is MinecraftChatVerificationResult.Invalid -> handleInvalid(result.failure)
}
```

Invalid input does not mutate the verifier. A caller that wants the official server's permanently-broken-chain policy
can discard that verifier after a failure. `MinecraftClientboundChatChainVerifier` instead consumes the explicit packet
index, accepts gaps because a recipient may not receive every sender message, and accepts an exact duplicate.

Packet helpers convert chat packets to unpacked signed bodies, sign command argument lists, and build recipient-specific
`PlayerChatMessagePacket` values after the caller supplies the global index and packed last-seen signatures. The module
does not reconstruct acknowledgement updates, manage signature caches/global indices, announce sessions, enforce server
configuration, disconnect players, order separately returned sends, or broadcast.

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
