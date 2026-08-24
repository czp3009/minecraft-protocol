# protocol-auth

This module owns game identities, Session/Services HTTP APIs, Minecraft's signed server hash, Login key exchange,
profile-key verification, and player chat signatures and chains.

## Local boundaries

- Microsoft OAuth, Xbox/XSTS, Minecraft Services access-token acquisition, entitlements, and launcher profiles belong to
  `account-auth`. Neither module depends on the other; callers pass plain token values between them.
- Login key exchange returns the shared secret. `protocol-transport` owns the continuous socket cipher, while
  client/server orchestration owns activation timing.
- Chat utilities do not fetch keys, choose announcement timing, reconstruct last-seen state, disconnect players, enforce
  secure-chat policy, manage recipients, or broadcast. A stateful chain owns only its documented sender/session index
  and timestamp ordering.
- Use maintained platform libraries for digests, constant-time comparison, signed big integers, randomness, RSA, ASN.1,
  and padding. Do not add project-owned cryptographic primitives.

## HTTP and API contract

- HTTP APIs borrow a caller-owned Ktor `HttpClient` and do not install engines, close it, or own retry/cache/token
  policy.
- Public HTTP wire bodies are `@Serializable` `*Request`/`*Response` data classes. Decode them directly, retain
  structured error bodies, and let transport, cancellation, parsing, Base64, UUID, timestamp, and serialization failures
  keep their owning type.
- Expose `protocol-model` values directly when they are the natural contract. Keep signature bodies, chain links,
  signable arguments, parsed keys, cryptographic results, and chain state in this module rather than adding facade
  wrappers.
- Tests use deterministic HTTP mocks and fixed cryptographic vectors, never live credentials.

## Verification

Run `:protocol-auth:jvmTest`. Platform cryptography changes also require the affected JS, WasmJS, and Native suites.
