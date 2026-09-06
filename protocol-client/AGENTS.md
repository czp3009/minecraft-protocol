# protocol-client

- `MinecraftClientConnection` exposes direction-limited channels and committed state, not its socket, frame stream or
  mutable session. Its constructor can wrap a caller-supplied packet connection; `connect` owns socket creation.
- `negotiate` exclusively borrows both channels until the first Play Login has been received. Initial-world packets
  remain on `incoming` for the application's packet loop.
- Resource-pack callbacks own consent, downloading and applying; negotiation owns status packets. Run callbacks as
  structured children while the Configuration receive loop remains responsive. Replacement/Pop cancels pending work;
  failures and cancellation must leave no callback running after negotiation. Do not create an HTTP client or infer
  successful application from downloaded bytes.
- The low-level client endpoint consumes and answers direct Configuration/Play KeepAlive requests. Do not duplicate
  those replies in negotiation or projection helpers.
- Online Login owns the timing of the Session Server `/join` call; account acquisition stays outside the flow.
- `MinecraftClientNegotiationResult` retains one `MinecraftDimensionContext` and one `DataPackConfigurationSnapshot`.
  Layout conveniences derive from that context, and captured pack fields are not copied onto a second result.
  Registry-view resolution uses that result's retained context and requires no live connection or I/O.
- The result's Chunk-decoder factory uses its initial dimension/registry facts and requests only application defaults,
  read mappings and missing-data providers. It returns the shared plain codec without retaining the connection.
- Install the resolved registry context on the connection. Domain block/biome defaults and missing packet fields enter
  only through the caller's world codec contexts; negotiation does not choose or cross-validate them.
- Shared registry/layout resolution comes from `protocol-configuration`; world decoding comes from `protocol-world`.
  Endpoint adapters own bundle registration and unresolved relation handling. Rebind world codecs after a registry
  epoch or dimension change.

Run `:protocol-client:jvmTest`, including its official-server scenario.
