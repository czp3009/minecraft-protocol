# minecraft-test-support

This module is the only official-peer test dependency consumed by subprojects. Its production surface is limited to the
KMP kotlinx.rpc contract, Ktor client, serializable models, remote server/client handles, and structured-use helpers.

## Remote boundary

- Official fixtures are always remote. Status, logs, commands, event waits, reports, codec verification, and world
  snapshots cross the API as suspend operations.
- Host paths, process objects, official-artifact discovery, Gradle types, launchers, report writers, and host-filesystem
  implementations do not belong in this module.
- The generated kRPC client uses Ktor WebSocket and JSON. This module has no hand-written request routing, downloader,
  process abstraction, or reconnect protocol.
- `close()` is idempotent and returns after the host accepts asynchronous cleanup. `useRemote` is the structured-use
  helper; task-owner and Build Service cleanup are host-side fallbacks.

Fixture entry points and scenarios belong in each consumer's `commonTest`. A standard platform test source set contains
only an unavoidable replaceable implementation. Unsupported devices and runtimes do not receive fake reachability. A
world snapshot crosses RPC as a map of relative paths to file contents; the `world-io` client materializes it in a
self-owned system temporary directory that has no relationship to a Fixture Host path. Ordinary protocol tests stay
filesystem-free.

Run `:minecraft-test-support:jvmTest` after contract or client changes.
