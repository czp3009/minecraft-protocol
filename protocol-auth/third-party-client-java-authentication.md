# Minecraft: Java Edition Third-Party Public-Client Authentication HTTP Flow

## Scope

This document defines the HTTP flow from Microsoft authorization through Xbox and Minecraft authentication to the
Minecraft: Java Edition entitlements and profile. It describes a public client. “Client” means the generic protocol
participant rather than a particular product or version. Other Microsoft authorization flows are outside this profile;
see
[Microsoft authentication flow support](https://learn.microsoft.com/en-us/entra/msal/msal-authentication-flows).

## Registration prerequisites

First satisfy the Azure account, active subscription, **Application Developer** role, and tenant prerequisites in
Microsoft's
[application-registration guide](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app).
Then create the registration as follows:

1. In the Microsoft Entra admin center, open **Entra ID** > **App registrations** and select **New registration**.
2. Enter an application name and set **Supported account types** to **Personal Microsoft accounts only**.
3. To make the [Authorization Code Grant with PKCE](#1a-authorization-code-grant-with-pkce) available, under **Redirect
   URI (optional)** select **Public client/native (mobile & desktop)** and enter, for example,
   `http://127.0.0.1/oauth/callback`. A registration used only with
   the [Device Authorization Grant](#1b-device-authorization-grant) can leave the redirect URI unset.
4. Select **Register**. On **Overview**, record **Application (client) ID** as `client_id` and also record **Directory (
   tenant) ID** for the Minecraft Services review described below.
5. To make the [Device Authorization Grant](#1b-device-authorization-grant) available, open the registered application's
   details page, select **Authentication** under **Manage**, open **Settings**, enable **Allow public client flows**,
   and select **Save**. The Authorization Code Grant does not require this setting. A registration intended to make both
   grants available completes both steps 3 and 5.

Do not create a client secret or certificate credential. No additional Entra API permission is required by the requests
in this document.

The Entra registration is complete at this point. Separately, Mojang's
[Java Edition Game Service API Review or Application Process](https://help.minecraft.net/hc/en-us/articles/16254801392141)
states that new applications must be added to the Java Edition game-service API allow list and directs applicants to the
[AppID review form](https://aka.ms/mce-reviewappid). This review is not an Entra setting and does not appear in its
registration checklist. It does not gate Microsoft OAuth or Xbox authentication; until the application is allow-listed,
the Minecraft authentication request in section 4 is rejected with HTTP `403 Forbidden`.

## 1. Microsoft OAuth

This stage obtains `microsoft_access_token` and its expiry. It also obtains `microsoft_refresh_token` when offline
access is granted. For each interactive authorization operation, the client chooses exactly one path:

- [Authorization Code Grant with PKCE](#1a-authorization-code-grant-with-pkce), using a loopback redirect.
- [Device Authorization Grant](#1b-device-authorization-grant).

The paths are alternatives, not consecutive steps. Both produce the Microsoft credentials consumed by section 2.

### 1A. Authorization Code Grant with PKCE

#### 1A.1. Loopback endpoint and PKCE values

Before opening the browser, the client binds an HTTP listener only to `127.0.0.1` on an operating-system-assigned free
port; it does not bind a wildcard interface. The accepted URI path is `/oauth/callback`. The client then creates:

- `redirect_uri` as `http://127.0.0.1:{redirect_port}/oauth/callback`.
- `oauth_state` from 32 cryptographically random bytes encoded as unpadded base64url.
- `code_verifier` from another 32 cryptographically random bytes encoded as unpadded base64url.
- `code_challenge` as the unpadded base64url encoding of `SHA-256(ASCII(code_verifier))`.

When matching a loopback redirect URI against the registered URI, Microsoft Entra does not compare the port; the scheme,
loopback host, and path still have to match. The authorization request therefore supplies the listener's actual port
even though the registered URI omits it. The service redirects to that complete request URI, which is also reused
unchanged in the token request. See
[Microsoft's loopback redirect rules](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url#localhost-exceptions).

The pending operation has a bounded client-selected deadline. For an accepted callback, the listener remains open
through the token exchange and closes after the client has responded to the browser. A matching OAuth error, explicit
cancellation, or the deadline also closes it.

#### 1A.2. Authorization request

The client opens the following authorization request in the external system browser:

```http
GET /consumers/oauth2/v2.0/authorize?client_id={urlencoded_client_id}&response_type=code&redirect_uri={urlencoded_redirect_uri}&response_mode=query&scope=xboxlive.signin%20xboxlive.offline_access&state={urlencoded_oauth_state}&code_challenge={urlencoded_code_challenge}&code_challenge_method=S256 HTTP/1.1
Host: login.microsoftonline.com
```

The authorization service presents interactive HTML and can issue intermediate redirects while authentication and
consent are in progress. A successful interaction ends with an HTTP redirect to the exact loopback URI:

```http
HTTP/1.1 302 Found
Location: {redirect_uri}?code={urlencoded_authorization_code}&state={urlencoded_oauth_state}
```

An unsuccessful interaction ends with an HTTP redirect to the same loopback URI carrying an OAuth error:

```http
HTTP/1.1 302 Found
Location: {redirect_uri}?error={urlencoded_error}&error_description={urlencoded_description}&state={urlencoded_oauth_state}
```

The service can add optional OAuth diagnostic query parameters. They do not weaken the requirement to validate
`state`.

#### 1A.3. Loopback callback request

The successful redirect causes the browser to send the following request to the client-owned loopback listener:

```http
GET /oauth/callback?code={urlencoded_authorization_code}&state={urlencoded_oauth_state} HTTP/1.1
Host: 127.0.0.1:{redirect_port}
```

The callback request is accepted only when all of these conditions hold:

- The listener has one pending authorization operation.
- The method is `GET`.
- The parsed URI path is exactly `/oauth/callback`.
- Exactly one non-empty `state` parameter exists and exactly equals the pending `oauth_state`.
- Exactly one non-empty `code` parameter exists.
- No `error` parameter exists.

After accepting this request, the client assigns `authorization_code` and atomically moves the pending operation into
token exchange so that no later callback can reuse it. The browser continues waiting for the local HTTP response while
the client performs section 1A.4; receiving the authorization code alone does not complete the OAuth operation.

An invalid or unsolicited callback does not assign `authorization_code` and does not consume the pending operation:

```http
HTTP/1.1 400 Bad Request
Content-Type: text/plain; charset=utf-8

Authorization could not be completed. Return to the application.
```

An OAuth error callback consumes the pending operation only when exactly one non-empty `state` matches the pending
`oauth_state`, exactly one non-empty `error` exists, and no `code` exists. The operation terminates with the returned
`error` and optional `error_description`. The local HTTP response is the same generic 400 response and does not
reproduce either value.

#### 1A.4. Authorization-code exchange and browser response

While holding the accepted loopback request open, the client immediately submits the authorization code for a single
token exchange:

```http
POST /consumers/oauth2/v2.0/token HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id={urlencoded_client_id}&scope=xboxlive.signin%20xboxlive.offline_access&code={urlencoded_authorization_code}&redirect_uri={urlencoded_redirect_uri}&grant_type=authorization_code&code_verifier={urlencoded_code_verifier}
```

The Microsoft token endpoint returns the following successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "scope": "{granted_scope}",
  "expires_in": 3600,
  "ext_expires_in": 3600,
  "access_token": "{microsoft_access_token}",
  "refresh_token": "{microsoft_refresh_token}"
}
```

`access_token`, `token_type`, and `expires_in` are required, and `token_type` must be `Bearer`. `expires_in` is the
access-token lifetime in seconds; adding it to the receipt time assigns `microsoft_access_token_expires_at`.

`refresh_token` is present when offline access is granted. If it is absent, the current authentication can continue, but
silent renewal through section 7.1 is unavailable. `scope` records the actual granted scope set when present; its order
is not significant. `ext_expires_in` is optional metadata and does not replace `expires_in` for the normal freshness
check.

After validating the successful token response, the client returns a user-friendly confirmation page through the held
loopback request. This is a client-generated response to the browser rather than a Microsoft response; its presentation
is implementation-defined, and it contains no credential:

```http
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8

Authorization completed. Return to the application.
```

After sending this response, the client consumes the pending operation, closes the loopback listener, and completes the
Microsoft OAuth stage with `microsoft_access_token`, its expiry, and `microsoft_refresh_token` when one was returned.

When the exchange is rejected, the Microsoft token endpoint returns an OAuth error response:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "invalid_grant",
  "error_description": "{description}",
  "error_codes": [70000],
  "timestamp": "{timestamp}",
  "trace_id": "{trace_id}",
  "correlation_id": "{correlation_id}"
}
```

`error_codes`, `timestamp`, `trace_id`, and `correlation_id` are optional diagnostic members. If delivery of the token
request is ambiguous, the same one-time authorization code is not submitted again; start a new Authorization Code Grant
operation. After a failed or ambiguous exchange, the client returns a generic failure page to the held loopback request
using the 400 response defined in section 1A.3, then consumes the pending operation, closes the listener, and terminates
the OAuth operation. The client-generated page does not reproduce OAuth diagnostics or credentials.

### 1B. Device Authorization Grant

#### 1B.1. Device authorization request

```http
POST /consumers/oauth2/v2.0/devicecode HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id={urlencoded_client_id}&scope=xboxlive.signin%20xboxlive.offline_access
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "user_code": "{user_code}",
  "device_code": "{device_code}",
  "verification_uri": "{verification_uri}",
  "expires_in": 900,
  "interval": 5,
  "message": "{localized_instructions}"
}
```

`user_code`, `device_code`, `verification_uri`, and `expires_in` are required. `interval` is optional; when absent, the
active polling interval starts at five seconds. `message` is optional display text and is not parsed as protocol data.
Adding `expires_in` seconds to the receipt time assigns `device_code_expires_at`.

A failed request returns an OAuth error response:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "{error}",
  "error_description": "{description}"
}
```

#### 1B.2. User verification

Before or while opening the browser, the client displays `user_code` and `verification_uri` to the user. It can also
display `message` when present. The user must be able to copy the code and URI when automatic browser navigation is
unavailable. `device_code` is never displayed. After validating that `verification_uri` is an absolute HTTPS URI, the
client opens it in an external system browser.

The user enters `user_code`, authenticates, and grants or denies consent in the service-controlled browser flow. Any
HTML responses and intermediate redirects belong to that browser flow and are not parsed by the client. No credential is
returned directly through the browser; completion instead changes the token-polling result.

The Microsoft response does not include or support the standard's optional `verification_uri_complete` optimization, so
this path uses `verification_uri` together with `user_code`.

#### 1B.3. Device token polling

The first poll occurs no earlier than the active polling interval after the device authorization response. Every later
poll observes the current active interval.

```http
POST /consumers/oauth2/v2.0/token HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&client_id={urlencoded_client_id}&device_code={urlencoded_device_code}
```

Before a token is issued, the token endpoint can return an OAuth error response:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "{error}",
  "error_description": "{description}"
}
```

`error` determines whether polling continues:

| `error` value            | Meaning and client action                                                                                                                                                                                                          |
|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `authorization_pending`  | The user has not completed authorization. Wait for the active interval, then poll again.                                                                                                                                           |
| `slow_down`              | Authorization is still pending, but polling must slow down. Add five seconds to the active interval before the next poll and retain the increased interval for every later poll. Each later `slow_down` adds another five seconds. |
| `authorization_declined` | Microsoft reports that the user declined authorization. Stop polling.                                                                                                                                                              |
| `access_denied`          | The RFC 8628 denial error. Stop polling.                                                                                                                                                                                           |
| `bad_verification_code`  | Microsoft does not recognize the submitted `device_code`. Stop polling.                                                                                                                                                            |
| `expired_token`          | The `device_code` has expired. Stop polling; a new operation requires a new device authorization request.                                                                                                                          |
| Any other OAuth error    | The operation cannot continue under this profile. Stop polling and preserve the available diagnostics.                                                                                                                             |

`error_description` is optional diagnostic text and does not control polling. A transport timeout is not an OAuth error;
wait at least the active interval and apply backoff before polling again. Polling also stops at
`device_code_expires_at` or on explicit cancellation.

After authorization, the token endpoint returns the successful HTTP token response defined in section 1A.4. The shared
flow continues with its `access_token`; its optional `refresh_token`, `scope`, and `expires_in` have the same meanings
as in that section.

## 2. Xbox user authentication

The Microsoft access token is exchanged for an Xbox User Token. `http://auth.xboxlive.com` is the relying-party
identifier carried in the JSON body, not the address to which this request is sent:

```http
POST /user/authenticate HTTP/1.1
Host: user.auth.xboxlive.com
Content-Type: application/json
Accept: application/json
x-xbl-contract-version: 1

{
  "Properties": {
    "AuthMethod": "RPS",
    "SiteName": "user.auth.xboxlive.com",
    "RpsTicket": "d={microsoft_access_token}"
  },
  "RelyingParty": "http://auth.xboxlive.com",
  "TokenType": "JWT"
}
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "IssueInstant": "{issue_instant}",
  "NotAfter": "{not_after}",
  "Token": "{xbox_user_token}",
  "DisplayClaims": {
    "xui": [
      {
        "uhs": "{xbox_user_hash}"
      }
    ]
  }
}
```

`Token`, `NotAfter`, and `DisplayClaims.xui[0].uhs` are required and non-empty. `NotAfter` assigns
`xbox_user_token_expires_at`. `xbox_user_hash` is an ephemeral token-instance claim and is never used as a persistent
account identifier.

A rejected Xbox user-authentication or XSTS request returns a non-2xx response. When Xbox policy error data is
available, both endpoints use this form:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "Identity": "0",
  "XErr": 2148916238,
  "Message": "{message}",
  "Redirect": "{policy_uri}"
}
```

`XErr` is an open numeric error space and requires at least signed 64-bit range. `Redirect` is diagnostic or policy
metadata and is not followed automatically. The status code does not by itself identify which credential was rejected.
Section 7.2 defines credential recovery for both Xbox stages.

## 3. XSTS authorization for Minecraft Services

The Xbox User Token is exchanged for an XSTS token scoped to Minecraft Services.
`rp://api.minecraftservices.com/` is the relying-party identifier carried in the JSON body, not the address to which
this request is sent:

```http
POST /xsts/authorize HTTP/1.1
Host: xsts.auth.xboxlive.com
Content-Type: application/json
Accept: application/json
x-xbl-contract-version: 1

{
  "Properties": {
    "SandboxId": "RETAIL",
    "UserTokens": [
      "{xbox_user_token}"
    ]
  },
  "RelyingParty": "rp://api.minecraftservices.com/",
  "TokenType": "JWT"
}
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "IssueInstant": "{issue_instant}",
  "NotAfter": "{not_after}",
  "Token": "{xsts_token}",
  "DisplayClaims": {
    "xui": [
      {
        "uhs": "{xsts_user_hash}"
      }
    ]
  }
}
```

`Token`, `NotAfter`, and `DisplayClaims.xui[0].uhs` are required and non-empty. `NotAfter` assigns
`xsts_token_expires_at`. `xsts_user_hash` must exactly equal `xbox_user_hash`; a mismatch rejects the token chain. A
rejected request follows the shared Xbox error format and recovery rule in section 2.

## 4. Minecraft authentication

The XSTS token and its matching user hash form the Minecraft identity token
`XBL3.0 x={xsts_user_hash};{xsts_token}`:

```http
POST /authentication/login_with_xbox HTTP/1.1
Host: api.minecraftservices.com
Content-Type: application/json
Accept: application/json

{
  "identityToken": "XBL3.0 x={xsts_user_hash};{xsts_token}"
}
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "username": "{minecraft_service_user_identifier}",
  "roles": [],
  "access_token": "{minecraft_access_token}",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

`access_token`, `token_type`, and `expires_in` are required, and `token_type` must be `Bearer`. Adding `expires_in`
seconds to the receipt time assigns `minecraft_access_token_expires_at`.

`username` is a Minecraft Services identifier rather than the Java profile UUID or profile name. `roles` and `username`
are metadata and are not inputs to later requests.

An application ID that has not passed the registration review is rejected before a Minecraft access token is issued:

```http
HTTP/1.1 403 Forbidden
Content-Type: application/json

{
  "path": "/authentication/login_with_xbox",
  "errorMessage": "Invalid app registration, see https://aka.ms/AppRegInfo for more information"
}
```

Other rejected requests return a non-2xx response. A JSON error response uses the fields available for that failure:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "path": "/authentication/login_with_xbox",
  "error": "{error}",
  "errorMessage": "{message}",
  "developerMessage": "{developer_message}"
}
```

The response can omit diagnostic fields that are unavailable for the specific failure.

## 5. Minecraft entitlements

The Minecraft access token retrieves the account's current product and subscription entitlements:

```http
GET /entitlements/mcstore HTTP/1.1
Host: api.minecraftservices.com
Authorization: Bearer {minecraft_access_token}
Accept: application/json
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "name": "{entitlement_name}",
      "signature": "{entitlement_signature}"
    }
  ],
  "signature": "{aggregate_signature}",
  "keyId": "{key_id}"
}
```

`items` is required and can be empty. Every item name is retained, and unknown names do not invalidate the response.
`game_minecraft` is the Java play-access grant; this profile classifies the Java entitlement as present exactly when
that item occurs. Items such as `product_minecraft`, `product_game_pass_pc`, and `product_game_pass_ultimate` describe
product or subscription sources but do not replace the required play-access grant.

Entitlement names and grant policy are service-controlled and can evolve. The classification describes service semantics
rather than a permanent ownership proof.

The signature members are entitlement-attestation data. This profile classifies the HTTPS response by item name and does
not define signing-key discovery or rotation. Signature verification, when required, therefore needs a separately
defined validation policy.

When `game_minecraft` is absent, the terminal result is **Java entitlement missing** and the profile request is not
performed.

## 6. Minecraft: Java Edition profile

When `game_minecraft` is present, the same Minecraft access token retrieves the Java profile:

```http
GET /minecraft/profile HTTP/1.1
Host: api.minecraftservices.com
Authorization: Bearer {minecraft_access_token}
Accept: application/json
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "{minecraft_profile_id}",
  "name": "{minecraft_profile_name}",
  "skins": [
    {
      "id": "{skin_id}",
      "state": "ACTIVE",
      "url": "{skin_texture_url}",
      "variant": "CLASSIC",
      "alias": "{skin_alias}"
    }
  ],
  "capes": [
    {
      "id": "{cape_id}",
      "state": "ACTIVE",
      "url": "{cape_texture_url}",
      "alias": "{cape_alias}"
    }
  ]
}
```

`id` is the Java profile UUID encoded as 32 hexadecimal digits without hyphens. `name` is the current Java profile name.
`skins` and `capes` are required arrays and can be empty. Unknown skin and cape members are ignored.

An account without an available Java profile returns:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "path": "/minecraft/profile",
  "error": "NOT_FOUND",
  "errorMessage": "The server has not found anything matching the request URI"
}
```

A profile 404 does not by itself prove that the account lacks a Java entitlement. An entitled account can still lack a
provisioned Java profile. The normal classification therefore preserves the positive `mcstore` entitlement result and
reports **Java profile missing**.

### 6.1. Optional license diagnostic

This request is not part of the normal path and is never required to establish a successful result. After a profile 404,
it can be made once to diagnose the already observed combination of a positive `mcstore` result and a missing profile:

```http
GET /entitlements/license HTTP/1.1
Host: api.minecraftservices.com
Authorization: Bearer {minecraft_access_token}
Accept: application/json
```

A successful response has the same HTTP response form as section 5, although the two endpoints need not return the same
items. `items` and every consumed item `name` are the only members used by this diagnostic. Unknown members do not
invalidate the response. The diagnostic result is interpreted as follows:

- If `items` contains `game_minecraft`, it agrees with the earlier positive `mcstore` result. Because the profile
  request still returned 404, the outcome remains **Java profile missing**.
- If `items` does not contain `game_minecraft`, it contradicts the earlier positive `mcstore` result. The result is
  **entitlement information inconsistent**, while retaining the observed profile 404.
- A non-2xx or malformed response does not erase either earlier fact. The result remains **Java profile missing**, with
  the license diagnostic failure preserved separately.

## 7. Credential renewal and recovery

### 7.1. Microsoft access-token refresh

When `microsoft_access_token` is no longer fresh and `microsoft_refresh_token` is available, the client obtains a
replacement Microsoft access token:

```http
POST /consumers/oauth2/v2.0/token HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id={urlencoded_client_id}&grant_type=refresh_token&refresh_token={urlencoded_microsoft_refresh_token}&scope=xboxlive.signin%20xboxlive.offline_access
```

The token endpoint returns the same successful and error HTTP response forms defined in section 1A.4. After a complete
successful response has been validated, its `access_token` and `expires_in` replace the Microsoft access-token state. A
returned `refresh_token` atomically replaces the previous refresh token; if it is omitted, the previous refresh token is
retained unless a terminal error has established that it is unusable.

`invalid_grant`, `interaction_required`, and equivalent terminal errors make silent renewal unavailable for the current
credential state and require a new section 1 operation.

### 7.2. Rebuilding downstream credentials

Microsoft, Xbox User, XSTS, and Minecraft access tokens have independent expirations. Refreshing a Microsoft access
token does not itself assert that an already-issued downstream token has expired. When a credential must be rebuilt, the
flow starts with the nearest still-fresh input:

| Fresh input available             | Resume at                                 |
|-----------------------------------|-------------------------------------------|
| `xsts_token`                      | Section 4, Minecraft authentication       |
| `xbox_user_token`                 | Section 3, XSTS authorization             |
| `microsoft_access_token`          | Section 2, Xbox user authentication       |
| `microsoft_refresh_token` only    | Section 7.1, then section 2               |
| No renewable Microsoft credential | Section 1, selecting one interactive path |

A service rejection does not prove expiry solely from its status code. The client can rebuild the rejected credential
chain once from the nearest fresh parent. If the same request is rejected again, the structured service error is
terminal for that operation rather than starting an unbounded recovery loop.

## 8. Authentication outcome

The flow terminates with one of these protocol-level results:

- **Authentication succeeded**: the Minecraft access token is fresh, `mcstore` contains `game_minecraft`, and the
  profile request returned 200 with a valid non-empty profile ID and name. The result contains the access token and its
  expiry, the complete entitlement list, the profile ID and name, and the returned skin and cape arrays.
- **Java entitlement missing**: `mcstore` did not contain `game_minecraft`; no profile request was required.
- **Java profile missing**: `mcstore` contained `game_minecraft`, but the profile request returned 404. A failed
  optional license diagnostic does not replace this result.
- **Entitlement information inconsistent**: the optional license diagnostic contradicted the earlier positive
  `mcstore` result.
- **Protocol or service failure**: another terminal failure occurred. Its stage, HTTP status, and available structured
  diagnostics are preserved.

The Minecraft access token is consumed by the Minecraft session service, including the later join operation. It is not
sent to a Minecraft game server in a Login packet. Game-session joining is outside this HTTP flow.

## 9. Request sequence

```text
Microsoft OAuth — select exactly one path for this operation

  Authorization Code + PKCE ----+
                                 +----> Microsoft access token
  Device Authorization ---------+      and optional refresh token
                                              |
                                              v
                             POST /user/authenticate
                                              |
                                              v
                                  Xbox User Token
                                              |
                                              v
                              POST /xsts/authorize
                                              |
                                              v
                          Minecraft-scoped XSTS token
                                              |
                                              v
                    POST /authentication/login_with_xbox
                                              |
                                              v
                                Minecraft access token
                                              |
                                              v
                              GET /entitlements/mcstore
                                   |                    |
                      no game_minecraft                 | game_minecraft
                                   |                    v
                                   |         GET /minecraft/profile
                                   |              |             |
                                   |              | 200         | 404
                                   |              v             v
                                   |       Authentication   Java profile
                                   |         succeeded       missing
                                   |                            |
                                   |                            +-- optional diagnostic -->
                                   |                                  GET /entitlements/license
                                   |                                    |-- contains grant --> remains profile missing
                                   |                                    |-- omits grant ----> entitlement information inconsistent
                                   |                                    +-- request fails --> remains profile missing; preserve failure
                                   v
                         Java entitlement missing

Expired or rejected credentials enter section 7 as a conditional recovery path.
```

## Protocol references

- [Microsoft identity platform authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)
- [Microsoft identity platform device-authorization flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)
- [Microsoft redirect URI restrictions](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url)
- [RFC 6749: OAuth 2.0](https://datatracker.ietf.org/doc/html/rfc6749)
- [RFC 7636: PKCE](https://datatracker.ietf.org/doc/html/rfc7636)
- [RFC 8252: OAuth 2.0 for Native Apps](https://datatracker.ietf.org/doc/html/rfc8252)
- [RFC 8628: OAuth 2.0 Device Authorization Grant](https://datatracker.ietf.org/doc/html/rfc8628)
- [Xbox services sign-in for title websites](https://learn.microsoft.com/en-us/gaming/gdk/docs/services/fundamentals/s2s-auth-calls/service-authentication/live-website-authentication)
- [Xbox services security tokens](https://learn.microsoft.com/en-us/gaming/gdk/docs/services/fundamentals/s2s-auth-calls/service-authentication/security-tokens/live-security-tokens)
- [Minecraft Wiki: Microsoft authentication](https://minecraft.wiki/w/Microsoft_authentication)
