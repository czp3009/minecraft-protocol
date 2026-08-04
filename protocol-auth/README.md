# protocol-auth

Authentication support for Minecraft Java Edition.

- Vanilla-compatible offline UUID and profile creation.
- Signed SHA-1 server hashes.
- Ktor-based `join` and `hasJoined` session-server operations.
- Encryption Request/Response helpers behind a portable cryptography interface.
- A JVM JCA implementation for RSA-1024, PKCS#1 v1.5, and secure random bytes.

The session service accepts an application-owned Ktor `HttpClient`.
