# account-auth

Caller-driven HTTP APIs for obtaining a Minecraft Services access token through Microsoft OAuth and Xbox authentication.
Every request is explicit: the library does not open a browser, receive OAuth callbacks, poll, wait, retry, refresh, or
store credentials automatically.

Create the API objects with a caller-owned Ktor `HttpClient`:

```kotlin
val microsoftOAuthApi = MicrosoftOAuthApi(applicationHttpClient)
val xboxAuthenticationApi = XboxAuthenticationApi(applicationHttpClient)
val minecraftServicesApi = MinecraftServicesApi(applicationHttpClient)
```

The caller configures and closes the client.

## Obtain a Minecraft access token

### 1. Obtain a Microsoft access token

Choose either Authorization Code with PKCE or Device Code. Both branches produce a `MicrosoftTokenResponse`.

#### Authorization Code with PKCE

Generate the values for a new authorization operation and build the Microsoft authorization URL:

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

The caller opens the URL, receives the callback, verifies `state`, handles callback errors, and extracts `code`. The
library only performs the subsequent token request:

```kotlin
applicationBrowser.open(authorizationUrl)

val authorizationCode = applicationCallbackHandler.receiveAndValidate(
    expectedState = state,
)

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

Request a device code and present Microsoft's response to the user:

```kotlin
val deviceAuthorization = microsoftOAuthApi.deviceCode(
    MicrosoftOAuthTools.deviceAuthorizationRequest(microsoftClientId),
)

applicationUi.showDeviceAuthorization(
    userCode = deviceAuthorization.userCode,
    verificationUri = deviceAuthorization.verificationUri,
    message = deviceAuthorization.message,
)
```

Each token poll is an explicit call. Microsoft reports states such as `authorization_pending` and `slow_down` as
non-success responses, so the caller interprets the exception and decides whether and when to poll again:

```kotlin
val microsoftToken = try {
    microsoftOAuthApi.tokenWithDeviceCode(
        MicrosoftOAuthTools.deviceCodeTokenRequest(
            clientId = microsoftClientId,
            deviceCode = deviceAuthorization.deviceCode,
        ),
    )
} catch (failure: MicrosoftOAuthResponseException) {
    when (failure.parsedErrorBody.error) {
        "authorization_pending" -> return scheduleNextPoll(deviceAuthorization.interval)
        "slow_down" -> return increasePollingInterval()
        else -> throw failure
    }
}
```

The `error` values handled above are not exhaustive. See Microsoft's
[expected device-code errors](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code#expected-errors)
for the documented possibilities.

Refreshing a Microsoft token is also caller-triggered:

```kotlin
val refreshedMicrosoftToken = microsoftOAuthApi.tokenWithRefreshToken(
    MicrosoftOAuthTools.refreshTokenRequest(
        clientId = microsoftClientId,
        refreshToken = savedRefreshToken,
    ),
)
```

The caller decides when a refresh is needed and whether to replace a stored refresh token with the returned value.

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
if (MinecraftServicesTools.hasJavaEditionEntitlement(storeEntitlements)) {
    val profile = minecraftServicesApi.getMinecraftProfile(minecraftAccessToken)
}
```

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
