# protocol-auth

Authentication and account-backed capabilities used by a Minecraft game client or server at runtime:

- sealed online and offline identities;
- Minecraft Session Server `/join`, `/hasJoined`, and profile calls;
- unauthenticated Java profile lookup by name;
- Minecraft Services user attributes, block list, Friends, and Presence calls;
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
identities. The online values `profileId`, `profileName`, and `minecraftAccessToken` are supplied by the launcher or
account-login layer before the game connection starts:

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
engine, alter client configuration, close the client, retry, or refresh credentials. Here `applicationHttpClient` is
that configured client; `serverHash` comes from the current Login key exchange; `playerName` and
`observedClientAddress` come from the accepted connection. The access token and profile ID were introduced in the
identity example:

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
sessions.join(online, serverHash)
```

`hasJoined` returns `null` for the documented `204 No Content` unverified-player response and otherwise decodes the
profile response directly. Other HTTP failures throw `MinecraftSessionResponseException`, which exposes the raw body and
the decoded service error.

`fetchProfile` retrieves a public Session Server profile and maps the official `requireSecure` argument to the
`unsigned` query parameter:

```kotlin
val profile = sessions.fetchProfile(profileId, requireSecure = true)?.toGameProfile()
```

## Profiles and game-user services

`MinecraftProfileLookupApi` performs unauthenticated single-name or explicit bulk profile lookups. A bulk call performs
one request; the caller owns batching and retry policy.

`MinecraftUserApi` uses a Minecraft access token to retrieve user privileges, bans, chat and friend preferences, and the
privacy block list. It also exposes the official attribute update request used for friend and profanity-filter
preferences.

`MinecraftFriendsApi` retrieves and updates friends and submits the local Presence status. Conditional friend and
Presence calls return `MinecraftConditionalResponse`, which preserves `304 Not Modified`, `ETag`, and `Retry-After` for
caller-owned polling and cache policy. None of these APIs starts a poller or retains a cache.

## Profile keys

`MinecraftProfileKeyApi` uses the same caller-owned `HttpClient` pattern. It does not decide when the game requests or
refreshes a key, cache Mojang service keys, retry, or persist private material:

```kotlin
val profileKeys = MinecraftProfileKeyApi(applicationHttpClient)
val keyPair = profileKeys.fetchProfileKeyPair(online).toMinecraftProfileKeyPair()
val servicesKeys = profileKeys.fetchServicesPublicKeys().toMinecraftServicesPublicKeySet()

val credentialIsValid = servicesKeys.verifyProfilePublicKey(
    profileId = online.id,
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
every signature succeeds. The `online` identity and `keyPair` come from the preceding examples. `chatSessionId` is the
announced chat session ID; `text`, `timestamp`, `salt`, `expandedLastSeenSignatures`, and `lastSeenUpdate` are the
message and acknowledgement state supplied by the caller's chat loop:

```kotlin
val signer = MinecraftChatChainSigner(
    sender = online.id,
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
advances its implicit index. Here `playerId`, `sessionId`, and `profilePublicKey` come from that player's accepted chat
session; `packet` and `expandedLastSeenSignatures` come from the server's packet/acknowledgement loop. `handle` and
`handleInvalid` are the application's callbacks for accepting the verified message or applying its invalid-chat policy:

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

Client-side response creation is explicit. In the example, `encryptionRequest` was received by the Login loop,
`sessions` and `online` were created above, `send` is the caller's ordered packet-send operation, and `session` is the
connection/session whose encryption boundary the caller controls:

```kotlin
val exchange = MinecraftClientKeyExchange.respond(encryptionRequest)

if (encryptionRequest.shouldAuthenticate) {
    sessions.join(online, exchange.serverHash)
}

send(exchange.toEncryptionResponsePacket())
val secret = exchange.sharedSecret
try {
    session.enableEncryption(secret)
} finally {
    secret.fill(0)
}
```

Server-side key-pair and per-connection challenge creation are separate operations. Here `send` and
`receiveEncryptionResponse` are the server Login loop's ordered packet operations; `session` is that connection,
`sessions` is its caller-owned Session Server API, and `loginName` plus `observedClientAddress` come from the accepted
Login Start and socket:

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
