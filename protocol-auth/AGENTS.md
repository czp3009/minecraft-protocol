# protocol-auth

This module owns offline and online game identities, Session Server HTTP calls, Minecraft's signed SHA-1 server hash,
and online-mode Login key exchange. Microsoft OAuth, Xbox authentication, Minecraft Services access tokens,
entitlements, and Java profiles belong to `account-auth`; neither module depends on the other.

Offline identity and server-hash composition remain common Kotlin, while maintained libraries supply MD5/SHA-1,
constant-time comparison, signed big-integer conversion, secure randomness, and RSA. Session HTTP uses a caller-owned
Ktor `HttpClient`; the module never installs an engine, changes client configuration, closes the client, provides a
relay, or owns retry policy. Internal platform RSA backends use JCA, cryptography-kotlin, or node-forge with vanilla's
RSA-1024, SPKI, and PKCS#1 v1.5 behavior. Do not add project-owned digest, comparison, big-integer, RSA, ASN.1, or
padding algorithms.

Key exchange produces the shared secret; `protocol-transport` owns the continuous socket cipher, and
`protocol-client`/`protocol-server` own activation timing. Automated tests use deterministic HTTP mocks and no live
account credentials. Run `:protocol-auth:jvmTest` after changes.

Direct identity, Session Server, hash, and key-exchange APIs use Kotlin standard types or models owned by this module.
Convenience extensions may adapt `protocol-model` profiles and Login packets in the same source files; that dependency
is deliberately `compileOnly`, is never invoked by a direct API path, and must be supplied by callers that use those
extensions. Exercise both the direct byte/wire-model path and the adapters in module and downstream tests; this
established optional-linkage pattern does not require a separate external-consumer project.

Session Server request, success-response, and error-response wire models are public `@Serializable` data classes named
`*Request` and `*Response`; nested JSON objects are nested classes of their owning response. Serialize and deserialize
those wire types directly with kotlinx.serialization, including query parameters through the Properties format. Do not
manually parse structured HTTP fields, prevalidate server-produced values, or catch text, serialization, UUID, or other
decoding failures. Explicit post-decode adapters to downstream models remain valid. Every endpoint handles its
documented successful statuses directly. Every other status decodes the structured error response and throws the public
`MinecraftSessionResponseException`, retaining both `responseBody` and the non-null `parsedErrorBody`.
