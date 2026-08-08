# protocol-session

This module binds typed packet codecs to framed transport and owns connection state, packet direction, packet IDs,
compression activation, and protocol-state transitions.

The published session follows `protocol-transport` onto JVM, Android, supported Native platforms, JS Node, and WasmJS
Node. Browser, D8, and Wasm/WASI are not configured because the public session contract requires TCP transport.

State changes occur only after the transition packet crosses the wire. Authentication code activates encryption after
the Encryption Response exchange. Packet payload rules remain in `protocol-model` and `protocol-serialization`; socket
creation, authentication policy, and client/server orchestration remain in their owning modules.

Run `:protocol-session:jvmTest` after changes. State-machine changes also require the affected client and server JVM
suites.
