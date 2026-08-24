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
- `protocol-auth` owns identities, Login key exchange, server hash, Session Server and profile-key calls, profile-key
  credential verification, and signed-chat primitives. It directly uses `protocol-model` packet and shared wire types
  where they form the natural contract, while reconstructed signing-only values remain module-owned.
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
evidence before changing a non-Minecraft selector.

Treat fixture readiness as staged evidence rather than a successful process start or raw TCP connection:

- For an official server, first observe its selected-release `Done` marker, then complete a bounded Status request and
  Ping/Pong exchange against its advertised endpoint. Publish the fixture only after both stages succeed.
- For a HeadlessMC client, first observe the HMC-Specifics command-ready marker, then use its `gui` command to confirm
  the title-screen state. Command acceptance is not connection success; after `connect`, inspect the returned GUI state
  and require the consuming test server to observe the inbound protocol connection.

Keep retries at the expensive official-client connection boundary. Use a finite attempt count, bind a fresh loopback
server endpoint for each attempt, reuse the same title-ready client, and disconnect it before another attempt. Aggregate
the command state, final GUI state, and connection deadline from every failed attempt. Do not retry localhost kRPC calls
or deterministic protocol assertions merely to mask a fixture failure.

Every acquired remote fixture still closes explicitly after its final phase. Task-owner cleanup must also cover a
creation that has started but is not registered yet: owner closure cancels its creation job and prevents late
registration. Cleanup attempts process termination and work-directory deletion independently, completes required
rollback under `NonCancellable`, and then rethrows cancellation. When changing this ownership machinery, test both the
owner-close race and cleanup failure path, and verify that an interrupted consuming run leaves no fixture process or
Host work directory.
