# account-auth

Caller-driven launcher HTTP APIs for obtaining a Minecraft Services access token through Microsoft OAuth and Xbox
authentication. Every request is explicit: the library does not open a browser, receive OAuth callbacks, poll, wait,
retry, refresh, or store credentials automatically.

Create the API objects with a caller-configured, caller-closed Ktor `HttpClient`. The `applicationHttpClient` variable
below is that client:

```kotlin
val microsoftOAuthApi = MicrosoftOAuthApi(applicationHttpClient)
val xboxAuthenticationApi = XboxAuthenticationApi(applicationHttpClient)
val minecraftServicesApi = MinecraftServicesApi(applicationHttpClient)
```

## Obtain a Minecraft access token

### 1. Obtain a Microsoft access token

Choose either Authorization Code with PKCE or Device Code. Both branches produce a `MicrosoftTokenResponse`.

#### Authorization Code with PKCE

Generate the values for a new authorization operation and build the Microsoft authorization URL. Here
`microsoftClientId` is the application's registered OAuth client ID and `listenerPort` is the port chosen by its local
callback listener:

```kotlin
val state = MicrosoftOAuthTools.generateState()
val codeVerifier = MicrosoftOAuthTools.generateCodeVerifier()
val redirectUri = "http://127.0.0.1:$listenerPort/oauth/callback"

val authorizationUrl = MicrosoftOAuthTools.authorizationUrl(
    clientId = microsoftClientId,
    redirectUri = redirectUri,
    state = state,
    codeVerifier = codeVerifier,
)
```

The application opens `authorizationUrl`, receives the callback, verifies `state`, handles callback errors, and
extracts its `authorizationCode: String`. Browser and callback-listener implementations belong to the application.
Pass that validated code to the API created above:

```kotlin
val microsoftToken = microsoftOAuthApi.tokenWithAuthorizationCode(
    MicrosoftOAuthTools.authorizationCodeTokenRequest(
        clientId = microsoftClientId,
        authorizationCode = authorizationCode,
        redirectUri = redirectUri,
        codeVerifier = codeVerifier,
    ),
)
```

#### Device Code

Request a device code and present Microsoft's response to the user. `microsoftClientId` was described in the
Authorization Code branch:

```kotlin
val deviceAuthorization = microsoftOAuthApi.deviceCode(
    MicrosoftOAuthTools.deviceAuthorizationRequest(microsoftClientId),
)
```

Display `deviceAuthorization.userCode`, `verificationUri` and `message` in the application's UI before polling.

Each token poll is an explicit call. Microsoft reports states such as `authorization_pending` and `slow_down` as
non-success responses, so the caller interprets the exception and decides whether and when to poll again.
The `deviceAuthorization` response above supplies both the device code and the initial polling interval:

```kotlin
val microsoftToken = microsoftOAuthApi.tokenWithDeviceCode(
    MicrosoftOAuthTools.deviceCodeTokenRequest(
        clientId = microsoftClientId,
        deviceCode = deviceAuthorization.deviceCode,
    ),
)
```

On `MicrosoftOAuthResponseException`, inspect `parsedErrorBody.error`: `authorization_pending` means the application
may schedule another attempt, while `slow_down` requires changing its polling interval. Other errors must also be
handled; these two values are not exhaustive. Continue to Xbox authentication only after a call returns a token.

Refreshing a Microsoft token is also caller-triggered. `savedRefreshToken` is the refresh token previously persisted by
the caller, and `microsoftClientId` is the same registered client ID used above:

```kotlin
val refreshedMicrosoftToken = microsoftOAuthApi.tokenWithRefreshToken(
    MicrosoftOAuthTools.refreshTokenRequest(
        clientId = microsoftClientId,
        refreshToken = savedRefreshToken,
    ),
)
```

The caller decides when to refresh, whether to persist the returned refresh token, and when to use
`refreshedMicrosoftToken` as the `microsoftToken` input to the remaining steps.

### 2. Obtain an Xbox User Token

```kotlin
val xboxUserToken = xboxAuthenticationApi.authenticateUser(
    XboxAuthenticationTools.userAuthenticationRequest(microsoftToken),
)
```

### 3. Obtain a Minecraft-scoped XSTS token

```kotlin
val xstsToken = xboxAuthenticationApi.authorizeXsts(
    XboxAuthenticationTools.xstsAuthorizationRequest(xboxUserToken),
)
```

### 4. Obtain the Minecraft Services access token

```kotlin
val minecraftLogin = minecraftServicesApi.loginWithXbox(
    MinecraftServicesTools.xboxLoginRequest(xstsToken),
)

val minecraftAccessToken = minecraftLogin.accessToken
```

Passing this token to a game process is outside the module.

### 5. Retrieve entitlements and the Java profile when needed

```kotlin
val storeEntitlements = minecraftServicesApi.getStoreEntitlements(minecraftAccessToken)
val minecraftProfileResponse = if (MinecraftServicesTools.hasJavaEditionEntitlement(storeEntitlements)) {
    minecraftServicesApi.getMinecraftProfile(minecraftAccessToken)
} else {
    null
}
```

The returned profile's `id` and `name`, together with `minecraftAccessToken`, supply the online identity in
[protocol-auth](../protocol-auth/README.md#identities). A null result leaves the entitlement decision with the caller.

`getLicenseEntitlements(...)` is available as an optional diagnostic. The caller decides whether to issue it and how to
handle a missing entitlement or profile.

## Errors

A non-2xx response with a valid service error body throws the exception for that service:

- `MicrosoftOAuthResponseException`
- `XboxAuthenticationResponseException`
- `MinecraftServicesResponseException`

Each exception exposes the Ktor response, the original response text in `responseBody`, and the deserialized service
error in `parsedErrorBody`. The caller decides whether the error is terminal, requires another explicit poll, or should
restart part of the authentication flow.

Malformed success or error bodies propagate their `SerializationException`. Transport, timeout, cancellation, and
caller-installed Ktor plugin failures also propagate unchanged.
