---
name: minecraft-protocol-flow
description: "Implement or audit protocol-session/client/server state and negotiation: Status, Login, Configuration, loader profiles, encryption/compression activation, KeepAlive, entry into Play, initial-world order or reconfiguration. Do not use for physical transport algorithms or stateless world/packet projection."
---

# Minecraft protocol flow

Confirm the selected release, then read [lifecycle.md](references/lifecycle.md). Inspect both matching official
listeners
and their successful, optional and rejection paths. Use the declared client-JAR producer when needed; packet inventory
alone is not state-machine evidence.

Route defects before editing: packet declarations to minecraft-protocol-model, payload bytes to serialization, world
conversion to minecraft-world-projection. This workflow owns orchestration and the timing of auth/transport effects.
Read the applicable module AGENTS for endpoint policy and layer constraints.

For each changed flow, record the initiating packet/event, expected response, permitted unrelated traffic, state/context
change and failure result. Verify effects commit only after successful wire operations, including encryption and
compression transitions. Revisit codec bindings when registries or dimensions change.

Run the smallest affected JVM suites among protocol-session, protocol-client and protocol-server. Auth or transport
primitive changes require their own focused suites first. The client suite uses the official server and the server suite
uses the official headless client; route preparation/readiness failures separately from protocol failures.

Report changed lifecycle branches, boundary timing, official-peer results and implemented scope.
