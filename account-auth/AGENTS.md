# account-auth

This module owns the caller-driven launcher HTTP flow from Microsoft OAuth through Xbox User authentication, XSTS,
Minecraft Services access tokens, Java entitlements, and the Java profile. Each public API method performs at most one
endpoint request. It never runs a complete login, polling loop, retry, refresh, callback listener, browser, credential
store, or recovery policy. It does not receive, parse, or validate OAuth callbacks. Pure stateless value tools such as
OAuth state generation and PKCE calculation may be provided, but they never advance a flow or invoke an endpoint.
Stateless tools can be layered: higher-level tools may derive fixed or linked protocol values and return a URL, request
DTO, or other value, but accept only independent inputs and never navigate, invoke an endpoint, or retain flow state. In
every endpoint API file, order declarations as the API class first, wire model classes second, tool objects third, and
private implementation constants or helpers last.

Every API uses a caller-owned Ktor `HttpClient`. The module installs no engine, requires no content-negotiation plugin,
does not change global client configuration, and never closes the client. Endpoint URLs and required protocol headers
remain canonical; callers own request values, timeout, proxy, TLS, logging, retry, routing, and browser deployment
policy.

Public request, success-response, and error-response bodies are the wire DTOs themselves: declare them as
`@Serializable` data classes, preserve the payload's structure and nullability, and nest subordinate JSON object types
inside the owning root type. Name every top-level request body type `*Request` and every top-level success or error
response body type `*Response`; nested types retain their payload names. Do not map them into credential wrappers,
parsed URLs, UUIDs, timestamps, durations, deadlines, or library-defined workflow results. Every non-2xx response throws
the endpoint's structured `ResponseException`; its `responseBody` retains the raw text and `parsedErrorBody` contains
the non-null wire error DTO. Decode successful and error bodies directly and let every serialization failure propagate
unchanged. Transport, timeout, cancellation, and caller-plugin failures propagate unchanged. Automated tests use
deterministic Ktor mocks and no live credentials.

Use kotlinx.serialization for every object-to-body and body-to-object conversion. JSON bodies use the JSON format;
Microsoft form bodies use the official multiplatform Properties format for object-to-key/value serialization and Ktor
for `application/x-www-form-urlencoded` encoding. Do not manually append individual form fields or construct structured
body text.

Derive every response and error field's required, omittable, and JSON-null behavior from Microsoft documentation, the
observed official launcher, or the module's protocol document. Do not make a field nullable merely to tolerate malformed
success data. Serialized JSON integer fields use `Long`, not Kotlin unsigned types. Do not add semantic validation for
documented server-produced values such as non-empty credentials, token types, lifetimes, numeric ranges, timestamps,
URLs, UUIDs, or cross-response consistency.

The module publishes JVM, Android, supported Native, JS Node/browser, and WasmJS Node/browser variants. It does not
publish Wasm/WASI, and D8 is not a required runtime. Run `:account-auth:jvmTest` after changes, plus JS Node, WasmJS
Node, and browser compilation for target-sensitive changes.
