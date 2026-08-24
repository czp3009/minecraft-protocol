# account-auth

This module owns the caller-driven launcher authentication endpoints from Microsoft OAuth through Xbox, XSTS, Minecraft
Services, entitlements, and the Java profile.

## Local contract

- Each public API method performs at most one endpoint request. Do not add a complete login flow, polling/retry loop,
  callback listener, browser, token store, refresh policy, or recovery policy.
- Stateless tools may derive PKCE/state values, linked protocol values, URLs, or request DTOs. They do not retain flow
  state or invoke endpoints.
- Every API borrows a caller-owned Ktor `HttpClient`; it installs no engine or content-negotiation plugin, changes no
  global configuration, and never closes the client.
- Public request, success, and error bodies are the wire DTOs. Use `@Serializable` data classes, preserve wire structure
  and nullability, and nest subordinate JSON objects under the owning response.
- Name top-level request bodies `*Request` and top-level success/error bodies `*Response`. Do not replace wire values
  with workflow-specific credentials, parsed URLs, UUIDs, timestamps, durations, or deadlines.
- Each non-2xx response throws the endpoint-specific `ResponseException`, retaining both raw `responseBody` and non-null
  decoded `parsedErrorBody`. Transport, cancellation, plugin, and serialization failures propagate unchanged.
- Do not add semantic validation for documented server-produced values or make required fields nullable merely to
  tolerate malformed responses.
- Endpoint source files are ordered as API class, wire models, public tool object, then private constants/helpers.
- Tests use deterministic Ktor mocks and never require live credentials.

## Verification

Run `:account-auth:jvmTest`; also compile or test JS, WasmJS, and browser targets when changing platform-sensitive HTTP
code.
