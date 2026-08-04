# protocol-session

This module binds typed packet codecs to framed transport and owns connection state, packet direction, packet IDs,
compression activation, and protocol-state transitions.

State changes occur only after the transition packet crosses the wire. Authentication code activates encryption after
the Encryption Response exchange. Packet payload rules remain in `protocol-model` and `protocol-serialization`; socket
creation, authentication policy, and client/server orchestration remain in their owning modules.

Run `:protocol-session:jvmTest` after changes. State-machine changes also require the affected client and server JVM
suites.
