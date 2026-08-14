# protocol-auth

Cross-platform Microsoft, Xbox, and Minecraft account authentication for Minecraft: Java Edition, plus the later
Minecraft session-service, offline-profile, server-hash, and Login-cryptography primitives owned by this module.

“Cross-platform” here refers only to the Kotlin targets on which this library runs; the protocol scope remains
Minecraft: Java Edition.

`protocol-auth` implements its supported authentication exchanges itself. Ktor, serialization, and cryptographic
dependencies provide HTTP machinery and primitive algorithms; no external Minecraft authentication implementation is
required at runtime.

This guide has two purposes:

- document the authentication protocols themselves, including important alternative routes; and
- show which parts are implemented by this project and how its public API maps to each protocol step.

The account-login part of this guide ends with a `MinecraftOnlineAccount`: a Java profile name and UUID paired with a
Minecraft Services access token. Minecraft socket Login and stream encryption happen later. They do not carry the
Microsoft access token, Xbox tokens, or Microsoft refresh token.

## Add the module

The project does not yet publish a stable binary release. From this checkout, add the narrow module directly:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":protocol-auth"))
        }
    }
}
```

Examples use Ktor's CIO engine only for concreteness. Any target-appropriate engine or preconfigured `HttpClient` can be
supplied.

## Account authentication at a glance

The normal direct protocol route is a chain of distinct credentials:

```text
Microsoft authorization
        │
        ▼
Microsoft access token ───────────────┐
Microsoft refresh token (optional)    │
                                      ▼
                              Xbox User Token + uhs
                                      │
                                      ▼
                       Minecraft-relying-party XSTS token + uhs
                                      │
                                      ▼
                         Minecraft Services access token
                                      │
                         ┌────────────┴────────────┐
                         ▼                         ▼
                   entitlements              Java profile
                                                   │
                                                   ▼
                                        MinecraftOnlineAccount
                                                   │
                                      later, during socket Login
                                                   ▼
                                      session server /join request
```

The arrows are token exchanges, not conversions that preserve the same credential. Each token has a different issuer,
audience, lifetime, and permitted destination.

| Value                                            | Issuer or origin               | Used for                                               | Must not be sent to                                      |
|--------------------------------------------------|--------------------------------|--------------------------------------------------------|----------------------------------------------------------|
| Microsoft authorization code                     | Microsoft identity platform    | One token-endpoint redemption                          | Xbox, Minecraft Services, or a game server               |
| Microsoft access token                           | Microsoft identity platform    | Xbox User Token request                                | Minecraft Services or a game server                      |
| Microsoft refresh token                          | Microsoft identity platform    | A later Microsoft access-token refresh                 | Xbox, Minecraft Services, session server, or game server |
| Xbox User Token                                  | Xbox authentication service    | XSTS authorization                                     | Minecraft Services or a game server                      |
| XSTS token for `rp://api.minecraftservices.com/` | Xbox Security Token Service    | Minecraft Services login                               | A game server                                            |
| Minecraft access token                           | Minecraft Services             | Entitlements, profile APIs, and session-server `/join` | The selected game server                                 |
| Java profile name and UUID                       | Minecraft Services profile API | Launching and identifying the Java client              | Microsoft OAuth token endpoints                          |

All tokens in this module are treated as opaque strings. Do not assume that a token is a JWT, decode it for application
logic, or infer its audience from its syntax.

## The identifiers called “client ID”

Several unrelated identifiers are routinely called a client ID in launcher code and logs. They are not interchangeable.

| Identifier                                                      |                    Value | Meaning                                                                                                                                            |
|-----------------------------------------------------------------|-------------------------:|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Official Minecraft Launcher Microsoft/XAL application client ID |       `00000000402b5328` | Public application identifier used by the official Launcher's Microsoft/Xbox authentication registration                                           |
| Official Launcher XAL `titleId` observed in Launcher telemetry  |             `1794566092` | Numeric Xbox title identity in the Launcher's XAL context; this is not an OAuth `client_id` and is not sent by the supported route                 |
| Java game argument `--clientId ${clientid}`                     | No universal fixed value | A Base64-encoded launcher-installation UUID stored in `clientId.txt`; it is a telemetry/game-process value, not the Microsoft OAuth application ID |
| Xbox user ID, or XUID                                           |    No; one per Xbox user | User identity, not an application identity and not an OAuth client ID                                                                              |
| Legacy Mojang `clientToken`                                     |   No; launcher-generated | A retired Yggdrasil-era launcher token, outside Microsoft authentication and unsupported here                                                      |

For the Java Edition account flow, `00000000402b5328` is the one fixed Microsoft/XAL application ID belonging to the
official Minecraft Launcher that is relevant to this document. Other Minecraft editions and platform clients have
different registrations, but those values and their authentication chains are intentionally outside this Java Edition
module.

The fixed `00000000402b5328` value is visible in the official Launcher distribution and its XAL initialization. It is a
public identifier, not a client secret. Its publication does **not** grant another launcher ownership of, or permission
to impersonate, Mojang's application registration. Redirect URIs, enabled flows, Xbox configuration, and Minecraft
Services approval remain bound to the registered application. Consequently, this project does not bake that value into
the API or silently use it on behalf of consumers.

That fixed ID is also not a drop-in value for the supported examples below. It is a title-client registration: its
Microsoft token enters Xbox authentication with the `t=` RPS-ticket form and, in the current Launcher, participates in
the XAL route described in A7. The project's direct caller-owned Entra route emits the `d=` form shown in B1. Supplying
`00000000402b5328` to `MicrosoftOAuthApplication` would not turn the latter implementation into the former protocol.

The supported direct title-website route supplies a caller-owned, Minecraft-approved application ID explicitly:

```kotlin
val application = MicrosoftOAuthApplication(
    clientId = appConfiguration.microsoftClientId,
    scopes = listOf(
        MicrosoftOAuthScope("xboxlive.signin"),
        MicrosoftOAuthScope("xboxlive.offline_access"),
    ),
)
```

`xboxlive.offline_access` is optional. Request it only when the application intends to retain and protect a refresh
token. `MicrosoftOAuthApplication` defaults to the `consumers` tenant because Minecraft ownership is attached to a
personal Microsoft account. Other Microsoft tenant aliases exist at the OAuth protocol level, but signing in a work or
school identity does not create a Minecraft account. Creating an ordinary Entra application registration alone does not
grant access to Minecraft Services; the application must also satisfy Minecraft's application-registration process.

The official Java version manifest includes `--clientId ${clientid}` and `--xuid ${auth_xuid}` among its launch
arguments. Those placeholders are populated by a launcher when it starts the Java process. Neither value is accepted by
`/authentication/login_with_xbox`, `/launcher/login`, `/join`, or the game server Login packets, and `protocol-auth`
does not launch the official game process.

## Supported and alternative routes

Authentication has choices at two different layers: how the application obtains Microsoft or Xbox authorization, and how
it exchanges the final Minecraft-relying-party XSTS token with Minecraft Services.

| Route                                                                  | Protocol status                                                                 | This project                                                                                    |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| Microsoft public-client Authorization Code + PKCE                      | Current Microsoft OAuth route                                                   | Supported                                                                                       |
| Microsoft Device Authorization Grant                                   | Current Microsoft OAuth route                                                   | Supported                                                                                       |
| Microsoft refresh-token grant                                          | Current Microsoft OAuth route                                                   | Supported                                                                                       |
| Access token from MSAL, WAM, or another application broker             | Current integration route                                                       | Supported from the Microsoft access-token boundary                                              |
| Confidential web authorization code with client secret or certificate  | Valid server-side OAuth route                                                   | Not implemented; complete it in the confidential backend and pass in the resulting access token |
| Legacy Microsoft Account `login.live.com/oauth20_*` title-client route | Registration-specific legacy route with `t=` RPS tickets                        | Not implemented                                                                                 |
| Xbox Authentication Library (XAL), device/title identity, and SISU     | Route used by the current official Launcher                                     | Not implemented                                                                                 |
| Direct Xbox User Token followed by XSTS                                | Microsoft-documented title-website route                                        | Supported                                                                                       |
| Minecraft `/authentication/login_with_xbox`                            | Minecraft Services login route                                                  | Supported                                                                                       |
| Minecraft `/launcher/login` with `platform=PC_LAUNCHER`                | Launcher-flavoured Minecraft Services login route used by the official Launcher | Not implemented                                                                                 |
| Minecraft `/entitlements/mcstore`                                      | Traditional entitlement route                                                   | Supported                                                                                       |
| Launcher signed-license entitlement route                              | Launcher-specific alternative                                                   | Not implemented                                                                                 |
| Existing Minecraft access token and verified Java profile              | Broker or launcher handoff                                                      | Supported                                                                                       |

“Not implemented” means the path is described here so that the protocol is not mistaken for having only one route. It
does not mean that callers should reproduce registration-specific or undocumented requests by copying another client's
identifiers.

## Phase A: acquire a Microsoft access token

All supported in-library OAuth flows use these Microsoft identity platform endpoints, where `{tenant}` is normally
`consumers`:

```text
GET  https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize
POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/devicecode
POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token
```

`MicrosoftOAuthService` owns the protocol mechanics. The application still owns app registration, UI, callback handling,
secure storage, and the Ktor client.

### Route A1: Authorization Code + PKCE

This is the interactive public-client route for desktop, mobile, and SPA applications. A system browser, embedded
WebView, custom-scheme callback, universal link, or loopback HTTP listener can carry the browser interaction; these are
different UI/callback carriers for the same OAuth grant.

#### A1.1 Request an authorization code

Direct the user's browser to:

```http
GET https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize?
    client_id={application-client-id}&
    response_type=code&
    redirect_uri={registered-redirect-uri}&
    response_mode=query&
    scope=xboxlive.signin%20xboxlive.offline_access&
    state={cryptographically-random-state}&
    code_challenge={base64url-sha256-of-verifier}&
    code_challenge_method=S256
```

The browser authenticates the Microsoft user and obtains consent. Microsoft redirects to the exact registered URI with
one of these query shapes:

```text
{redirect-uri}?code={short-lived-authorization-code}&state={same-state}
{redirect-uri}?error={oauth-error}&error_description={details}&state={same-state}
```

The client must verify `state`, preserve the PKCE verifier, require the same redirect URI, and redeem a code only once.

Project mapping:

```kotlin
val oauth = MicrosoftOAuthService(httpClient, application)
val authorization = oauth.beginAuthorizationCodeLogin(
    redirectUri = Url("my-launcher://microsoft/callback"),
)

applicationUi.openUri(authorization.authorizationUri)
```

`MicrosoftAuthorizationCodeLogin` retains the state, redirect URI, and verifier without exposing them in `toString()`.
The application returns the **complete** callback URI, not just its `code` parameter:

```kotlin
val microsoftTokens = oauth.completeAuthorizationCodeLogin(
    authorization = authorization,
    redirectedUri = callbackUri,
)
```

#### A1.2 Redeem the code

The public client sends a form request:

```http
POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded

client_id={same-client-id}&
grant_type=authorization_code&
code={authorization-code}&
redirect_uri={same-redirect-uri}&
code_verifier={original-pkce-verifier}
```

`scope` is optional during code redemption when the authorization request already selected the resource. This project
omits it on this request. A successful response contains:

```json
{
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "xboxlive.signin xboxlive.offline_access",
  "access_token": "<opaque Microsoft access token>",
  "refresh_token": "<opaque refresh token, when offline access was granted>"
}
```

Public clients must not send a `client_secret`. PKCE proves that the application redeeming the code is the one that
created the authorization request; a distributable desktop, mobile, or browser application cannot protect a shared
secret.

For a browser SPA, the redirect URI must be registered with the `spa` type. Microsoft requires the browser's `Origin`
header for that token redemption and supplies the corresponding CORS behavior. That browser restriction is why using a
same-origin backend relay for every OAuth call is not automatically equivalent to a direct SPA redemption.

### Route A2: Device Authorization Grant

Device Code is an alternative to Authorization Code, not an extra step after it. It is useful when the application has
no convenient redirect handler or asks the user to authenticate on another device.

#### A2.1 Request a device and user code

```http
POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/devicecode
Content-Type: application/x-www-form-urlencoded

client_id={application-client-id}&
scope=xboxlive.signin%20xboxlive.offline_access
```

A successful response contains:

```json
{
  "device_code": "<opaque polling credential>",
  "user_code": "ABCD-EFGH",
  "verification_uri": "https://www.microsoft.com/link",
  "expires_in": 900,
  "interval": 5,
  "message": "<localized user instruction>"
}
```

The OAuth device-flow standard also defines optional `verification_uri_complete`. Microsoft currently may omit it, so
the project exposes it as nullable rather than assuming that it exists.

Project mapping:

```kotlin
val authorization = oauth.beginDeviceCodeLogin()

applicationUi.showDeviceCode(
    code = authorization.userCode,
    uri = authorization.verificationUriComplete ?: authorization.verificationUri,
    message = authorization.message,
)
```

The application displays `user_code`, `verification_uri`, and the optional provider message. It must not display or log
`device_code`.

#### A2.2 Poll the token endpoint

While the user authenticates in a browser, the original application polls:

```http
POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded

client_id={same-client-id}&
grant_type=urn:ietf:params:oauth:grant-type:device_code&
device_code={opaque-device-code}
```

The client waits at least `interval` seconds between requests. `authorization_pending` means continue polling;
`slow_down` means increase the interval; `authorization_declined`, `expired_token`, and other terminal errors stop the
flow. A successful response has the same Microsoft token shape as A1.

Project mapping:

```kotlin
val microsoftTokens = oauth.awaitDeviceCodeLogin(authorization)
```

The service observes the provider interval, adds five seconds after `slow_down`, respects expiry, and remains
coroutine-cancellable. Cancel the awaiting coroutine when the user cancels the application flow.

### Route A3: refresh an existing Microsoft login

An application that requested offline access can acquire another Microsoft access token without interactive UI:

```http
POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded

client_id={same-client-id}&
grant_type=refresh_token&
refresh_token={stored-refresh-token}&
scope=xboxlive.signin%20xboxlive.offline_access
```

The response has the normal token shape and may rotate the refresh token. Replace the stored token with the newly
returned one only after a successful response. Refresh credentials can expire, be revoked, or require renewed user
interaction; they are not permanent passwords. SPA-issued refresh tokens have additional browser-specific lifetime
limits.

Project mapping:

```kotlin
val restoredRefreshToken = oauth.importRefreshToken(
    secureStore.loadMicrosoftRefreshTokenCredentials(),
)
val refreshedMicrosoftTokens = oauth.refresh(restoredRefreshToken)
val refreshedLogin = accounts.loginWithMicrosoftTokens(refreshedMicrosoftTokens)

secureStore.replace(
    requireNotNull(refreshedLogin.refreshToken).exportForSecureStorage(),
)
```

The exported envelope records the client ID, tenant, scopes, originating flow, and direct/relay channel. Import rejects
a credential restored into a differently configured `MicrosoftOAuthService`; this prevents an application from silently
replaying a refresh credential through the wrong registration or trust boundary.

### Route A4: application broker, MSAL, or WAM

An application can let a maintained platform broker acquire the correctly scoped Microsoft access token. The project
then starts at the token-exchange boundary:

```kotlin
val microsoftAccessToken = MicrosoftAccessToken.fromExternalProvider(
    brokerResult.accessToken,
)
val login = accounts.loginWithMicrosoftAccessToken(microsoftAccessToken)
```

The broker must have acquired an access token intended for the supported caller-owned Entra-to-Xbox exchange, whose Xbox
RPS-ticket representation is `d=<token>`. A legacy/title-client token requiring `t=`, Microsoft Graph token, ID token,
Minecraft access token, or XSTS token is not a substitute. This entry point does not invent a refresh token; refresh
remains the broker's responsibility.

This is an interoperability boundary for a token that the host application already owns, not a recommendation that
`protocol-auth` delegate its own A1–A3 implementation to another authentication library. The module itself implements
those public-client OAuth grants using Ktor and the protocol models described above.

### Route A5: confidential server-side authorization code

A confidential web application can redeem the same kind of authorization code from its backend using either:

- `client_secret={secret}`, or
- `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer` plus a signed
  `client_assertion` for a registered certificate.

The secret or private key stays on the backend. This is a valid OAuth route, but `MicrosoftOAuthService` deliberately
models a public client and has no client-secret or certificate API. A confidential backend can complete OAuth outside
this module and then call `MinecraftAccountService.loginWithMicrosoftAccessToken(...)` with the resulting user access
token.

The OAuth client-credentials grant is not an alternative here: it authenticates an application without a Minecraft user
and therefore cannot produce a player's Minecraft account.

### Route A6: legacy Microsoft Account endpoints

Older launcher integrations and registration-specific examples use:

```text
GET  https://login.live.com/oauth20_authorize.srf
POST https://login.live.com/oauth20_connect.srf
POST https://login.live.com/oauth20_token.srf
```

For the fixed Java Launcher registration, the PKCE-enabled authorization-code shape uses:

```http
GET https://login.live.com/oauth20_authorize.srf?
    client_id=00000000402b5328&
    response_type=code&
    redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&
    scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&
    state={random-state}&
    code_challenge={base64url-sha256-of-verifier}&
    code_challenge_method=S256
```

The callback carries `code` and `state`. Code redemption posts `client_id`, `grant_type=authorization_code`, `code`, the
same `redirect_uri` and resource `scope`, and the original `code_verifier` to `oauth20_token.srf`. Refresh posts
`client_id`, `grant_type=refresh_token`, `refresh_token`, and the same scope to that token endpoint. The successful
access/refresh-token response has the same essential fields described in A1 and A3.

The same legacy endpoint family also has a registration-specific Device Code variant. It posts `client_id`, `scope`, and
`response_type=device_code` to `oauth20_connect.srf`, then polls `oauth20_token.srf` with `client_id`, `device_code`,
and a device-code grant. Implementations in the wild use both the short legacy `device_code` grant value and the RFC
URN; this is not a portable substitute for the documented Entra v2 Device Authorization Grant in A2.

The desktop redirect, resource scope, and `t=` Xbox RPS-ticket behavior are tied to the title-client registration. They
are not generic replacements for an application's Entra v2 redirect URI, `xboxlive.*` scopes, and `d=` RPS ticket.

This project does not implement the legacy endpoint family. Its raw OAuth implementation uses only the
`login.microsoftonline.com/{tenant}/oauth2/v2.0/*` endpoints.

### Route A7: XAL and SISU, used by the official Launcher

The current official Launcher embeds the Xbox Authentication Library rather than reproducing only the title-website
exchange implemented by this project. XAL manages Microsoft sign-in, local token storage, device identity, title
identity, proof keys, user identity, and Xbox token acquisition. Its sign-in path includes the SISU authorization
service:

```text
https://sisu.xboxlive.com/authorize
```

Before SISU, XAL can create a proof-of-possession device identity:

```http
POST https://device.auth.xboxlive.com/device/authenticate
x-xbl-contract-version: 1
Signature: <signature made by the device proof key>
Content-Type: application/json

{
  "Properties": {
    "AuthMethod": "ProofOfPossession",
    "DeviceType": "<provisioned launcher device type>",
    "Id": "{<device UUID>}",
    "ProofKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
  },
  "RelyingParty": "http://auth.xboxlive.com",
  "TokenType": "JWT"
}
```

The response supplies an Xbox Device Token. A schematic SISU request for the Java Services relying party is then:

```http
POST https://sisu.xboxlive.com/authorize
Signature: <signature made by the same proof key>
Content-Type: application/json

{
  "Sandbox": "RETAIL",
  "UseModernGamertag": true,
  "AppId": "00000000402b5328",
  "AccessToken": "t=<title-client Microsoft access token>",
  "DeviceToken": "<Xbox Device Token>",
  "ProofKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." },
  "RelyingParty": "rp://api.minecraftservices.com/"
}
```

Its response contains `UserToken`, `TitleToken`, and `AuthorizationToken`. The last is the Minecraft-relying-party XSTS
token; its `DisplayClaims.xui[0].uhs` and `Token` can continue to B3b. An alternative expanded XAL token stack calls the
Xbox user, device, title, and XSTS services separately, carrying `DeviceToken`, `TitleToken`, and `ProofKey` into XSTS,
rather than asking SISU to return the combined set.

These bodies show the roles of the inputs and outputs, not a reusable official-client recipe. The exact signature
canonicalization, device properties, key persistence, title provisioning, and XAL state are part of the registered XAL
client contract. Copying only the JSON and public `AppId` cannot reproduce it.

For Minecraft Java, XAL ultimately obtains an XSTS/XToken for the Minecraft Services relying party
`rp://api.minecraftservices.com/`. The downstream Minecraft login can then continue through the Launcher route described
in B3b.

This project does not implement XAL, SISU, device tokens, title tokens, or request signing. This is a route difference,
not evidence that the supported direct Xbox User Token route is the official Launcher implementation.

### OAuth routes that do not replace the normal flow

- The OAuth implicit grant exposes an access token through a browser redirect and is superseded by Authorization Code
  + PKCE for this use. It is not implemented.
- Resource Owner Password Credentials would require handling the user's Microsoft password, conflicts with MFA and
  modern account policy, and is not implemented.
- OIDC hybrid flow can additionally return an ID token, but an ID token identifies a user to the OAuth client; it cannot
  call Xbox authentication or Minecraft Services. The project does not request one.
- An app-only client-credentials token has no player identity and cannot enter the Xbox/Minecraft user chain.

## Phase B: exchange Xbox authorization for a Minecraft account

The supported project path begins with the Microsoft access token from any supported Phase A route. One public call
performs all of B1 through B5:

```kotlin
val accounts = MinecraftAccountService(httpClient)
val login = accounts.loginWithMicrosoftTokens(microsoftTokens)

val account = login.account
val entitlements = login.entitlements
val profile = login.profile
val refreshToken = login.refreshToken
```

Supplying only an externally acquired access token calls the same chain but naturally returns no Microsoft refresh
token:

```kotlin
val login = accounts.loginWithMicrosoftAccessToken(microsoftAccessToken)
```

### B1: Microsoft access token to Xbox User Token

Request:

```http
POST https://user.auth.xboxlive.com/user/authenticate
x-xbl-contract-version: 1
Content-Type: application/json

{
  "RelyingParty": "http://auth.xboxlive.com",
  "TokenType": "JWT",
  "Properties": {
    "AuthMethod": "RPS",
    "SiteName": "user.auth.xboxlive.com",
    "RpsTicket": "d=<Microsoft access token>"
  }
}
```

Relevant response fields:

```json
{
  "Token": "<Xbox User Token>",
  "DisplayClaims": {
    "xui": [
      {
        "uhs": "<user hash>"
      }
    ]
  }
}
```

`uhs` is the Xbox user hash used to select a user inside the later `XBL3.0` token. It is not the XUID. The project
requires exactly one non-empty user-hash claim.

The `RpsTicket` prefix is part of the protocol variant:

- `d=<token>` is used for the caller-owned Entra application route documented by Microsoft and implemented here.
- `t=<token>` is used with legacy/title-client Microsoft tokens such as the fixed official Launcher registration from
  A6. That route can also require Xbox device/title identity and proof-key requests from A7; it is not implemented here.

`XboxUserAuthenticationOperation` deliberately emits only `d=`. It does not inspect a token, guess its origin, or switch
protocol variants from the shape of a client ID.

Project implementation: `MinecraftAccountService.authenticateXboxUser`, backed by the internal
`XboxUserAuthenticationOperation`.

### B2: Xbox User Token to Minecraft-relying-party XSTS token

Request:

```http
POST https://xsts.auth.xboxlive.com/xsts/authorize
x-xbl-contract-version: 1
Content-Type: application/json

{
  "RelyingParty": "rp://api.minecraftservices.com/",
  "TokenType": "JWT",
  "Properties": {
    "SandboxId": "RETAIL",
    "UserTokens": [
      "<Xbox User Token>"
    ]
  }
}
```

The relevant response shape is again `Token` plus `DisplayClaims.xui[0].uhs`. This token is audience-bound to Minecraft
Services. The project compares the User Token and XSTS `uhs` values and rejects a mismatch.

Xbox can reject this step with an `XErr`, including cases where the Microsoft account has no Xbox profile, Xbox is not
available in the account region, or a child/family account requires authorization. The project exposes
`XboxAuthenticationException(stage, xerr, ...)` rather than treating these as Minecraft profile failures.

Project implementation: `MinecraftAccountService.authorizeXboxServices`, backed by
`XboxXstsAuthorizationOperation`.

### B3: XSTS token to Minecraft Services access token

There are two important Minecraft Services request shapes.

#### B3a: `authentication/login_with_xbox`

This is the route implemented by this project:

```http
POST https://api.minecraftservices.com/authentication/login_with_xbox
Content-Type: application/json

{
  "identityToken": "XBL3.0 x=<XSTS uhs>;<XSTS token>"
}
```

Relevant response fields:

```json
{
  "username": "<Minecraft Services account identifier>",
  "roles": [],
  "access_token": "<Minecraft Services access token>",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

`username` here is service-account metadata, not the Java profile name returned by B5. The exact lifetime is
provider-controlled; clients use `expires_in` rather than assuming the example value. A 403 at this stage commonly
indicates that Minecraft Services rejected the calling application registration. The project maps that response to
`MinecraftApplicationRegistrationException`. It consumes `access_token` and `expires_in`; other response fields remain
service metadata.

Project implementation: `MinecraftAccountService.loginToMinecraftServices`, backed by
`MinecraftXboxLoginOperation`.

#### B3b: `launcher/login`

The current official Launcher obtains its Minecraft-relying-party XToken through XAL and uses the launcher-flavoured
route:

```http
POST https://api.minecraftservices.com/launcher/login
Content-Type: application/json
Accept: application/json

{
  "xtoken": "XBL3.0 x=<XSTS uhs>;<XSTS token>",
  "platform": "PC_LAUNCHER"
}
```

Its response has the same essential Minecraft access-token fields, including `access_token`, `token_type`, and
`expires_in`, plus `username`/role metadata, and can include launcher-specific data. This route and B3a currently
coexist; the difference must not be described as merely renaming one JSON field. Current Java third-party
implementations provide a useful cross-check of that coexistence: Prismarine's Java token manager still emits B3a, while
the Java authentication library used by MCProtocolLib emits B3b.

This project does not expose B3b and does not consume launcher-specific response metadata. A caller that completes this
route elsewhere can hand the resulting Minecraft token and verified profile to `fromExistingCredentials`, described
below.

### B4: retrieve entitlements

The supported traditional route is:

```http
GET https://api.minecraftservices.com/entitlements/mcstore
Authorization: Bearer <Minecraft access token>
```

Relevant response shape:

```json
{
  "items": [
    {
      "name": "<entitlement name>",
      "signature": "<signature>"
    }
  ],
  "signature": "<optional response signature>",
  "keyId": "<optional key identifier>"
}
```

Entitlements describe products or access grants attached to the account. Their exact names and grant policy are
service-controlled; applications should not infer permanent ownership from one hard-coded item name. This project
returns the response as `MinecraftEntitlements`. It does not independently validate the returned signatures or reject an
otherwise valid response merely because `items` is empty.

The Launcher-oriented alternative is:

```http
GET https://api.minecraftservices.com/entitlements/license?requestId=<caller-generated UUID>
Authorization: Bearer <Minecraft access token>
Accept: application/json
Content-Type: application/json
```

It returns the same essential entitlement `items` family and can include response-signature data. The request ID is a
per-request correlation value; it is not an account or product identifier. Signature-validation policy is part of that
branch and must not be inferred from the fact that the JSON parser can read its item names. This route is not emitted by
`MinecraftAccountService` and is not implemented by the relay operation either.

Project implementation: `MinecraftAccountService.getEntitlements`, backed by `MinecraftEntitlementsOperation`.

### B5: retrieve the Java profile

```http
GET https://api.minecraftservices.com/minecraft/profile
Authorization: Bearer <Minecraft access token>
```

Relevant response shape:

```json
{
  "id": "<undashed Java profile UUID>",
  "name": "<Java profile name>",
  "skins": [
    {
      "id": "<skin id>",
      "state": "ACTIVE",
      "url": "<skin URL>",
      "variant": "CLASSIC",
      "alias": "<optional alias>"
    }
  ],
  "capes": [
    {
      "id": "<cape id>",
      "state": "ACTIVE",
      "url": "<cape URL>",
      "alias": "<optional alias>"
    }
  ]
}
```

A 404 means that the Microsoft/Xbox identity does not currently have a Java profile; it is mapped to
`MinecraftJavaProfileNotFoundException`. A successful response becomes `MinecraftAccountProfile`. Its name and UUID,
combined with the B3 Minecraft access token and expiry, become `MinecraftOnlineAccount`.

Entitlements and profile are sibling bearer-token queries at the protocol level; neither response is an input to the
other. `MinecraftAccountService` currently performs entitlements first and profile second and requires both HTTP
requests to succeed before returning `MinecraftAccountLoginResult`.

## Complete supported login examples

### Public-client Authorization Code + PKCE

```kotlin
val httpClient = HttpClient(CIO)
val application = MicrosoftOAuthApplication(
    clientId = appConfiguration.microsoftClientId,
    scopes = listOf(
        MicrosoftOAuthScope("xboxlive.signin"),
        MicrosoftOAuthScope("xboxlive.offline_access"),
    ),
)
val oauth = MicrosoftOAuthService(httpClient, application)
val accounts = MinecraftAccountService(httpClient)

val authorization = oauth.beginAuthorizationCodeLogin(
    redirectUri = Url("my-launcher://microsoft/callback"),
)
applicationUi.openUri(authorization.authorizationUri)

val microsoftTokens = oauth.completeAuthorizationCodeLogin(
    authorization = authorization,
    redirectedUri = applicationUi.awaitCallbackUri(),
)
val login = accounts.loginWithMicrosoftTokens(microsoftTokens)
val account = login.account
```

The application must close `httpClient` when the application-owned lifetime ends. The library never closes it.

### Device Code

```kotlin
val authorization = oauth.beginDeviceCodeLogin()

applicationUi.showDeviceCode(
    code = authorization.userCode,
    uri = authorization.verificationUriComplete ?: authorization.verificationUri,
)

val microsoftTokens = oauth.awaitDeviceCodeLogin(authorization)
val login = accounts.loginWithMicrosoftTokens(microsoftTokens)
```

### Existing, already verified Minecraft credential

If another trusted component already completed Minecraft Services login and verified the Java profile, Microsoft and
Xbox can be skipped entirely:

```kotlin
val account = MinecraftOnlineAccount.fromExistingCredentials(
    name = verifiedProfile.name,
    id = verifiedProfile.id,
    accessToken = minecraftAccessToken,
    expiresAt = minecraftTokenExpiry,
)
```

When the caller also restored the complete profile and entitlement metadata, it can construct the same result shape
without network I/O:

```kotlin
val login = accounts.existingAccount(
    account = account,
    profile = verifiedProfile,
    entitlements = restoredEntitlements,
)
```

The caller is responsible for establishing that the token, name, UUID, and metadata belong together. The factory
validates basic values; it cannot remotely re-verify a token without making the omitted service calls.

## Browser and relay topology

Cryptographic support does not bypass browser CORS. The browser deployment normally splits the routes:

| Operation                                                | Browser route                                    |
|----------------------------------------------------------|--------------------------------------------------|
| Authorization page                                       | Top-level Microsoft navigation                   |
| Authorization Code + PKCE redemption for an SPA redirect | Direct Microsoft token request from the browser  |
| Device authorization and polling                         | Application's trusted same-origin HTTPS relay    |
| Xbox User Token and XSTS                                 | Application's relay                              |
| Minecraft login, entitlement, and profile                | Application's relay                              |
| Session `/join` and `/hasJoined`                         | Application's relay when invoked by browser code |

Create a direct OAuth service for SPA PKCE and relay-routed services for the back-channel operations:

```kotlin
val directPkceOauth = MicrosoftOAuthService(browserHttpClient, application)

val relayEndpoint = Url("https://launcher.example/auth/minecraft")
val deviceOauth = MicrosoftOAuthService(browserHttpClient, application, relayEndpoint)
val accounts = MinecraftAccountService(browserHttpClient, relayEndpoint)
val sessions = MinecraftSessionService(browserHttpClient, relayEndpoint)
```

On the trusted backend, embed the framework-neutral handler in an application-owned endpoint:

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
```

The handler accepts only versioned, typed operations targeting fixed upstream HTTPS endpoints. The application still
owns caller authentication, CSRF protection, authorization, TLS, rate limits, body limits at the outer framework,
observability, and response writing. The relay is an HTTP trust boundary; it is unrelated to any future WebSocket or TCP
carrier for Minecraft packets.

Supported relay operations are enumerated by `MinecraftAuthenticationRelayOperation`:

- `MICROSOFT_DEVICE_AUTHORIZATION`
- `MICROSOFT_TOKEN`
- `XBOX_USER_AUTHENTICATION`
- `XBOX_XSTS_AUTHORIZATION`
- `MINECRAFT_XBOX_LOGIN`
- `MINECRAFT_ENTITLEMENTS`
- `MINECRAFT_PROFILE`
- `SESSION_JOIN`
- `SESSION_HAS_JOINED`

Authorization-page navigation is intentionally absent: it is a user-agent navigation, not a back-channel relay
operation.

## Credential storage and lifetime

`MinecraftOnlineAccount`, `MicrosoftAccessToken`, `MicrosoftRefreshToken`, and `MicrosoftOAuthTokens` redact secrets in
their string rendering. Explicit export methods cross the secret-storage boundary:

```kotlin
val minecraftCredentials = account.exportForSecureStorage()
val refreshCredentials = login.refreshToken?.exportForSecureStorage()

secureStore.save(minecraftCredentials, refreshCredentials)
```

Restore a Minecraft credential with:

```kotlin
val account = MinecraftOnlineAccount.fromSecureStorage(
    secureStore.loadMinecraftOnlineAccountCredentials(),
)
```

The exported envelopes themselves contain plaintext credential values. Redacted `toString()` is not encryption. Store
them in application-owned secure storage, never ordinary logs or source control. The module deliberately does not choose
Keychain, Credential Manager, Keystore, browser storage, a database, or a serialization format for the application.

## The later session-service boundary

Account login is complete before connecting to a game server. During an online-mode socket Login, the client computes a
server hash from the selected server's encryption challenge and registers its intent with the Mojang session server:

```http
POST https://sessionserver.mojang.com/session/minecraft/join
Content-Type: application/json

{
  "accessToken": "<Minecraft access token>",
  "selectedProfile": "<undashed Java profile UUID>",
  "serverId": "<signed SHA-1 server hash>"
}
```

A normal successful response has no content. Project mapping:

```kotlin
val sessions = MinecraftSessionService(httpClient)
sessions.join(account, serverHash)
```

The game server independently computes the same hash and asks:

```http
GET https://sessionserver.mojang.com/session/minecraft/hasJoined?
    username={requested-name}&
    serverId={same-server-hash}&
    ip={optional-observed-client-ip}
```

A successful response contains the authoritative profile UUID and signed profile properties. No match is represented by
an empty/not-found response. Project mapping:

```kotlin
val joined = sessions.hasJoined(
    username = requestedUsername,
    serverHash = serverHash,
    ipAddress = observedClientIp,
)
```

Only the session server sees the Minecraft access token. The selected game server never receives Microsoft, Xbox, XSTS,
refresh, or Minecraft access tokens. `protocol-auth` supplies `MinecraftEncryption`, `minecraftServerHash`, and
`MinecraftSessionService`; `protocol-client` and `protocol-server` own Login packet ordering and invoke those
capabilities, `protocol-session` owns protocol-state effects, and `protocol-transport` owns sockets and AES stream
encryption.

## Offline profiles

Offline identity generation is independent of every online step above:

```kotlin
val profile = offlineProfile(playerName)
val id = offlineUuid(playerName)
val restoredId = parseMinecraftUuid(profile.id.toDashedString())
```

An online authentication failure must not be silently converted into an offline login. The application or server chooses
offline behavior explicitly.

## HTTP ownership and failures

Every network service receives a caller-created Ktor `HttpClient`. The module installs no engine, changes no client
configuration, and never closes the client. Redirect policy, TLS, proxy, timeout, retry, cookie, logging, and lifecycle
choices remain application responsibilities.

Important public failure boundaries include:

| Failure                                       | Meaning                                                                                   |
|-----------------------------------------------|-------------------------------------------------------------------------------------------|
| `MicrosoftOAuthException`                     | OAuth rejection, malformed response, invalid state, expiry, or terminal device-flow error |
| `MicrosoftOAuthUnavailableException`          | Temporary Microsoft OAuth availability failure                                            |
| `XboxAuthenticationException`                 | Xbox User Token or XSTS rejection, with stage and optional `XErr`                         |
| `MinecraftApplicationRegistrationException`   | Minecraft Services rejected the application during XSTS-to-Minecraft login                |
| `MinecraftJavaProfileNotFoundException`       | Microsoft/Xbox account has no retrievable Java profile                                    |
| `MinecraftAuthenticationRejectedException`    | Non-temporary service rejection, with stage and status code                               |
| `MinecraftAuthenticationUnavailableException` | Rate limit or temporary upstream service failure                                          |

Authentication failures are terminal for that attempt. The library does not downgrade online login, switch client IDs,
change scopes, retry through a different credential, or fall back to offline identity.

## Protocol-to-source map

| Protocol responsibility                                                         | Project source                                                                                                                               |
|---------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Authorization Code + PKCE, Device Code, refresh, binding, and OAuth errors      | [`MicrosoftOAuthService.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MicrosoftOAuthService.kt)                               |
| Secret-bearing account and token models                                         | [`MinecraftAccountModels.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MinecraftAccountModels.kt)                             |
| Xbox User Token, XSTS, Minecraft login, entitlement, and profile orchestration  | [`MinecraftAccountService.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MinecraftAccountService.kt)                           |
| Exact direct endpoints, HTTP methods, headers, forms, JSON, and bearer requests | [`MinecraftAuthenticationHttpTransport.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MinecraftAuthenticationHttpTransport.kt) |
| Typed browser relay operations and fail-closed policy                           | [`MinecraftAuthenticationRelay.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MinecraftAuthenticationRelay.kt)                 |
| Session-server `/join` and `/hasJoined`                                         | [`MinecraftSessionService.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MinecraftSessionService.kt)                           |
| Server hash and Login challenge primitives                                      | [`MinecraftEncryption.kt`](src/commonMain/kotlin/com/hiczp/minecraft/protocol/auth/MinecraftEncryption.kt)                                   |
| Deterministic protocol tests with mocked HTTP responses                         | [`commonTest`](src/commonTest/kotlin/com/hiczp/minecraft/protocol/auth)                                                                      |

The endpoint operation classes are internal because callers should use the typed services, but their source is the
authoritative mapping from the raw HTTP examples in this document to the requests currently emitted by the module.

## References

- [Microsoft identity platform: Authorization Code flow and PKCE](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)
- [Microsoft identity platform: Device Authorization Grant](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)
- [Microsoft: Xbox services sign-in for title websites](https://learn.microsoft.com/en-us/gaming/gdk/docs/services/fundamentals/s2s-auth-calls/service-authentication/live-website-authentication)
- [Microsoft: Xbox authentication and access overview](https://learn.microsoft.com/en-us/gaming/gdk/docs/services/fundamentals/identity/auth/live-authentication-overview)
- [Microsoft: Xbox identity-token and XSTS authorization model](https://learn.microsoft.com/en-us/xbox/gdk/docs/reference/live/rest/additional/edsauthorization)
- [Minecraft Java Edition game-service API review/application process](https://help.minecraft.net/hc/en-us/articles/16254801392141)
- [Official Minecraft Launcher download](https://www.minecraft.net/en-us/download)
- [Official Minecraft Java version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Official Launcher XAL/OAuth log captured in Mojang issue MCL-24681](https://bugs-legacy.mojang.com/browse/MCL-24681)
- [Minecraft Wiki: Microsoft authentication](https://minecraft.wiki/w/Microsoft_authentication)
- [Minecraft Wiki: Java game-launch arguments and
  `clientId.txt`](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Launching_the_game)
- [Prism Launcher Java
  `launcher/login` request](https://github.com/PrismLauncher/PrismLauncher/blob/d909e0205d940cb2846fdab665aa3c69015303af/launcher/minecraft/auth/steps/LauncherLoginStep.cpp)
- [Prism Launcher Java
  `entitlements/license` request](https://github.com/PrismLauncher/PrismLauncher/blob/d909e0205d940cb2846fdab665aa3c69015303af/launcher/minecraft/auth/steps/EntitlementsStep.cpp)
- [node-minecraft-protocol Java Microsoft-authentication integration](https://github.com/PrismarineJS/node-minecraft-protocol/blob/aa23a03964bf84e2f7fe813818a4ec5b7b2a1270/src/client/microsoftAuth.js)
- [Prismarine Java token exchange using
  `authentication/login_with_xbox`](https://github.com/PrismarineJS/prismarine-auth/blob/b795199dc5fa26059655bb1bc91c7f7f2733b232/src/TokenManagers/MinecraftJavaTokenManager.js)
- [MCProtocolLib Java session-service implementation](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/auth/SessionService.java)
- [MinecraftAuth Java client ID and Microsoft scope constants](https://github.com/RaphiMC/MinecraftAuth/blob/e78c8b735b7cfc142b4a6f547d39ce1d71a170f4/src/main/java/net/raphimc/minecraftauth/msa/data/MsaConstants.java)
- [MinecraftAuth `t=`/
  `d=` Xbox RPS-ticket selection](https://github.com/RaphiMC/MinecraftAuth/blob/e78c8b735b7cfc142b4a6f547d39ce1d71a170f4/src/main/java/net/raphimc/minecraftauth/xbl/request/XblUserAuthenticateRequest.java)
- [MinecraftAuth XAL/SISU Java authorization request](https://github.com/RaphiMC/MinecraftAuth/blob/e78c8b735b7cfc142b4a6f547d39ce1d71a170f4/src/main/java/net/raphimc/minecraftauth/xbl/request/XblSisuAuthorizeRequest.java)
- [MinecraftAuth Java token exchange using
  `launcher/login`](https://github.com/RaphiMC/MinecraftAuth/blob/e78c8b735b7cfc142b4a6f547d39ce1d71a170f4/src/main/java/net/raphimc/minecraftauth/java/request/MinecraftLauncherLoginRequest.java)

The third-party sources above are protocol cross-checks where the official Launcher does not publish a complete wire
contract. They are not runtime dependencies of this module and are not a recommendation to move authentication out of
`protocol-auth`.
