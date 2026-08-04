# protocol-auth

This module owns offline profiles, Minecraft's signed SHA-1 server hash, session-service HTTP calls, and online-mode
login cryptography abstractions.

Offline identity and digest logic remain common Kotlin. Session-service calls use an application-supplied Ktor
`HttpClient`. Platform implementations satisfy `MinecraftCryptography`; the JVM implementation uses JCA with vanilla's
RSA-1024 and PKCS#1 v1.5 behavior.

Authentication does not own sockets or protocol state. Automated tests use deterministic HTTP mocks and no live account
credentials. Run `:protocol-auth:jvmTest` after changes.
