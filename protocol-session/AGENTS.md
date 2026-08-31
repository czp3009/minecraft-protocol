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
- Clientbound Play bundles are logical session values. Enforce their size, nesting, and delimiter ownership at the
  packet-session boundary; the connection core never handles bundle structure. Deliberately do not inspect members for
  terminal state-transition semantics: putting `StartConfigurationPacket` in a bundle is documented invalid caller
  usage, not a library-side validation case.
- `MinecraftConnectionDefinition` and loader definitions are shareable while their caller-owned inputs remain stable.
  Connection profiles retain schemas, snapshots, registrations, and resolved contexts by reference.
- Keep `MinecraftConnectionDefinition()` and the `VanillaClient`/`VanillaServer` profiles sufficient as the high-level
  client/server defaults. Extension registrations and loader profiles remain explicit opt-ins.
- Preserve unknown valid extension routes as `UnknownPacket`. Apart from direct official Configuration/Play KeepAlive
  requests, propagate malformed bytes and state/order failures without inventing automatic replies.
- Keep server-managed KeepAlive state in the server endpoint. Higher-level flows explicitly replace the Configuration
  run with a fresh Play run; the shared connection core must not infer that choice from connection state.
- Socket creation, authentication policy, and high-level client/server negotiation belong to their orchestration
  modules.

## Verification

Run `:protocol-session:jvmTest`. State-machine changes also require the affected client and server JVM suites.
