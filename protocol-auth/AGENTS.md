# protocol-auth

This module owns offline profiles, Microsoft/Xbox/Minecraft account authentication, session-service HTTP calls,
Minecraft's signed SHA-1 server hash, and online-mode Login cryptography.

Offline identity and server-hash composition remain common Kotlin, while Okio supplies MD5/SHA-1 and constant-time byte
comparison, and cryptography-kotlin supplies signed big-integer conversion and Native RSA. Authentication HTTP uses a
caller-owned Ktor `HttpClient` passed directly to each service; an optional typed relay remains an explicit constructor
argument. The module never installs an engine, changes client configuration, or owns the client lifecycle. Internal
platform RSA backends use JCA, cryptography-kotlin, or node-forge with vanilla's RSA-1024, SPKI, and PKCS#1 v1.5
behavior. Do not add project-owned digest, comparison, big-integer, RSA, ASN.1, or padding
algorithms.

Authentication does not own sockets or protocol state. Automated tests use deterministic HTTP mocks and no live account
credentials. Run `:protocol-auth:jvmTest` after changes.
