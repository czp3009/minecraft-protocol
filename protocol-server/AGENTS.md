# protocol-server

This module owns server socket acceptance and orchestration for Status or Login through entry into Play. It can project
a finite initial Chunk/entity view; it does not run gameplay.

## Application boundary

- `accept` returns a raw direction-bound connection. The application chooses when to negotiate and owns concurrency,
  players, worlds, persistence, ticking, and gameplay.
- Preserve the zero-configuration vanilla path: `bind` requires only the selector and defaults the connection
  definition, transport, and offline authentication; `negotiate` defaults the vanilla profile, protocol data, and
  policy. Vanilla initial-world factories independently default their Play bootstrap values.
- `MinecraftServerNegotiationOptions` contains protocol-visible configuration; `MinecraftServerNegotiationPolicy`
  contains per-connection decisions. Initial-world difficulty, difficulty locking, player abilities, and semantic Chunk
  defaults do not belong to negotiation options. Do not read `server.properties` or hardcode gameplay policy.
- Fire-and-forget Configuration packets use `configurationPackets`; response-gated work uses ordered
  `configurationTasks`. Caller extension traffic is not rescanned as framework-owned traffic.
- `ProtocolData` supplies domain values; construct Feature Flags, Known Packs, Update Tags, and registry packets at the
  Configuration send boundary without moving packet sequencing into `protocol-datapack`.
- A stored-world server explicitly passes `ResolvedMinecraftWorld.protocolData`, its dimension IDs, and the selected
  dimension to negotiation options. Its `WorldChunkContexts` remain raw-ID-free semantic disk contexts, while
  negotiation returns the connection's registry/raw-ID context. The world-protocol adapter composes those facts in an
  explicit encoder context; do not merge them into a bidirectional codec or derive encoder configuration from a Chunk.
- Online Login decides when the Session Server `/hasJoined` call occurs. It consumes a caller-supplied `HttpClient` and
  does not own account or admission policy.
- Definitions, static schemas, and resolved contexts may be shared across connections. Retain caller-owned read-only
  data by reference.
- Negotiation, codec, and state failures propagate. Do not add automatic disconnect packets or loader-failure replies;
  the caller chooses the response and lifetime.
- Preset negotiation owns the Configuration-to-Play KeepAlive switch. Custom negotiation and reconfiguration explicitly
  disable the old run before enabling the new state-specific run at the acknowledgement boundary.

## Initial world projection

- Shared dimension/registry conversion facts come from the Configuration module, and reusable plain encoders that
  project
  semantic Chunks and Entities into detached clientbound packets belong to the world-protocol adapter module. This
  endpoint binds explicit direction-specific contexts or prebuilt codecs at the current connection epoch/dimension and
  invokes them directly; it does not own duplicate conversion logic or call a fluent convenience API. Replace that
  binding on reconfiguration or dimension change. The encoder context is authoritative and is not compared with
  parallel facts retained by the input Chunk. Air, fluid, and block-entity update-tag classification remains caller game
  content.
- Entity synchronization delegates stable subtype/registry conversion to `EntityPacketEncoder`; this endpoint supplies
  each connection-local `EntityPairingData`, including runtime entity IDs, tracking relations, passengers, vehicles,
  and leash state. The encoder owns the matching release's per-Entity pairing sequence; the endpoint owns visibility,
  ordering across Entities and Chunks, and enqueue. These facts do not become persistent Entity fields.
- The module never depends on `world-io` or opens files. Applications load data separately and pass semantic values or
  pairing data.
- Palette/light projection remains stateless. Require caller-owned semantics when a registry ID alone cannot determine a
  value.

## Verification

The official headless-client scenario retries only the expensive official-client connection boundary: bind a fresh
loopback server endpoint for each finite attempt, reuse the title-ready client, disconnect it before the next attempt,
and aggregate command state, GUI state, and connection deadline diagnostics. Do not retry deterministic protocol
assertions. The accepted socket and observed packets, not HMC GUI text, prove protocol progress.

The scenario has a four-minute coroutine budget. Keep `jsNodeTest`'s outer Mocha watchdog longer so bounded diagnostics
and cleanup finish before the test process is terminated.

Run `:protocol-server:jvmTest`.
