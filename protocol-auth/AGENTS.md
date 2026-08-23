# protocol-auth

This module owns offline and online game identities, Session Server HTTP calls, Minecraft Services profile-key and
service-public-key HTTP calls, Minecraft's signed SHA-1 server hash, online-mode Login key exchange, profile-key
credential verification, and player chat signatures/chains. Microsoft OAuth, Xbox authentication, Minecraft Services
access-token acquisition, entitlements, and Java profiles belong to `account-auth`; neither module depends on the other.
A caller may pass the access token produced by `account-auth` without creating a module dependency.

Offline identity and server-hash composition remain common Kotlin, while maintained libraries supply MD5/SHA-1,
constant-time comparison, signed big-integer conversion, secure randomness, and RSA. Session HTTP uses a caller-owned
Ktor `HttpClient`; the module never installs an engine, changes client configuration, closes the client, provides a
relay, or owns retry, cache, refresh, or request-timing policy. Internal platform RSA backends use JCA,
cryptography-kotlin, or node-forge with vanilla's RSA-1024 Login encryption, RSA-2048/SHA-256 player signatures, and
RSA-4096/SHA-1 service signatures. Do not add project-owned digest, comparison, big-integer, RSA, ASN.1, or padding
algorithms.

Key exchange produces the shared secret; `protocol-transport` owns the continuous socket cipher, and
`protocol-client`/`protocol-server` own activation timing. Chat utilities never fetch keys implicitly, choose when a
session is announced, reconstruct last-seen updates, disconnect players, enforce secure-chat configuration, manage
recipient caches/global indices, or broadcast. Stateful chain tools may own only the explicitly documented
sender/session index and timestamp ordering, with a lock around sequential operations. Automated tests use deterministic
HTTP mocks and static cryptographic vectors with no live account credentials. Cryptography failure mapping leaves
`CancellationException` unwrapped, including around suspend platform-backend calls. Run `:protocol-auth:jvmTest` after
changes and the applicable JS, Wasm, and Native suites when platform cryptography changes.

`protocol-auth` directly exposes `protocol-model` identities, packet payloads, and shared wire values where those are
the natural contract; `protocol-model` is therefore an `api` dependency. Do not duplicate those types behind
module-owned wrappers merely to hide the dependency. Keep reconstructed signature bodies, chain links, Brigadier-derived
signable arguments, opaque parsed key material, cryptographic results, HTTP wire models, and chain state in this module.
Do not mark signing-only logical values serializable without an independent serialization contract. Exercise direct
byte/key paths and packet conveniences in module tests.

Session Server and profile-key request, success-response, and error-response wire models are public `@Serializable`
data classes named `*Request` and `*Response`; nested JSON objects are nested classes of their owning response.
Serialize and deserialize those wire types directly with kotlinx.serialization, including query parameters through the
Properties format. Do not manually parse structured HTTP fields, prevalidate server-produced values, or catch text,
serialization, UUID, timestamp, Base64, or other decoding failures. Explicit post-decode adapters to key and protocol
models remain valid. Every endpoint handles its documented successful statuses directly. Every other status decodes the
structured error response and throws its public response exception, retaining both `responseBody` and the non-null
`parsedErrorBody`.
