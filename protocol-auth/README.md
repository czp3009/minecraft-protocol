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
[`account-auth`](../account-auth/README.md); this module consumes the resulting caller-managed identity values rather
than acquiring them. The shared secret produced here is only key material—
[`protocol-transport`](../protocol-transport/README.md) performs the continuous stream encryption and the connection
modules apply it at the correct wire boundary. `protocol-model` is a direct API dependency because the authentication
and signed-chat APIs naturally consume its profiles, packets, and shared wire values. Reconstructed signature bodies,
chain links, and Brigadier-derived signable arguments remain `protocol-auth` values.

## Identities

`MinecraftIdentity` is a sealed interface so downstream connection code can exhaustively distinguish offline and online
identities. The online values `profileId`, `profileName`, and `minecraftAccessToken` are supplied by the launcher or
account-login layer before the game connection starts. Take `name` and `id` from the
[`MinecraftProfileResponse`](../account-auth/README.md#5-retrieve-entitlements-and-the-java-profile-when-needed);
parse its compact ID with `Uuid.parseHex(...)` to obtain `profileId`. The access token comes from the Services login:

```kotlin
val offline = MinecraftOfflineIdentity("Player")

val online = MinecraftOnlineIdentity(
    id = profileId,
    name = profileName,
    accessToken = minecraftAccessToken,
)
```

To compute a vanilla offline UUID without constructing an identity:

```kotlin
val id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player")
```

Identity types are ordinary data classes. Credential logging and storage are caller responsibilities.
`MinecraftOfflineIdentity.toGameProfile()` adapts the identity to a `protocol-model` profile.

## Login key exchange

This is the custom-flow path; high-level client/server `negotiate()` already performs it. Create a caller-configured
Ktor `HttpClient`, then `MinecraftSessionApi(httpClient)` before the exchange. The online identity comes from the
preceding example. A custom Login packet loop passes the `ClientboundHelloPacket` received on `incoming`:

```kotlin
suspend fun respondToChallenge(
    clientboundHelloPacket: ClientboundHelloPacket,
    minecraftOnlineIdentity: MinecraftOnlineIdentity,
    minecraftSessionApi: MinecraftSessionApi,
): MinecraftClientKeyExchangeResult {
    val result = MinecraftClientKeyExchange.respond(clientboundHelloPacket)
    if (clientboundHelloPacket.shouldAuthenticate) {
        minecraftSessionApi.join(minecraftOnlineIdentity, result.minecraftServerHash)
    }
    return result
}
```

`MinecraftClientKeyExchangeResult.toServerboundKeyPacket()` supplies the reply and `sharedSecret` supplies the
cipher key. The maintained [client negotiation](../protocol-client/README.md#online-login) orders the reply, encryption
activation and key cleanup. Custom endpoints must preserve that same wire boundary; enqueueing a reply alone is not
proof that it has been written.

For servers, generate a shareable `MinecraftServerKeyPair`, call `createChallenge` per connection and send its
`toClientboundHelloPacket()` result. `MinecraftServerChallenge.accept` consumes the received `ServerboundKeyPacket`
and returns `MinecraftServerKeyExchangeResult`. [Server negotiation](../protocol-server/README.md#online-authentication)
owns
activation and the subsequent `/hasJoined` call.

Instead of generating a key pair, callers may construct `MinecraftServerKeyPair` from DER-encoded public and private
keys. The server key-pair object is intentionally opaque because it owns private-key material; response and result
models are data classes. Backend cryptography failures use `MinecraftCryptographyException`; coroutine cancellation
propagates as `CancellationException` instead of being wrapped as a cryptography failure.

## Minecraft Session Server

`MinecraftSessionApi` is stateless apart from its reference to a caller-owned `HttpClient`. It does not install an
engine, alter client configuration, close the client, retry, or refresh credentials. Here `applicationHttpClient` is
that configured client. For a client, `minecraftServerHash` is `respondToChallenge(...).minecraftServerHash`; for a
server it is the hash from `MinecraftServerChallenge.accept(...)`. `playerName` is from `ServerboundHelloPacket.name`,
and `observedClientAddress` is the accepted connection's optional peer IP. The access token and profile ID were
introduced in the
identity example. The two calls below illustrate the client and server sides separately; a custom client which uses
`respondToChallenge` already performed its join and must not call it again:

```kotlin
val minecraftSessionApi = MinecraftSessionApi(applicationHttpClient)

minecraftSessionApi.join(
    MinecraftSessionJoinRequest(
        accessToken = minecraftAccessToken,
        selectedProfile = profileId.toHexString(),
        serverId = minecraftServerHash.value,
    ),
)

val minecraftSessionHasJoinedResponse = minecraftSessionApi.hasJoined(
    MinecraftSessionHasJoinedRequest(
        username = playerName,
        serverId = minecraftServerHash.value,
        ip = observedClientAddress,
    ),
)
```

As an alternative to constructing `MinecraftSessionJoinRequest`, the identity overload performs the same join:

```kotlin
minecraftSessionApi.join(online, minecraftServerHash)
```

`hasJoined` returns `null` for the documented `204 No Content` unverified-player response and otherwise decodes the
profile response directly. Other HTTP failures throw `MinecraftSessionResponseException`, which exposes the raw body and
the decoded service error.

`fetchProfile` retrieves a public Session Server profile and maps the official `requireSecure` argument to the
`unsigned` query parameter:

```kotlin
val gameProfile = minecraftSessionApi.fetchProfile(profileId, requireSecure = true)?.toGameProfile()
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

`MinecraftProfileKeyApi` uses the same caller-owned HTTP client. Key refresh, caching and persistence are application
decisions:

```kotlin
val minecraftProfileKeyApi = MinecraftProfileKeyApi(applicationHttpClient)
val minecraftProfileKeyPair = minecraftProfileKeyApi.fetchProfileKeyPair(online).toMinecraftProfileKeyPair()
val minecraftServicesPublicKeySet = minecraftProfileKeyApi.fetchServicesPublicKeys().toMinecraftServicesPublicKeySet()

val credentialIsValid = minecraftServicesPublicKeySet.verifyProfilePublicKey(
    profileId = online.id,
    profilePublicKeyData = minecraftProfileKeyPair.profilePublicKeyData,
)
```

The official service exposes raw RSA SubjectPublicKeyInfo values in `playerCertificateKeys`; profile credentials use
`SHA1withRSA`. This is not an X.509 root-certificate chain. `MinecraftProfilePublicKey` parsing is deliberately separate
from credential trust, so callers can fetch keys elsewhere or construct their own key set. Expiry and refresh helpers
take an explicit epoch millisecond value and never read the clock implicitly.

## Signed chat

`MinecraftChatSignatures` is the stateless payload/sign/verify layer. `MinecraftChatChainSigner` adds only a locked
sender/session index. A batch—especially a signed command's arguments—is allocated contiguously and committed only when
every signature succeeds. The `online` identity and `minecraftProfileKeyPair` come from the preceding examples.
Choose `chatSessionId` with `Uuid.random()` and announce the corresponding
`ChatSessionData(chatSessionId, minecraftProfileKeyPair.profilePublicKeyData)` through the application's chat-session
flow. `text`, epoch-millisecond `timestamp` and `salt` are scalar message inputs. The application's acknowledgement
tracker supplies `expandedLastSeenSignatures: List<ByteString>` (actual signature bytes, with `emptyList()` valid for
an empty history) and `LastSeenMessagesUpdate(offset, acknowledged, checksum)` as `lastSeenUpdate`. These two forms must
describe the same history:

```kotlin
val minecraftChatChainSigner = MinecraftChatChainSigner(
    sender = online.id,
    sessionId = chatSessionId,
    minecraftProfileKeyPair = minecraftProfileKeyPair,
)

val serverboundChatPacket = minecraftChatChainSigner.signServerboundChatPacket(
    message = text,
    timestampEpochMillis = timestamp,
    salt = salt,
    lastSeen = expandedLastSeenSignatures,
    lastSeenMessagesUpdate = lastSeenUpdate,
)
```

The serverbound chat and signed-command packets do not carry their chain index. The server therefore keeps one
`MinecraftServerboundChatChainVerifier` per accepted player chat session; each valid message or signed command argument
advances its implicit index. To illustrate the other side of the same message, use the sender/session announced above
and the public key from the previously fetched key pair. A real server gets those values from the accepted player's
`ChatSessionData` and constructs `MinecraftProfilePublicKey(chatSessionData.profilePublicKey)` after credential checks.
Pass the received `ServerboundChatPacket` and the signatures expanded by that server's acknowledgement tracker:

```kotlin
val minecraftServerboundChatChainVerifier = MinecraftServerboundChatChainVerifier(
    sender = online.id,
    sessionId = chatSessionId,
    minecraftProfilePublicKey = minecraftProfileKeyPair.minecraftProfilePublicKey,
)
val minecraftChatVerificationResult =
    minecraftServerboundChatChainVerifier.verify(serverboundChatPacket, expandedLastSeenSignatures)
```

Inspect `MinecraftChatVerificationResult.Valid.minecraftSignedMessage` or
`MinecraftChatVerificationResult.Invalid.minecraftChatChainFailure` and apply the application's acceptance policy.

Invalid input does not mutate the verifier. A caller that wants the official server's permanently-broken-chain policy
can discard that verifier after a failure. `MinecraftClientboundChatChainVerifier` instead consumes the explicit packet
index, accepts gaps because a recipient may not receive every sender message, and accepts an exact duplicate.

Packet helpers convert chat packets to unpacked signed bodies, sign command argument lists, and build recipient-specific
`ClientboundPlayerChatPacket` values after the caller supplies the global index and packed last-seen signatures. The
module
does not reconstruct acknowledgement updates, manage signature caches/global indices, announce sessions, enforce server
configuration, disconnect players, order separately returned sends, or broadcast.
