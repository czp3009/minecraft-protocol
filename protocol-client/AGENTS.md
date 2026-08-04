# protocol-client

This module owns client-side Status, Login, Configuration, and Play-entry orchestration over `MinecraftSession`. It
exposes the connected Ktor socket and delegates packet bytes, state, authentication primitives, and vanilla data to
their owning modules.

The high-level Login path handles cookies and plugins, compression, online-mode encryption, client information, Known
Packs, Configuration keepalives, and active chunk/biome decode context. Extension hooks answer server-specific requests
without changing the core state machine.

Scripted peers exercise local branches in `commonTest`. The production-client scenario against the exact official
offline server also lives in `commonTest`; it requests a remote server and reads status and logs only through
`minecraft-test-support`. Live account authentication is not a deterministic test dependency.

Run `:protocol-client:jvmTest` after changes.
