# protocol-server

## Negotiation

- `accept` returns a direction-bound connection without negotiating. Status negotiation closes and returns `null`;
  Login negotiation returns after sending the first Play Login, before initial-world synchronization.
- `MinecraftServerNegotiationOptions` holds protocol-visible configuration; `MinecraftServerNegotiationPolicy` holds
  per-connection decisions. Difficulty, difficulty locking, abilities and semantic Chunk defaults belong to the
  initial-world input. Do not read `server.properties` here.
- Fire-and-forget Configuration additions use `configurationPackets`; response-gated work uses ordered
  `configurationTasks`. Distinguish unhandled packets, consumed progress and completion; consumed progress must not fall
  through to unexpected-packet handling. Do not rescan extension traffic as framework-owned traffic.
- Resource-pack configuration follows the official dedicated server's single optional offer. ACCEPTED and DOWNLOADED
  wait; a declined required pack raises a Configuration rejection, while other terminal responses continue. Do not
  strengthen `required` into success-only admission. Acceptance prompts and disconnect reasons are distinct inputs.
- Construct Configuration packets from `ConfigurationData` at this send boundary. A stored-world application supplies
  its projected Configuration data, resolved dimension IDs and selected dimension explicitly.
  The default Play Login resolves `initialDimensionTypeId` in that Configuration data independently of
  `initialDimensionId`; the two identifiers need not match.
- Online Login owns the timing of `/hasJoined`. Negotiation, codec and state failures propagate without an automatic
  disconnect or loader-failure response; the caller chooses the response and connection lifetime.
- Preset negotiation switches managed KeepAlive from Configuration to Play at the acknowledgement boundary. Custom
  negotiation and reconfiguration own the corresponding explicit run replacement.

## Initial world

- `MinecraftInitialWorld` holds caller-prepared bootstrap data, Chunks, a `ChunkPacketEncoder` and optional Entity
  batches. Sending enqueues a finite sequence; it neither generates data nor waits for acknowledgements.
- `protocol-world` owns per-value conversion. This endpoint owns ordering across caller-supplied Chunks/Entities,
  bundles and enqueue. Applications own visibility selection, pending queues, tick scheduling and Chunk-batch feedback
  policy. Rebind codecs after a registry epoch or dimension change.
- The negotiation-result Chunk-encoder factory uses its retained dimension/registry facts and requests only application
  defaults, write mappings and required-data providers. It constructs the shared plain codec, with no connection access.
- `MinecraftEntityBatch` supplies connection-local `EntityPairingData` for each Entity. Tracking IDs and relations do
  not become persisted Entity fields.
- Abilities are explicit bootstrap values. Test-only terrain and ability builders must not become production presets.

## Verification

Run `:protocol-server:jvmTest`. Its official-client runner retries only connection acquisition with a fresh loopback
listener, reuses the title-ready client, disconnects between attempts and retains each attempt's diagnostics. The
accepted socket and observed packets prove protocol progress; deterministic protocol assertions are not retried.
