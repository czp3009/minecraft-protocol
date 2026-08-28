# protocol-server

This module owns server socket acceptance and orchestration for Status or Login through entry into Play. It can project
a finite initial Chunk/entity view; it does not run gameplay.

## Application boundary

- `accept` returns a raw direction-bound connection. The application chooses when to negotiate and owns concurrency,
  players, worlds, persistence, ticking, and gameplay.
- Preserve the zero-configuration vanilla path: `bind` requires only the selector and defaults the connection
  definition, transport, and offline authentication; `negotiate` defaults the vanilla profile, protocol data, and
  policy. Vanilla initial-world factories also default the matching server options.
- `MinecraftServerNegotiationOptions` contains protocol-visible configuration; `MinecraftServerNegotiationPolicy`
  contains application decisions. Do not read `server.properties` or hardcode difficulty, game mode, abilities, Status
  content, transfer admission, resource-pack policy, or secure-chat claims.
- Fire-and-forget Configuration packets use `configurationPackets`; response-gated work uses ordered
  `configurationTasks`. Caller extension traffic is not rescanned as framework-owned traffic.
- `ProtocolData` supplies domain values; construct Feature Flags, Known Packs, Update Tags, and registry packets at the
  Configuration send boundary without moving packet sequencing into `protocol-datapack`.
- Online Login decides when the Session Server `/hasJoined` call occurs. It consumes a caller-supplied `HttpClient` and
  does not own account or admission policy.
- Definitions, static schemas, and resolved contexts may be shared across connections. Retain large immutable data by
  reference.
- Negotiation, codec, and state failures propagate. Do not add automatic disconnect packets or loader-failure replies;
  the caller chooses the response and lifetime.
- Preset negotiation owns the Configuration-to-Play KeepAlive switch. Custom negotiation and reconfiguration explicitly
  disable the old run before enabling the new state-specific run at the acknowledgement boundary.

## Initial world projection

- Shared dimension/registry conversion to world-Chunk contracts comes from `protocol-datapack`. This module owns the
  direction-specific encoders and adapters that convert semantic Chunks and Entities into detached clientbound packets
  using the installed registry context.
- The module never depends on `world-io` or opens files. Applications load data separately and pass semantic values or
  snapshots.
- Palette/light projection remains stateless. Require caller-owned semantics when a registry ID alone cannot determine a
  value.

## Verification

The official headless-client scenario retries only the expensive official-client connection boundary: bind a fresh
loopback server endpoint for each finite attempt, reuse the title-ready client, disconnect it before the next attempt,
and aggregate command state, GUI state, and connection deadline diagnostics. Do not retry deterministic protocol
assertions. The accepted socket and observed packets, not HMC GUI text, prove protocol progress.

Run `:protocol-server:jvmTest`.
