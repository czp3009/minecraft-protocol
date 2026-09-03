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
- The negotiation result retains one composed `MinecraftDimensionContext` for the Play Login dimension and exposes its
  `MinecraftDimensionLayout`/`ChunkLayout` as derived conveniences. Install that context's registry context on the
  connection; semantic block/biome defaults enter only when the caller constructs the `ChunkContext` and
  `ChunkPacketDecoderContext`. Do not add cross-source identity, layout, or Section-count validation to negotiation or
  semantic packet decoding.
- Shared registry/layout adaptation comes from the Configuration module, while reusable clientbound Chunk/entity plain
  codecs belong to the world-protocol adapter module. Bind caller-provided contexts or prebuilt codecs to the current
  connection epoch and dimension, reuse them there, and replace them on reconfiguration or dimension change. This
  endpoint invokes the plain codecs directly rather than a fluent conversion and does not own a duplicate decoder or
  projection implementation. Every field absent
  from a packet, including local Chunk status and other save-oriented state, comes from the caller's provider rather
  than a hidden client or vanilla default. Packet-derived Chunks have no persistence metadata; supplying that metadata
  and encoding them for storage remain explicit caller operations after decoding.
- Received data-pack views expose only Configuration-visible resources and may resolve tags against the installed
  context or caller-supplied schemas. Do not imply that they reconstruct server-only pack content.
- `MinecraftClientNegotiationResult` stores one `DataPackConfigurationSnapshot` directly instead of duplicating its
  Known Packs, feature flags, synchronized registries, and registry tags or wrapping them in another public result.

## Verification

The official-server scenario has a four-minute coroutine budget. Keep `jsNodeTest`'s outer Mocha watchdog longer so
bounded diagnostics and cleanup finish before the test process is terminated.

Run `:protocol-client:jvmTest`.
