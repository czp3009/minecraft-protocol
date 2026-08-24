# protocol-session

This module binds typed packet codecs to framed transport and owns direction, connection state, transition timing,
compression activation, extension routes, and loader negotiation profiles.

## Local invariants

- Keep client and server endpoints direction-bound through `MinecraftClientPacketConnection` and
  `MinecraftServerPacketConnection`; do not reintroduce a runtime side flag.
- State changes occur only after the complete transition frame has been appended. Encryption activates after the
  Encryption Response frame, and failed or cancelled writes leave correlated state unchanged.
- Pending Login Query IDs are a caller ordering convention. A response without an observed request remains the raw
  vanilla response packet.
- `MinecraftConnectionDefinition` and loader definitions are immutable and shareable. Connection profiles retain
  caller-owned schemas, snapshots, registrations, and resolved contexts by reference.
- Preserve unknown valid extension routes as `UnknownPacket`. Propagate malformed bytes and state/order failures without
  inventing automatic replies.
- Socket creation, authentication policy, and high-level client/server negotiation belong to their orchestration
  modules.

## Verification

Run `:protocol-session:jvmTest`. State-machine changes also require the affected client and server JVM suites.
