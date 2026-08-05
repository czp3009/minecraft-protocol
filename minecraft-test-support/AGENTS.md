# minecraft-test-support

This module is the only official-peer test dependency consumed by subprojects. Its production surface is limited to the
KMP kotlinx.rpc service, test-process Ktor client, serializable server/client values, and structured-use helpers.

## Remote boundary

- Official fixtures are always remote. Status, logs, commands, event waits, codec verification, and world snapshots
  cross the API as suspend operations. Successful verification returns without a separate result payload or file;
  failures cross as exceptions with diagnostic details.
- Host paths, process objects, official-artifact discovery, Gradle types, launchers, and host-filesystem implementations
  do not belong in this module.
- `MinecraftTestSupport` lazily owns at most one `MinecraftTestSupportServiceClient` at a time. The client reads its RPC
  URL and task-owner ID from the Gradle-provided environment, delegates the complete `MinecraftTestSupportService` API
  to the generated kRPC proxy, and uses Ktor WebSocket with JSON. Resource close and standalone codec verification close
  the transport so Node test processes can exit; a later operation reconnects lazily. This module has no hand-written
  request routing, per-method RPC forwarding layer, downloader, process abstraction, or transport recovery protocol.
- `close()` is idempotent and returns after the host accepts asynchronous cleanup. `use` is the structured-use
  helper; task-owner and Build Service cleanup are host-side fallbacks.

Fixture entry points and scenarios belong in each consumer's `commonTest`. A standard platform test source set contains
only an unavoidable replaceable implementation. Unsupported devices and runtimes do not receive fake reachability. A
world snapshot crosses RPC as a map of relative paths to file contents; the `world-io` client materializes it in a
self-owned system temporary directory that has no relationship to a Fixture Host path. Ordinary protocol tests stay
filesystem-free. The annotated consumer test creates the remote process required by its scenario and closes its
serializable resource value with structured cleanup. Reuse one resource across compatible ordered phases, but do not
move startup into lifecycle hooks merely to exclude it from the test timeout.

Run `:minecraft-test-support:jvmTest` after contract or client changes.
