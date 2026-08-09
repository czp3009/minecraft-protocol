# protocol-auth

Authentication support for Minecraft Java Edition.

- Vanilla-compatible offline UUID and profile creation.
- Signed SHA-1 server hashes.
- Ktor-based `join` and `hasJoined` session-server operations.
- Encryption Request/Response helpers behind a portable cryptography interface.
- A JVM JCA implementation for RSA-1024, PKCS#1 v1.5, and secure random bytes.

MD5, SHA-1, and constant-time token comparison delegate to Okio `ByteString`, while signed server-hash formatting
delegates two's-complement arithmetic to cryptography-kotlin `BigInt`; this module contains no digest, comparison, or
big-integer algorithm implementation. The session service accepts an application-owned Ktor `HttpClient`.
