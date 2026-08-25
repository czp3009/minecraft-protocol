# minecraft-test-support

This module is the portable official-peer test contract consumed by other modules. Its production surface is limited to
serializable resource values, the kotlinx.rpc service, the test-process client, and structured-use helpers.

## Remote contract

- Fixture operations are suspend RPC calls. Process objects, artifact discovery, launchers, Gradle types, and host
  implementations never cross the boundary.
- `hostWorkingDirectory` is the explicit exception for same-host filesystem interoperability. Keep its non-portable
  nature visible in KDoc and restrict its use to `world-io`'s host-filesystem test capability.
- `MinecraftTestSupport` lazily owns one kRPC client, reads its endpoint and owner ID from Gradle-provided environment
  variables, and may reconnect after an operation closes the transport. Concurrent operations lease the same current
  client; a terminal or failed operation detaches it, and physical close waits until its existing leases finish. Do not
  add hand-written RPC routing or process abstraction.
- `closeProcess()` waits for process exit while retaining the slot and workspace. `deleteWorkingDirectory()` requires a
  stopped process and releases both. `close()` schedules the same combined cleanup idempotently and returns after host
  acceptance; `use` is the ordinary structured helper.
- Workspace policy is not public. The host derives template versus fresh state from the complete resource configuration.
- `newHeadlessClient` returns a title-ready process. `connectHeadlessClient` returns a correlated post-command GUI
  snapshot; `headlessClientState` returns another snapshot; `disconnectHeadlessClient` waits for a newly observed title
  screen. These snapshots prove command/liveness state only. TCP acceptance and observed protocol packets provide
  connection and Play evidence.
- The private WasmWASI target is compile-time scaffolding, not runtime fixture support; unsupported environments receive
  no fake implementation.

## Verification

Run `:minecraft-test-support:jvmTest` after contract or client changes.
