# protocol-auth

This module owns offline profiles, Minecraft's signed SHA-1 server hash, session-service HTTP calls, and online-mode
login cryptography abstractions.

Offline identity and server-hash composition remain common Kotlin, while Okio supplies MD5/SHA-1 and constant-time byte
comparison, and cryptography-kotlin supplies signed big-integer conversion. Session-service calls use an
application-supplied Ktor `HttpClient`. Platform implementations satisfy `MinecraftCryptography`; the JVM implementation
uses JCA with vanilla's RSA-1024 and PKCS#1 v1.5 behavior. Do not add project-owned digest, comparison, or big-integer
algorithms.

Authentication does not own sockets or protocol state. Automated tests use deterministic HTTP mocks and no live account
credentials. Run `:protocol-auth:jvmTest` after changes.
