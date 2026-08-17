# protocol-session

This module binds typed packet codecs to framed transport and owns the public channel-first connection contract,
connection state, packet direction, packet IDs, compression activation, protocol-state transitions, dynamic extension
route activation, and optional Fabric API/NeoForge/Forge negotiation profiles.

The published session follows `protocol-transport` onto JVM, Android, supported Native platforms, JS Node, and WasmJS
Node. Browser, D8, and Wasm/WASI are not configured because the public session contract requires TCP transport.

State changes occur only after the transition packet crosses the wire. Authentication code activates encryption after
the Encryption Response exchange. State that must be registered before a suspending wire write, such as Login Query
correlation, is provisional: a failed or cancelled write rolls it back with `NonCancellable` cleanup and then propagates
the original failure. Packet payload rules remain in `protocol-model` and `protocol-serialization`; socket creation,
authentication policy, and client/server orchestration remain in their owning modules.

`MinecraftConnectionDefinition` and loader definition objects are immutable and shareable. One-connection profile
instances retain caller-owned static schemas, registry snapshots, packet registrations, and resolved contexts by
reference; do not introduce global mutable registries or per-connection copies of large immutable data. Unknown valid
extension routes remain lossless `UnknownPacket` values. Known malformed bytes and state/order failures propagate and
never trigger an automatic reply.

Run `:protocol-session:jvmTest` after changes. State-machine changes also require the affected client and server JVM
suites.
