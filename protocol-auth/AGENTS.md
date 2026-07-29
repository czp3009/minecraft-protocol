# protocol-auth

This module owns offline profiles, Minecraft's signed SHA-1 server hash, session-server HTTP calls, and online-mode
login cryptography abstractions.

Offline authentication and digest logic are common Kotlin. Online HTTP uses an injected Ktor `HttpClient`. Platform
cryptography implements
`MinecraftCryptography`; the JVM implementation uses the JCA with vanilla's RSA-1024 and PKCS#1 v1.5 choices.

Authentication never owns sockets or protocol state. Live account credentials are not required by automated tests; HTTP
is tested with deterministic mocks.
