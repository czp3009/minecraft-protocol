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
6. Play-to-Configuration reconfiguration and return to Play when implemented;
7. Configuration/Play KeepAlive producers and consumers, including pending-challenge validation, terminal-listener
   behavior, and the event that resets server-side timing state.

For every optional branch, identify who initiates it, which replies are required, whether unrelated packets may be
handled while waiting, and the exact event that changes protocol state.

## Trace wire effects

For each triggering packet, verify the route, pre-operation state, completed read/write and resulting state. Compression
starts after Set Compression crosses the wire; encryption starts at the challenge-response boundary with continuous
cipher state. Failed operations must not commit state or Login-query correlation.

Inspect endpoint-generated KeepAlive traffic through the shared writer and state-specific server run replacement.
Bundle expansion/reconstruction belongs to the session boundary. Refer to the owning AGENTS for the complete layer
rules rather than repeating endpoint policy in this workflow.

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
authentication branch failures, phase budget exhaustion, state changes after failed I/O, KeepAlive timeout/mismatch and
run replacement, and reconfiguration ordering. Official-peer success complements rather than replaces deterministic
in-memory tests.

## Route fixture failures

First establish whether a failure reached packet behavior. Inspect artifact preparation, Host diagnostics and command
snapshots before changing production negotiation. The applicable buildSrc, fixture-host and test-support guides own
readiness and cleanup invariants.

Use protocol evidence appropriate to the phase: server-ready plus Status/Pong establishes server readiness; a correlated
client GUI observation establishes command/liveness only. TCP acceptance and observed packets establish connection and
Play progress. Test channel backpressure with an active consumer and explicit signals.

The existing official-client runner retries connection acquisition with fresh listeners. Do not broaden this into
retries around deterministic assertions. On timeout, distinguish deadlock/order bugs from host CPU pressure before
changing budgets or synchronization. Run focused fixture tests if preparation, RPC or lifecycle code changes, then
rerun the consuming endpoint scenario.
