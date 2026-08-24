# protocol-client

This module owns client-side Status, Login, Configuration, and entry into Play.

## Local contract

- `MinecraftClientConnection` exposes direction-limited packet channels and connection state, not its socket, frame
  stream, or mutable low-level session.
- High-level negotiation handles cookies, custom Login queries, compression, online encryption, client information,
  Known Packs, Configuration tasks, registry context, and the selected loader profile. It borrows the public channels
  exclusively until it returns.
- Online Login borrows a caller-owned `HttpClient`; this module decides when the Session Server `/join` call occurs but
  never configures or closes that client.
- The negotiation result retains the server-selected `MinecraftDimensionLayout` and derived `ChunkLayout`. There is no
  release-global dimension-layout default.
- Chunk and entity projection is stateless and filesystem-independent. Do not invent persistence-only data missing from
  packets; require caller-supplied templates or adapters for it.
- Received data-pack views expose only Configuration-visible resources and may resolve tags against the installed
  context or caller-supplied schemas. Do not imply that they reconstruct server-only pack content.

## Verification

Run `:protocol-client:jvmTest`.
