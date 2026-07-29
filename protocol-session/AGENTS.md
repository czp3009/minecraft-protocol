# protocol-session

This module binds typed packet codecs to a framed transport and owns connection state, packet direction, packet IDs,
compression activation, and protocol state transitions.

Session state changes occur only after the transition packet has crossed the wire. Encryption is activated explicitly by
authentication code after the Encryption Response exchange.

Packet payload rules remain in `protocol-model` and `protocol-serialization`. Socket creation, authentication policy,
and client/server behavior remain in their owning modules.
