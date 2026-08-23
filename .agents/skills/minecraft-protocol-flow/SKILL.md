---
name: minecraft-protocol-flow
description: Implement, update, test, or audit handwritten Minecraft connection lifecycle code in protocol-session, protocol-client, and protocol-server for the selected release. Use for packet direction and state transitions, Status, Login, transfer, authentication invocation, encryption or compression activation timing, cookies and custom queries, Configuration and Known Packs negotiation, code-of-conduct or other configuration tasks, transition to Play, reconfiguration, dynamic serialization context, initial world synchronization, or official client/server interoperability. Do not use for the byte-level transport codec itself.
---

# Minecraft protocol flow

Align stateful orchestration with both official peers after packet models and payload codecs are correct. Keep gameplay,
ticking worlds, and persistence policy outside this library.

## Establish the lifecycle

1. Confirm the selected release with `./gradlew -q minecraftVersion`.
2. Read [references/lifecycle.md](references/lifecycle.md).
3. Load the model or serialization skill first when a flow failure is caused by a packet declaration or payload codec.

Inspect the matching official server listeners and official client listeners. Obtain client bytecode through
`./gradlew downloadMinecraftClientJar` when it is not already available; do not download it manually. Trace both the
successful path and conditional branches; do not derive a state machine from packet inventory or Wiki prose alone.

## Implement stateful behavior

Keep typed dispatch and post-wire state effects in `protocol-session`. Keep client-side negotiation in `protocol-client`
and server-side negotiation plus the finite initial Play projection in `protocol-server`. Keep identities, Login
key-exchange primitives, hashes, Session Server calls, caller-driven profile-key calls, and signed-chat primitives in
`protocol-auth`; this skill owns only when the protocol invokes them. Launcher-side Microsoft/Xbox access-token calls
belong to independent `account-auth` and are outside the connection lifecycle.

`protocol-auth` directly uses `protocol-model` packet and shared wire types where they are its natural public contract;
that dependency is `api`. Keep reconstructed signature bodies, chain links, Brigadier-derived signable arguments, parsed
key material, HTTP request/response models, cryptographic results, and chain state owned by `protocol-auth`, and do not
duplicate protocol models solely to conceal the dependency.

Apply state transitions and compression/encryption activation only at the official wire boundary and only after the
triggering read or write completes successfully. Reject unexpected direction, state, duplicate data, invalid peer
ordering, and exhausted phase budgets deterministically.

Do not change framing, zlib, AES/CFB8, or Ktor socket implementations unless official transport behavior itself changed.
Such a change belongs in `protocol-transport` and requires focused transport tests, but does not justify a release-wide
transport rewrite.

## Verify and report

Use the smallest applicable prefix:

```shell
./gradlew :protocol-session:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
```

The client suite reaches Play against the matching official server; the server suite exercises the matching official
headless client. Run focused lower-layer suites first and applicable platform tests after the JVM path is stable.

When Login authentication, profile-key, signed-chat primitives, or Session Server calls changed, run
`./gradlew :protocol-auth:jvmTest` before the affected flow suites. When framing, compression-envelope, encryption,
channels, or sockets changed, run
`./gradlew :protocol-transport:jvmTest :protocol-session:jvmTest` before client/server interoperability.

Report the official producer/consumer paths inspected, lifecycle branches changed, activation timing decisions,
official-peer results, and any official phase this library intentionally does not implement.
