# protocol-client

This module owns client-side Status, Login, Configuration, and entry into Play.

## Local contract

- `MinecraftClientConnection` exposes direction-limited packet channels and connection state, not its socket, frame
  stream, or mutable low-level session.
- High-level negotiation handles cookies, custom Login queries, compression, online encryption, client information,
  Known Packs, Configuration tasks, registry context, and the selected loader profile. It borrows the public channels
  exclusively until it returns.
- The low-level client endpoint consumes and answers direct official Configuration and Play KeepAlive requests. Do not
  duplicate that reply in negotiation or application packet loops.
- Preserve the zero-configuration vanilla path: `connect` defaults the connection definition and transport, while
  `negotiate` requires only the caller's identity and defaults the vanilla profile, protocol data, Known Packs, and
  client settings. Loader or mod behavior is an explicit override.
- Online Login decides when the Session Server `/join` call occurs. It consumes a caller-supplied `HttpClient` and does
  not own account-token acquisition.
- The negotiation result retains the server-selected `MinecraftDimensionLayout` and derived `ChunkLayout`. The shared
  dimension conversion comes from `protocol-datapack`; there is no release-global dimension-layout default.
- Shared active-registry conversion to `ChunkDataRegistries` comes from `protocol-datapack`. This module owns the
  connection convenience and clientbound Chunk/entity projection, which remain stateless and filesystem-independent. Do
  not invent persistence-only data missing from packets; require caller-supplied templates or adapters for it.
- Received data-pack views expose only Configuration-visible resources and may resolve tags against the installed
  context or caller-supplied schemas. Do not imply that they reconstruct server-only pack content.
- `MinecraftClientNegotiationResult` stores one `DataPackConfigurationSnapshot` directly instead of duplicating its
  Known Packs, feature flags, synchronized registries, and registry tags or wrapping them in another public result.

## Verification

Run `:protocol-client:jvmTest`.
