# Minecraft: Java Edition Third-Party Public-Client Authentication HTTP Flow

## Scope

This document defines the HTTP flow that a third-party native public client implements to obtain a Microsoft access
token, exchange it for Xbox security tokens, obtain a Minecraft access token, read Minecraft entitlements, and retrieve
the Minecraft: Java Edition profile.

Exactly one of the following Microsoft OAuth grants starts the flow:

- Authorization Code Grant with PKCE and a loopback redirect.
- Device Authorization Grant.

Both grants produce the same `microsoft_access_token` and `microsoft_refresh_token`. Every request after Microsoft OAuth
is identical for both grants. Other Microsoft identity-platform grants are outside this protocol profile. Their catalog
is available
in [Microsoft authentication flow support](https://learn.microsoft.com/en-us/entra/msal/msal-authentication-flows).

The client is a public client. It never creates, embeds, sends, or stores a client secret.

## Registration prerequisites

The client operator completes these one-time registration steps before issuing runtime requests:

1. Open the [Microsoft Entra admin center](https://entra.microsoft.com/) and
   [register an application](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app).
2. Set **Supported account types** to **Personal Microsoft accounts only**.
3. Record the assigned **Application (client) ID** as `client_id`.
4. Configure the registration as a public native client. Under **Authentication**, add the **Mobile and desktop
   applications** platform and enable **Allow public client flows**. The Microsoft configuration procedure is defined in
   [Enable public-client flow](https://learn.microsoft.com/en-us/entra/identity-platform/scenario-mobile-app-configuration#enable-public-client-flow).
5. When Authorization Code Grant support is enabled, register the loopback `redirect_uri` defined in this document.
   Microsoft Entra requires a registered redirect URI with the same scheme, loopback host, and path; native-loopback
   ports are selected at runtime. The applicable configuration and restrictions are defined in
   [Add a redirect URI](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-redirect-uri) and
   [Redirect URI restrictions](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url#localhost-exceptions).
   An HTTP redirect URI using the `127.0.0.1` literal is added through the application manifest, as specified by those
   restrictions.
6. Do not add a client secret. A distributed native client cannot keep one confidential, and none of the token requests
   in this flow accepts or sends one.
7. Submit `client_id` through the
   [Minecraft Services application review form](https://aka.ms/mce-reviewappid). Minecraft Services access begins only
   after that application ID is approved.

## Fixed protocol values

| Name                                  | Value                                                                |
|---------------------------------------|----------------------------------------------------------------------|
| Microsoft tenant                      | `consumers`                                                          |
| Authorization endpoint                | `https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize`  |
| Token endpoint                        | `https://login.microsoftonline.com/consumers/oauth2/v2.0/token`      |
| Device authorization endpoint         | `https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode` |
| OAuth scope                           | `XboxLive.signin XboxLive.offline_access`                            |
| Xbox user-authentication endpoint     | `https://user.auth.xboxlive.com/user/authenticate`                   |
| Xbox user relying party               | `http://auth.xboxlive.com`                                           |
| XSTS endpoint                         | `https://xsts.auth.xboxlive.com/xsts/authorize`                      |
| XSTS sandbox                          | `RETAIL`                                                             |
| Minecraft relying party               | `rp://api.minecraftservices.com/`                                    |
| Minecraft authentication endpoint     | `https://api.minecraftservices.com/authentication/login_with_xbox`   |
| Minecraft store-entitlements endpoint | `https://api.minecraftservices.com/entitlements/mcstore`             |
| Minecraft license endpoint            | `https://api.minecraftservices.com/entitlements/license`             |
| Minecraft profile endpoint            | `https://api.minecraftservices.com/minecraft/profile`                |
| Xbox contract version                 | `1`                                                                  |

`client_id` is the application identifier assigned to the public-client registration. The registration accepts personal
Microsoft accounts, enables public-client flows, is permitted to request the Xbox scopes above, and is authorized to
call Minecraft Services.

## Runtime values

| Value                               | Creation or source                                              | Consumer                                              |
|-------------------------------------|-----------------------------------------------------------------|-------------------------------------------------------|
| `client_id`                         | Public-client registration                                      | Every Microsoft OAuth request                         |
| `redirect_port`                     | Operating-system-assigned free TCP port                         | Authorization Code Grant                              |
| `redirect_uri`                      | `http://127.0.0.1:{redirect_port}/oauth/callback`               | Authorization request and code exchange               |
| `oauth_state`                       | 32 cryptographically random bytes encoded as unpadded base64url | Authorization request and callback validation         |
| `code_verifier`                     | 32 cryptographically random bytes encoded as unpadded base64url | PKCE authorization request and code exchange          |
| `code_challenge`                    | Unpadded base64url encoding of `SHA-256(ASCII(code_verifier))`  | Authorization request                                 |
| `authorization_code`                | Successful loopback callback query                              | Authorization-code token request                      |
| `device_code`                       | Device authorization response                                   | Device token polling                                  |
| `user_code`                         | Device authorization response                                   | Microsoft verification page                           |
| `verification_uri`                  | Device authorization response                                   | External user-agent navigation                        |
| `device_poll_interval`              | Device authorization response `interval`                        | Device token polling schedule                         |
| `device_code_expires_at`            | Receipt time plus device authorization response `expires_in`    | Device token polling deadline                         |
| `microsoft_access_token`            | Successful Microsoft token response                             | Xbox user authentication                              |
| `microsoft_refresh_token`           | Successful Microsoft token response                             | Microsoft token refresh                               |
| `microsoft_access_token_expires_at` | Receipt time plus Microsoft `expires_in`                        | Token freshness check                                 |
| `xbox_user_token`                   | Xbox user-authentication response `Token`                       | XSTS request                                          |
| `xbox_user_hash`                    | Xbox user-authentication response `DisplayClaims.xui[0].uhs`    | XSTS identity consistency check                       |
| `xbox_user_token_expires_at`        | Xbox user-authentication response `NotAfter`                    | Token freshness check                                 |
| `xsts_token`                        | XSTS response `Token`                                           | Minecraft authentication                              |
| `xsts_user_hash`                    | XSTS response `DisplayClaims.xui[0].uhs`                        | Minecraft `identityToken`                             |
| `xsts_token_expires_at`             | XSTS response `NotAfter`                                        | Token freshness check                                 |
| `minecraft_access_token`            | Minecraft authentication response `access_token`                | Entitlements, profile, and authenticated game session |
| `minecraft_token_type`              | Minecraft authentication response `token_type`                  | Bearer authorization scheme                           |
| `minecraft_access_token_expires_at` | Receipt time plus Minecraft `expires_in`                        | Token freshness check                                 |
| `minecraft_entitlements`            | Minecraft entitlements response `items`                         | Java entitlement classification                       |
| `java_entitled`                     | Classification of `minecraft_entitlements`                      | Completed authentication result                       |
| `minecraft_profile_id`              | Minecraft profile response `id`                                 | Java profile UUID                                     |
| `minecraft_profile_name`            | Minecraft profile response `name`                               | Java profile name                                     |

All tokens and authorization codes are opaque, case-sensitive strings. No request parses a token to derive identity,
expiry, or authorization. Expiry comes only from `expires_in` or `NotAfter` fields returned by the issuing service.
Names beginning with `urlencoded_` denote the form-URL-encoded serialization of the corresponding unprefixed value in
the same request or response.
`verification_uri_host` and `verification_uri_path_and_query` are parsed directly from `verification_uri`.

The following placeholders occur only in response examples. They name response data rather than values created by the
client:

| Placeholder                         | Response member or meaning                                                  |
|-------------------------------------|-----------------------------------------------------------------------------|
| `description`                       | Microsoft `error_description`                                               |
| `error`                             | The surrounding response's `error` member                                   |
| `timestamp`                         | Microsoft OAuth diagnostic `timestamp`                                      |
| `trace_id`                          | Microsoft OAuth diagnostic `trace_id`                                       |
| `correlation_id`                    | Microsoft OAuth diagnostic `correlation_id`                                 |
| `localized_instructions`            | Device authorization response `message`                                     |
| `issue_instant`                     | Xbox response `IssueInstant`                                                |
| `not_after`                         | Xbox response `NotAfter`                                                    |
| `message`                           | The surrounding response's `Message` or `errorMessage` member               |
| `policy_uri`                        | Xbox error response `Redirect` member; it is metadata, not an HTTP redirect |
| `minecraft_service_user_identifier` | Minecraft authentication response `username`                                |
| `developer_message`                 | Minecraft service response `developerMessage`                               |
| `entitlement_name`                  | Entitlement item `name`                                                     |
| `entitlement_signature`             | Entitlement item `signature`                                                |
| `aggregate_signature`               | Top-level entitlement response `signature`                                  |
| `key_id`                            | Top-level entitlement response `keyId`                                      |
| `license_source`                    | License item `source`, such as a purchase or subscription source            |
| `skin_id`                           | Profile skin `id`                                                           |
| `skin_texture_url`                  | Profile skin `url`                                                          |
| `skin_alias`                        | Profile skin `alias`                                                        |
| `cape_id`                           | Profile cape `id`                                                           |
| `cape_texture_url`                  | Profile cape `url`                                                          |
| `cape_alias`                        | Profile cape `alias`                                                        |

A response value not listed as a runtime value is retained as response metadata and is not consumed by a later request.

## HTTP invariants

- Every remote connection uses HTTPS with normal hostname and certificate validation.
- Form bodies use UTF-8 `application/x-www-form-urlencoded` encoding.
- JSON request bodies use UTF-8 and contain no comments or trailing commas.
- Microsoft OAuth token responses and the loopback response use `Cache-Control: no-store`. Every remote token response
  is handled as non-cacheable sensitive data even when the service omits an explicit cache header.
- Tokens never appear in URLs, query parameters, error messages, or logs. The authorization code and `oauth_state` are
  the only credentials received through the loopback query.
- `Authorization` header values use the exact scheme and spacing shown below.
- Unknown JSON members are retained or ignored without rejecting an otherwise valid response. Required members are
  validated before their values are consumed.

## 1. Microsoft OAuth entry

### 1A. Authorization Code Grant with PKCE

#### 1A.1. Loopback endpoint and PKCE values

The client binds an HTTP listener only to `127.0.0.1` on an operating-system-assigned free port. The listener accepts
only `/oauth/callback`. It then creates `redirect_uri`, `oauth_state`, `code_verifier`, and `code_challenge` as defined
in the runtime-value table.

#### 1A.2. Authorization request

The system browser navigates to the authorization endpoint with these query parameters:

| Parameter               | Value                                     |
|-------------------------|-------------------------------------------|
| `client_id`             | `client_id`                               |
| `response_type`         | `code`                                    |
| `redirect_uri`          | `redirect_uri`                            |
| `response_mode`         | `query`                                   |
| `scope`                 | `XboxLive.signin XboxLive.offline_access` |
| `state`                 | `oauth_state`                             |
| `code_challenge`        | `code_challenge`                          |
| `code_challenge_method` | `S256`                                    |
| `prompt`                | `select_account`                          |

The resulting request has this form:

```http
GET /consumers/oauth2/v2.0/authorize?client_id={urlencoded_client_id}&response_type=code&redirect_uri={urlencoded_redirect_uri}&response_mode=query&scope=XboxLive.signin%20XboxLive.offline_access&state={urlencoded_oauth_state}&code_challenge={urlencoded_code_challenge}&code_challenge_method=S256&prompt=select_account HTTP/1.1
Host: login.microsoftonline.com
```

Request body: no body.

The authorization endpoint returns Microsoft-hosted HTML pages and HTTP redirects while authentication and consent are
in progress. A successful interaction ends with this redirect:

```http
HTTP/1.1 302 Found
Location: {redirect_uri}?code={urlencoded_authorization_code}&state={urlencoded_oauth_state}
Cache-Control: no-store
```

Response body: no body is required for the final redirect.

An unsuccessful interaction ends with this redirect:

```http
HTTP/1.1 302 Found
Location: {redirect_uri}?error={urlencoded_error}&error_description={urlencoded_description}&state={urlencoded_oauth_state}
Cache-Control: no-store
```

Response body: no body is required for the final redirect.

#### 1A.3. Loopback callback

The successful redirect causes the browser to send:

```http
GET /oauth/callback?code={urlencoded_authorization_code}&state={urlencoded_oauth_state} HTTP/1.1
Host: 127.0.0.1:{redirect_port}
```

Request body: no body.

The callback is accepted only when all of these conditions hold:

- The listener has one pending authorization operation.
- The request target is `/oauth/callback`.
- The `Host` port equals `redirect_port`.
- `state` exactly equals the pending `oauth_state`.
- Exactly one non-empty `code` parameter exists.
- No `error` parameter exists.

The accepted callback assigns `authorization_code` from `code`, consumes the pending operation, and returns:

```http
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Cache-Control: no-store
Connection: close

Authentication completed. Return to the application.
```

An invalid callback does not assign `authorization_code` and returns:

```http
HTTP/1.1 400 Bad Request
Content-Type: text/plain; charset=utf-8
Cache-Control: no-store
Connection: close

Invalid authentication callback.
```

An OAuth error callback terminates the pending operation with the returned `error` and `error_description`. The local
HTTP response is the same 400 response shown above and does not reproduce either value.

#### 1A.4. Authorization-code token request

The client immediately exchanges the accepted authorization code:

```http
POST /consumers/oauth2/v2.0/token HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id={urlencoded_client_id}&scope=XboxLive.signin%20XboxLive.offline_access&code={urlencoded_authorization_code}&redirect_uri={urlencoded_redirect_uri}&grant_type=authorization_code&code_verifier={urlencoded_code_verifier}
```

The request contains no `client_secret`.

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
Pragma: no-cache

{
  "token_type": "Bearer",
  "scope": "XboxLive.signin XboxLive.offline_access",
  "expires_in": 3600,
  "ext_expires_in": 3600,
  "access_token": "{microsoft_access_token}",
  "refresh_token": "{microsoft_refresh_token}"
}
```

The numeric values above illustrate the response shape. The client uses the returned `expires_in` value and never
assumes a fixed lifetime. `ext_expires_in` is retained when present but does not replace `expires_in` for normal expiry.
`access_token`, `refresh_token`, and `token_type` assign `microsoft_access_token`, `microsoft_refresh_token`, and the
Microsoft bearer scheme. The receipt time plus `expires_in` assigns `microsoft_access_token_expires_at`.

A failed exchange returns an OAuth error object:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
Pragma: no-cache

{
  "error": "invalid_grant",
  "error_description": "{description}",
  "error_codes": [70000],
  "timestamp": "{timestamp}",
  "trace_id": "{trace_id}",
  "correlation_id": "{correlation_id}"
}
```

`error_codes`, `timestamp`, `trace_id`, and `correlation_id` are optional diagnostic members. An unsuccessful exchange
does not continue to Xbox authentication.

### 1B. Device Authorization Grant

#### 1B.1. Device authorization request

```http
POST /consumers/oauth2/v2.0/devicecode HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id={urlencoded_client_id}&scope=XboxLive.signin%20XboxLive.offline_access
```

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
Pragma: no-cache

{
  "user_code": "{user_code}",
  "device_code": "{device_code}",
  "verification_uri": "{verification_uri}",
  "expires_in": 900,
  "interval": 5,
  "message": "{localized_instructions}"
}
```

The numeric values above illustrate the response shape. The receipt time plus the returned `expires_in` assigns
`device_code_expires_at`; the returned `interval` assigns `device_poll_interval`. `message` is display text and is not
parsed as protocol data.

A failed request returns:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
Pragma: no-cache

{
  "error": "{error}",
  "error_description": "{description}"
}
```

No device polling starts after an unsuccessful response.

#### 1B.2. User verification navigation

The system browser navigates to `verification_uri`:

```http
GET {verification_uri_path_and_query} HTTP/1.1
Host: {verification_uri_host}
```

Request body: no body.

Microsoft returns interactive HTML and redirects that collect `user_code`, account authentication, and consent. This
browser exchange does not return credentials to the local client. Completion changes the result returned by the token
polling endpoint.

Response headers: Microsoft-controlled browser headers, including the content type or redirect location for each page.

Response body: Microsoft-hosted HTML on interactive responses; no body is required on redirects.

#### 1B.3. Device token polling

The first poll occurs after `device_poll_interval` seconds. Every later poll observes the active interval.

```http
POST /consumers/oauth2/v2.0/token HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&client_id={urlencoded_client_id}&device_code={urlencoded_device_code}
```

While user interaction is incomplete, the response is:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
Pragma: no-cache

{
  "error": "authorization_pending",
  "error_description": "{description}"
}
```

`authorization_pending` schedules another poll after the active interval. `slow_down` increases the active interval by
five seconds before another poll. `authorization_declined`, `bad_verification_code`, `expired_token`, and every other
OAuth error terminate the device operation. Polling also terminates at `device_code_expires_at`.

After authorization, the token endpoint returns the same successful JSON token response defined in section 1A.4. Its
`access_token`, `refresh_token`, and `expires_in` values assign `microsoft_access_token`, `microsoft_refresh_token`, and
`microsoft_access_token_expires_at`.

## 2. Microsoft access-token refresh

When the current time reaches five minutes before `microsoft_access_token_expires_at`, a stored Microsoft session
obtains a replacement access token with the most recent refresh token:

```http
POST /consumers/oauth2/v2.0/token HTTP/1.1
Host: login.microsoftonline.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id={urlencoded_client_id}&grant_type=refresh_token&refresh_token={urlencoded_microsoft_refresh_token}&scope=XboxLive.signin%20XboxLive.offline_access
```

Request body fields contain no `client_secret`.

The successful response has the same headers and JSON shape as section 1A.4. The returned `access_token` replaces
`microsoft_access_token`. The returned `refresh_token` atomically replaces the previously stored refresh token. The
returned `expires_in` establishes the new `microsoft_access_token_expires_at`.

An `invalid_grant`, `interaction_required`, or equivalent terminal OAuth error invalidates the stored Microsoft session
and requires a new section 1 OAuth operation.

Every successful Microsoft refresh invalidates cached `xbox_user_token`, `xsts_token`, and `minecraft_access_token`.
Sections 3 through 7 then run again to produce a coherent downstream token set.

## 3. Xbox user authentication

The Microsoft access token is exchanged for an Xbox User Token:

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
Content-Type: application/json; charset=utf-8

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

`Token`, `NotAfter`, and `DisplayClaims.xui[0].uhs` are required and non-empty. `xbox_user_hash` is an ephemeral token
claim and is never used as a persistent account identifier. `NotAfter` assigns `xbox_user_token_expires_at`.

A rejected request returns a non-2xx status and a JSON error body. Xbox policy failures use this shape:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json; charset=utf-8

{
  "Identity": "0",
  "XErr": 2148916238,
  "Message": "{message}",
  "Redirect": "{policy_uri}"
}
```

`XErr` is an open numeric error space. `Redirect` is error metadata, not an HTTP redirect, and is not followed
automatically. A 401 caused by an expired Microsoft token starts section 2 when a refresh token is available.

## 4. XSTS authorization for Minecraft Services

The Xbox User Token is exchanged for an XSTS token scoped to Minecraft Services:

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
Content-Type: application/json; charset=utf-8

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

`Token`, `NotAfter`, and `DisplayClaims.xui[0].uhs` are required and non-empty. `xsts_user_hash` must exactly equal
`xbox_user_hash`. `NotAfter` assigns `xsts_token_expires_at`. A user-hash mismatch invalidates the entire token set.

A rejected request returns a non-2xx status and a JSON body containing `Identity`, `XErr`, `Message`, and `Redirect`
when an Xbox policy error is available. A 401 caused by an expired Xbox User Token restarts section 3 with a valid
Microsoft access token.

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json; charset=utf-8

{
  "Identity": "0",
  "XErr": 2148916238,
  "Message": "{message}",
  "Redirect": "{policy_uri}"
}
```

## 5. Minecraft authentication

The XSTS token and its user hash are combined into the Minecraft identity token:

```text
XBL3.0 x={xsts_user_hash};{xsts_token}
```

That value is exchanged for a Minecraft access token:

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
Content-Type: application/json; charset=utf-8

{
  "username": "{minecraft_service_user_identifier}",
  "roles": [],
  "access_token": "{minecraft_access_token}",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

The numeric lifetime above illustrates the response shape. The returned `expires_in` establishes
`minecraft_access_token_expires_at`. `username` is a Minecraft Services identifier and is not the Java profile UUID or
profile name. `roles` and `username` are retained but are not inputs to later requests.

A rejected request returns a non-2xx status. A JSON error response uses the service error fields available for that
failure:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json; charset=utf-8

{
  "path": "/authentication/login_with_xbox",
  "error": "{error}",
  "errorMessage": "{message}",
  "developerMessage": "{developer_message}"
}
```

The response can omit fields that are unavailable for the specific failure. A failure caused by expired Xbox credentials
restarts section 3.

## 6. Minecraft entitlements

The Minecraft access token retrieves the account's current product and subscription entitlements:

```http
GET /entitlements/mcstore HTTP/1.1
Host: api.minecraftservices.com
Authorization: Bearer {minecraft_access_token}
Accept: application/json
```

Request body: no body.

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

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

`items` is required and can be empty. Every item name is retained. Unknown item names do not invalidate the response.
The known Java entitlement classification is:

- `game_minecraft` is the entitlement that grants Java play access. `java_entitled` is true exactly when this item is
  present.
- `product_minecraft` identifies the Minecraft product attached to a perpetual purchase.
- `product_game_pass_pc` and `product_game_pass_ultimate` identify the subscription source when access is supplied by
  Xbox Game Pass. These source items do not replace the required `game_minecraft` play entitlement.

The returned `items` array assigns `minecraft_entitlements`; the classification above assigns `java_entitled`.

The item signatures, aggregate signature, and key identifier are entitlement-attestation data. No later HTTP request
uses them as request inputs.

A missing or invalid Minecraft access token returns `401 Unauthorized`. The response has no body or a JSON service-error
body according to the failure. A 401 restarts section 2 when a Microsoft refresh token is available.

## 7. Minecraft: Java Edition profile

The same Minecraft access token retrieves the Java profile:

```http
GET /minecraft/profile HTTP/1.1
Host: api.minecraftservices.com
Authorization: Bearer {minecraft_access_token}
Accept: application/json
```

Request body: no body.

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

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
`skins` and `capes` are required arrays and can be empty. Unknown skin and cape members are retained or ignored.

An account without an available Java profile returns:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

{
  "path": "/minecraft/profile",
  "error": "NOT_FOUND",
  "errorMessage": "The server has not found anything matching the request URI"
}
```

### 7.1. License classification after a missing profile

A profile 404 triggers one license-classification request:

```http
GET /entitlements/license HTTP/1.1
Host: api.minecraftservices.com
Authorization: Bearer {minecraft_access_token}
Accept: application/json
```

Request body: no body.

A successful response is:

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "items": [
    {
      "name": "{entitlement_name}",
      "source": "{license_source}"
    }
  ],
  "signature": "{aggregate_signature}",
  "keyId": "{key_id}",
  "errors": []
}
```

`errors` is optional and contains service-defined diagnostic objects when non-empty. The license response is classified
by the presence of `game_minecraft` in its `items` array.

- A `game_minecraft` item with a profile 404 produces `JAVA_PROFILE_MISSING`.
- No `game_minecraft` item with a profile 404 produces `JAVA_ENTITLEMENT_MISSING`.

A 401 response restarts section 2 when a Microsoft refresh token is available. Other non-2xx responses preserve the
profile result as `JAVA_PROFILE_MISSING_OR_UNCLASSIFIED` and expose the HTTP failure separately.

## 8. Completed authentication result

The completed result contains:

- `minecraft_access_token` and `minecraft_access_token_expires_at`.
- `minecraft_token_type`, equal to `Bearer`.
- The complete entitlement item list and `java_entitled` classification.
- `minecraft_profile_id` and `minecraft_profile_name` after a profile 200 response.
- The returned skin and cape arrays.
- The most recent `microsoft_refresh_token` for future renewal.

The result is ready for an authenticated Java session only when all of these conditions hold:

- `minecraft_access_token` is unexpired.
- `java_entitled` is true.
- The profile request returned 200.
- `minecraft_profile_id` and `minecraft_profile_name` are valid and non-empty.

The Minecraft access token has no refresh grant. Its renewal starts with section 3 when `microsoft_access_token` remains
outside its five-minute expiry window. It starts with section 2 when the Microsoft token has entered that window. Both
paths repeat every downstream section through section 7. If the Microsoft refresh grant fails terminally, renewal starts
with section 1.

## 9. HTTP failure handling

| Condition                                                                                   | Action                                                                                              |
|---------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| OAuth callback `state` mismatch                                                             | Reject the callback and terminate the pending operation without exchanging the code.                |
| OAuth `authorization_pending`                                                               | Poll again after the active device interval.                                                        |
| OAuth `slow_down`                                                                           | Increase the active device interval by five seconds and poll again.                                 |
| OAuth `invalid_grant`, `interaction_required`, `authorization_declined`, or `expired_token` | End the current OAuth operation and require a new interactive entry when refresh cannot recover it. |
| HTTP 400, 401, or 403 with a service error                                                  | Parse and preserve the structured error; do not retry unchanged credentials.                        |
| HTTP 408, 425, 429, 500, 502, 503, or 504                                                   | Apply a bounded retry policy and honor `Retry-After` when present.                                  |
| Other non-2xx status                                                                        | Preserve the status, relevant response headers, and body as a terminal failure for that step.       |
| Invalid JSON or a missing required response member                                          | Reject the response as malformed and do not consume partial credentials.                            |

Retries never extend a token beyond its returned expiry. A retry that reaches an expired input token restarts at the
nearest step capable of issuing a fresh input token.

## 10. Request sequence

```text
Authorization Code + PKCE --+
                            +--> Microsoft access token + refresh token
Device Authorization ------+
                                      |
                                      v
                     POST user.auth.xboxlive.com/user/authenticate
                                      |
                                      v
                         Xbox User Token + user hash
                                      |
                                      v
                       POST xsts.auth.xboxlive.com/xsts/authorize
                                      |
                                      v
                    Minecraft-scoped XSTS token + matching user hash
                                      |
                                      v
             POST api.minecraftservices.com/authentication/login_with_xbox
                                      |
                                      v
                            Minecraft access token
                                      |
                                      v
                         GET /entitlements/mcstore
                                      |
                                      v
                          GET /minecraft/profile
                                      |
                                      +-- 404 --> GET /entitlements/license
```
