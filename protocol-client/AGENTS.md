# protocol-client

This module owns client-side protocol orchestration over `MinecraftSession`. It exposes the connected Ktor socket and
delegates packet bytes, state, auth, and vanilla data to their owning modules.

The high-level login path handles Login cookies/plugins, compression, online-mode encryption, client information, Known
Packs, Configuration keepalives, entry into Play, and active chunk/biome decode context derived from synchronized
registries. Extension hooks may answer server-specific requests without changing the core state machine.

Client tests use scripted peers and the exact official offline server. Keep the reusable protocol scenario and
assertions in `commonTest`; the shared `hostProcessTest` source set owns the ordinary test-support resource call on
JVM, desktop Native, and supported Node runtimes. An external Java process does not make the scenario JVM-specific. Live
account authentication is not part of deterministic verification.
