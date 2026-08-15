# Protocol lifecycle workflow

## Inventory both peers

Search the selected official server and client by listener behavior rather than relying on remembered class names.
Trace:

1. Handshake intention into Status, Login, or transfer handling;
2. Status request, response, ping, and pong;
3. Login start, optional encryption, authentication, compression, cookies, custom queries, Login Success, and
   acknowledgement;
4. Configuration client information, feature flags, Known Packs offer/response, synchronized registries, tags, optional
   tasks, code of conduct, finish, and acknowledgement;
5. Play Login, connection-specific registry and dimension context, required initial packets and acknowledgements;
6. Play-to-Configuration reconfiguration and return to Play when implemented.

For every optional branch, identify who initiates it, which replies are required, whether unrelated packets may be
handled while waiting, and the exact event that changes protocol state.

## Preserve layer ownership

- `protocol-session` validates direction/state, reads and writes packet IDs around payload serialization, and applies
  packet-driven state effects.
- `protocol-client` orchestrates the official server-facing Status/Login/Configuration path and builds runtime
  serialization context from synchronized data.
- `protocol-server` orchestrates the official client-facing path and may emit only the documented finite initial
  chunk/entity projection.
- `protocol-auth` owns identities, Login key exchange, server hash, and Session Server calls through standard types and
  module-owned serializable request/response models, with optional `protocol-model` adapters expressed as `compileOnly`
  extensions.
- `protocol-transport` owns frames, compression envelope, stream encryption, and sockets.

`account-auth` ends at caller-managed Minecraft account data and access tokens. It does not participate in this
connection state machine and has no dependency relationship with `protocol-auth`.

The Login Set Compression packet changes subsequent framing only after that packet crosses the wire. Stream encryption
starts at the official challenge-response boundary and must preserve continuous cipher state. A failed send or
incomplete receive must not advance session state.

## Audit dynamic context

Verify how the active dimension, dimension-type registry entry, synchronized biome registry, and static block-state
registry configure chunk codecs. Reject absent or inconsistent registry IDs instead of falling back silently. Recompute
context after reconfiguration or respawn when the official lifecycle requires it.

## Audit initial Play synchronization

Compare the production server's packet order with official client handling. Include dimension and spawn context,
difficulty and abilities, position/teleport, render and simulation distance, chunk center and batches, initial entities,
and every acknowledgement the official client requires. Add or remove steps only from selected-release official
behavior.

Do not expand the initial projection into gameplay or an authoritative world.

## Test failure paths

Cover wrong packet state/direction, rejection and disconnect paths, duplicate registries, unsupported transfers,
authentication branch failures, phase budget exhaustion, state changes after failed I/O, and reconfiguration ordering.
Official-peer success complements rather than replaces deterministic in-memory tests.

## Route fixture failures

Standard client/server test tasks obtain official fixtures through the existing Gradle Build Service and
`minecraft-test-support`; do not add a launcher, helper CLI, explicit fixture task dependency, workspace-policy switch,
or path property. A failure before packet behavior is exercised may belong to artifact preparation, the Fixture Host, or
the HeadlessMC integration rather than production protocol code. When those modules change, run
`./gradlew :minecraft-test-support:jvmTest` or `./gradlew :minecraft-test-fixture-host:test` as applicable before
rerunning the consuming client/server suite.

Keep Minecraft, HeadlessMC, Fabric Loader, and HMC-Specifics versions independent and require exact compatibility
evidence before changing a non-Minecraft selector. HeadlessMC output establishes process readiness only; packet
observations establish protocol state and Play acceptance.
