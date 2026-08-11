# protocol-auth

Cross-platform authentication and Minecraft Login cryptography for Java Edition.

Examples use Ktor's CIO engine only for concreteness; applications may supply any engine or preconfigured `HttpClient`.

Use this module to:

- create offline UUIDs and profiles;
- sign in with Microsoft Authorization Code + PKCE or Device Code, refresh a login, or continue from an external token;
- exchange Microsoft credentials for a Minecraft account and retrieve its entitlements and profile;
- call the Minecraft session service during an online-mode game login;
- create and answer Minecraft Login encryption challenges; and
- expose the same authentication operations through an application-hosted relay when a browser cannot call an upstream
  endpoint directly.

## HTTP ownership

Every network service receives a caller-created Ktor `HttpClient` directly. The library installs no engine, changes no
client configuration, and never closes the client. Engine, redirect, TLS, proxy, timeout, retry, logging, and lifecycle
choices belong entirely to the application. The program that creates the HTTP client must eventually close it.

If an upstream structured response cannot be decoded, the authentication exception retains the original serialization
cause for diagnosis.

## Client path: sign in and join an online server

The client path contains two separate network conversations. Account sign-in first talks to Microsoft, Xbox Live, and
Minecraft Services over HTTP and produces a `MinecraftOnlineAccount`. A later game connection talks to the selected
Minecraft server over TCP and uses that account only when registering `/join` with the Minecraft session server.

### 1. Create the account services

Register a caller-owned Microsoft public-client application that permits personal Microsoft accounts, enable the chosen
public-client flow, select only scopes approved for that registration, and obtain any Minecraft Services application
approval required for the intended use. The library contains no shared client ID and has no `clientSecret` API.

```kotlin
val httpClient = HttpClient(CIO)
val application = MicrosoftOAuthApplication(
    clientId = appConfiguration.microsoftClientId,
    scopes = appConfiguration.approvedMicrosoftScopes.map(::MicrosoftOAuthScope),
)
val oauth = MicrosoftOAuthService(httpClient, application)
val accounts = MinecraftAccountService(httpClient)
val sessions = MinecraftSessionService(httpClient)
```

### 2. Complete Microsoft OAuth

Choose one OAuth entry point. Authorization Code + PKCE returns an authorization URI and a state object that must be
passed back when completing the login. The application opens the system browser or WebView and returns the complete
callback URI to the library:

```kotlin
val authorization = oauth.beginAuthorizationCodeLogin(
    redirectUri = Url("my-launcher://microsoft/callback"),
)
applicationUi.openUri(authorization.authorizationUri)

val microsoftTokens = oauth.completeAuthorizationCodeLogin(
    authorization = authorization,
    redirectedUri = callbackUri,
)
```

Device Code is an alternative, not an additional step. The application displays the returned code and URI, then awaits
completion. Cancel the awaiting coroutine if the user cancels the login:

```kotlin
val deviceAuthorization = oauth.beginDeviceCodeLogin()
applicationUi.showDeviceCode(
    deviceAuthorization.userCode,
    deviceAuthorization.verificationUri,
)
val microsoftTokens = oauth.awaitDeviceCodeLogin(deviceAuthorization)
```

At this point `microsoftTokens` contains Microsoft OAuth credentials, not a Minecraft session credential. The
application can instead supply an access token obtained from MSAL, WAM, or another broker with
`MicrosoftAccessToken.fromExternalProvider(...)`.

### 3. Exchange the Microsoft token for a Minecraft account

```kotlin
val login = accounts.loginWithMicrosoftTokens(microsoftTokens)
val account = login.account
```

That single call performs the official account chain in order:

1. exchange the Microsoft access token for an Xbox Live user token and user hash;
2. authorize that user with XSTS for the Minecraft Services relying party;
3. exchange the XSTS token for a Minecraft Services access token;
4. fetch entitlements and the Java Edition profile;
5. return `MinecraftAccountLoginResult` with the verified profile, entitlements, optional refresh token, and a
   `MinecraftOnlineAccount` that can be used for session-service authentication.

The Microsoft token, Xbox tokens, and Minecraft token are different credentials. The later `/join` request uses the
Minecraft access token stored in `account`; it never sends the Microsoft or Xbox tokens to the game server.

Treat refresh tokens and Minecraft access tokens as credentials. Persist them through `exportForSecureStorage()`, store
the returned envelope in application-owned secure storage, and restore it with `oauth.importRefreshToken(...)` or
`MinecraftOnlineAccount.fromSecureStorage(...)`. A successful refresh may rotate the refresh token, so replace stored
credentials only after the complete refresh succeeds.

A launcher that already owns a Minecraft Services token and verified profile can skip Microsoft and Xbox entirely:

```kotlin
val account = MinecraftOnlineAccount.fromExistingCredentials(
    name = verifiedProfileName,
    id = verifiedProfileId,
    accessToken = minecraftAccessToken,
    expiresAt = tokenExpiry,
)
```

### 4. Open the game connection

This high-level example also uses the separately published `protocol-client` module. If the application owns its own
Login state machine, use the lower-level sequence in step 6 instead. Create a fresh TCP connection, combine the account
with its session service, and ask the high-level client to log in:

```kotlin
SelectorManager(Dispatchers.Default).use { selector ->
    MinecraftClientConnection.connect(
        selectorManager = selector,
        host = serverHost,
        port = serverPort,
    ).use { connection ->
        val identity = MinecraftOnlineIdentity(account, sessions)
        val result = connection.protocol.login(identity)

        val serverLogin = result.login
        val playSession = connection.session
        // The connection is now in Play; continue receiving and sending through playSession.
    }
}
```

`serverHost` is the selected Minecraft game server. Its RSA public key is not fetched over HTTP: it arrives inside that
server's Login-state Encryption Request packet on this TCP connection.

### 5. What `MinecraftClientProtocol.login` does

The high-level call implements the official client flow and the library mapping below.

| Official client step                                                                                             | Library behavior                                                                                                                                                            |
|------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Send Handshake selecting Login, then Login Start with the account name and UUID                                  | `MinecraftClientProtocol.login` sends both packets.                                                                                                                         |
| Receive Encryption Request containing the server ID, RSA public key, fresh verify token, and authentication flag | `MinecraftSession.receive` decodes `EncryptionRequestPacket`.                                                                                                               |
| Create a fresh 16-byte shared secret and RSA-encrypt both it and the exact verify token                          | `MinecraftEncryption.answerServerChallenge` returns the response and shared secret needed for the remaining steps.                                                          |
| Compute the signed SHA-1 server hash from the server ID, shared secret, and encoded server public key            | `minecraftServerHash` returns the value expected by the session server.                                                                                                     |
| Tell the session server that this authenticated profile intends to join this exact encrypted connection          | `MinecraftSessionService.join(account, serverHash)` submits the Minecraft access token, profile UUID, and hash to `/join`. The game server never receives the access token. |
| Send Encryption Response while the TCP stream is still unencrypted                                               | `MinecraftSession.send(encryption.response)` writes and flushes the response.                                                                                               |
| Enable encryption only after the response has crossed the wire                                                   | `MinecraftSession.enableEncryption(sharedSecret)` activates the existing transport cipher.                                                                                  |
| Process optional compression, Login Success, Login Acknowledged, Configuration, and Play Login                   | `MinecraftSession` applies wire transitions and `MinecraftClientProtocol.login` returns only after the connection reaches Play.                                             |

If the Encryption Request says authentication is required, failure of `/join` is terminal: the client does not send an
Encryption Response and does not downgrade to an offline identity. The online server path provided by this repository
always requests authentication.

### 6. The same client sequence with lower-level tools

Applications that own a custom Login state machine can call the lower-level APIs in this order:

```kotlin
val encryptionRequest = session.receive() as EncryptionRequestPacket
val encryption = MinecraftEncryption.answerServerChallenge(encryptionRequest)
val sharedSecret = encryption.sharedSecret

try {
    val serverHash = minecraftServerHash(
        serverId = encryptionRequest.serverId,
        sharedSecret = sharedSecret,
        encodedPublicKey = encryptionRequest.publicKey.toByteArray(),
    )
    if (encryptionRequest.shouldAuthenticate) {
        sessions.join(account, serverHash)
    }

    session.send(encryption.response)
    session.enableEncryption(sharedSecret)
} finally {
    sharedSecret.fill(0)
}
```

Do not enable the cipher before Encryption Response has been completely written. When using `MinecraftSession`, call
`session.enableEncryption` as shown. A custom socket stack must consume the returned secret at the same boundary.

### Browser authentication relay

Browser Authorization Code + PKCE token redemption can use a direct browser `HttpClient` when its redirect is registered
as an SPA. Device Code endpoints and Xbox, Minecraft Services, `/join`, and `/hasJoined` should use the application's
trusted same-origin HTTPS authentication relay:

```kotlin
val relayEndpoint = Url("https://launcher.example/auth/minecraft")
val deviceOauth = MicrosoftOAuthService(browserHttpClient, application, relayEndpoint)
val browserAccounts = MinecraftAccountService(browserHttpClient, relayEndpoint)
val browserSessions = MinecraftSessionService(browserHttpClient, relayEndpoint)
```

On the backend, pass the incoming request to `MinecraftAuthenticationRelayHandler` and write its response through the
application's server framework:

```kotlin
val relayHandler = MinecraftAuthenticationRelayHandler(
    upstreamHttpClient = upstreamHttpClient,
    policy = MinecraftAuthenticationRelayPolicy(
        allowedOperations = appConfiguration.allowedAuthenticationRelayOperations,
        allowedMicrosoftClientIds = setOf(appConfiguration.microsoftClientId),
    ),
)

val response = relayHandler.handle(
    MinecraftAuthenticationRelayRequest(
        contentType = incomingContentType,
        body = boundedIncomingBody,
    ),
)
// Write the response fields through the application's server framework.
```

The handler accepts only the supported authentication operations. The application still owns its outer endpoint's access
control, CSRF protection, rate limits, TLS, and logging policy. This HTTP relay is separate from any carrier used for
Minecraft socket bytes.

## Server path: verify a join and admit the player

An online game server does not receive the client's Microsoft, Xbox, or Minecraft access token. It receives Login Start
and Encryption Response packets, derives the connection hash from those packets and its own RSA state, and asks the
Minecraft session server whether a client previously registered a matching `/join` intent.

### 1. Create one online authentication configuration

Create the HTTP session service and online authentication object once during server startup, not once per accepted
connection:

```kotlin
val httpClient = HttpClient(CIO)
val sessionService = MinecraftSessionService(httpClient)
val authentication = MinecraftServerAuthentication.online(sessionService)

val configuration = MinecraftServerConfiguration(
    authentication = authentication,
    preventProxyConnections = true,
)
```

Reuse this authentication configuration for accepted connections throughout the server's lifetime.

### 2. Bind, accept, and decide admission

This high-level example also uses the separately published `protocol-server` module. The application owns the accept
loop and concurrency. `MinecraftServerHandler.acceptProfile` is called only after the session server has returned a
verified profile; return `true` to admit it or `false` to send a Login rejection:

```kotlin
val handler = object : MinecraftServerHandler {
    override suspend fun acceptProfile(profile: GameProfile): Boolean =
        applicationAdmissionPolicy.allows(profile)
}

SelectorManager(Dispatchers.Default).use { selector ->
    MinecraftServer.bind(
        selectorManager = selector,
        configuration = configuration,
        handler = handler,
    ).use { server ->
        server.accept().use { connection ->
            when (val result = connection.negotiate()) {
                MinecraftServerNegotiationResult.StatusCompleted -> Unit
                is MinecraftServerNegotiationResult.PlayReady -> {
                    val verifiedProfile = result.profile
                    val playSession = connection.session
                    // The player is admitted and the connection is now in Play.
                }
            }
        }
    }
}
```

The default handler accepts every session-service-verified profile. Applications can override `profileRejection` when
they need a custom structured disconnect component rather than the default rejection.

### 3. What `MinecraftServerConnection.negotiate` does

The high-level call implements the official server flow and extracts the relevant values from the received packets.

| Official server step                    | Library behavior                                                                                                                                                                                         |
|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Receive Handshake and Login Start       | `MinecraftSession.receive` provides the requested username and client-supplied UUID. In online mode that UUID is not accepted as the authoritative identity.                                             |
| Send an Encryption Request              | `MinecraftEncryption.createServerChallenge` creates a challenge from the server authentication context, and `MinecraftSession.send` sends its request unencrypted.                                       |
| Receive Encryption Response             | `MinecraftSession.receive` decodes the two RSA ciphertext fields. No access token is present in this packet.                                                                                             |
| Recover and validate connection secrets | `MinecraftEncryption.acceptClientResponse` returns the shared secret or rejects an invalid response.                                                                                                     |
| Turn on the encrypted game stream       | After the complete response has been received, `MinecraftSession.enableEncryption(sharedSecret)` enables encryption before any further game packet is exchanged.                                         |
| Recompute the client's server hash      | `minecraftServerHash` uses the same server ID, shared secret, and encoded public key used by the client.                                                                                                 |
| Confirm the prior `/join` intent        | `MinecraftSessionService.hasJoined(username, serverHash, ipAddress)` calls `/hasJoined`. When proxy prevention is enabled, the observed numeric client IP is included.                                   |
| Establish the authoritative profile     | A null response rejects Login. A successful response supplies the authoritative `GameProfile`; the UUID claimed in Login Start is not trusted.                                                           |
| Apply application admission policy      | `MinecraftServerHandler.profileRejection` or `acceptProfile` decides whether the already authenticated profile may enter this application server.                                                        |
| Finish Login and Configuration          | The protocol optionally sends Set Compression, then Login Success, processes Login Acknowledged and Configuration, sends Play Login, and returns `PlayReady` with the verified profile and live session. |

`/join` and `/hasJoined` are the rendezvous: the client first registers its profile UUID and server hash with its
Minecraft access token; the game server later presents the requested username and the independently computed same hash.
Only the session server sees the access token. A missing or mismatched registration therefore becomes `null` from
`hasJoined` and the game server rejects the connection.

### 4. The same server sequence with lower-level tools

Applications that own a custom Login state machine can call the lower-level APIs directly. Create the context once, then
create and consume a distinct challenge for each connection:

```kotlin
// Once during server startup.
val serverContext = MinecraftEncryption.createServerContext()

// Once for each connection after receiving Login Start.
val loginStart = session.receive() as LoginStartPacket
val challenge = MinecraftEncryption.createServerChallenge(serverContext)
session.send(challenge.request)

val encryptionResponse = session.receive() as EncryptionResponsePacket
val sharedSecret = MinecraftEncryption.acceptClientResponse(
    challenge = challenge,
    response = encryptionResponse,
)

try {
    session.enableEncryption(sharedSecret)
    val serverHash = minecraftServerHash(
        serverId = challenge.request.serverId,
        sharedSecret = sharedSecret,
        encodedPublicKey = challenge.request.publicKey.toByteArray(),
    )
    val joined = sessionService.hasJoined(
        username = loginStart.name,
        serverHash = serverHash,
        ipAddress = if (preventProxyConnections) requireNotNull(observedClientIp) else null,
    ) ?: throw MinecraftAuthenticationException("Session server did not verify the join")

    val verifiedProfile = joined.profile
} finally {
    sharedSecret.fill(0)
}
```

Clear the caller-held shared secret after `session.enableEncryption` has installed it in the transport.

### 5. Encryption of all subsequent packets

Encryption Request and Encryption Response are the last plaintext Login exchange. The high-level client and server call
`MinecraftSession.enableEncryption` at the correct boundary, so applications using those APIs need no additional
encryption setup.

Applications with a custom Login state machine must enable encryption immediately after sending or receiving the full
Encryption Response. `protocol-transport` then encrypts the socket stream with AES-128/CFB8, using the 16-byte shared
secret as both key and initialization vector. It maintains separate continuous cipher states for sending and receiving;
do not reset them at packet boundaries.

Outbound data is packet-encoded, optionally compressed, framed, and then encrypted. Inbound data is decrypted, split
into frames, optionally decompressed, and then decoded. A Set Compression packet sent after authentication is therefore
encrypted, and its compression setting applies to the packets that follow it. A completely custom transport must
preserve these same rules.

Authentication and cryptographic failures are terminal and never downgrade an online connection to offline mode.

## Offline profiles

Offline profiles are independent of Microsoft and session-service authentication:

```kotlin
val profile = offlineProfile(playerName)
val id = offlineUuid(playerName)
val restoredId = parseMinecraftUuid(profile.id.toDashedString())
```
