# minecraft-test-support

This module is the only official-peer test dependency consumed by subprojects. Its production surface is limited to the
KMP kotlinx.rpc service, test-process Ktor client, serializable server/client values, and structured-use helpers.

## Remote boundary

- Official fixtures are always remote. Status, logs, commands, event waits, lifecycle operations, and codec verification
  cross the API as suspend operations. Successful verification returns without a separate result payload or file;
  failures cross as exceptions with diagnostic details.
- Process objects, official-artifact discovery, Gradle types, launchers, and host-filesystem implementations do not
  belong in this module. `hostWorkingDirectory` is the deliberate exception for same-host filesystem interoperability:
  it returns the Host's absolute path as a string and clearly documents that this backdoor is not portable to a remote,
  containerized, device, or otherwise isolated test process.
- `MinecraftTestSupport` lazily owns at most one `MinecraftTestSupportServiceClient` at a time. The client reads its RPC
  URL and task-owner ID from the Gradle-provided environment, delegates the complete `MinecraftTestSupportService` API
  to the generated kRPC proxy, and uses Ktor WebSocket with JSON. Resource close and standalone codec verification close
  the transport so Node test processes can exit; a later operation reconnects lazily. This module has no hand-written
  request routing, per-method RPC forwarding layer, downloader, process abstraction, or transport recovery protocol.
- `closeProcess()` waits until resource-specific graceful shutdown or forced termination has ended the process while
  retaining its working directory and slot. `deleteWorkingDirectory()` requires a stopped process and waits until the
  directory is deleted and the slot released. `close()` remains idempotent and returns after the Host accepts the same
  combined process-and-directory cleanup asynchronously. `use` is the structured-use helper; task-owner and Build
  Service cleanup invoke that combined Host implementation as fallbacks.

Fixture runners normally belong in each consumer's `commonTest`, and their annotated entries normally do as well.
Unsupported devices and runtimes do not receive fake reachability. Ordinary protocol tests stay filesystem-free. The
`world-io` official runner closes the remote process, obtains its Host working directory through the documented
backdoor, and opens that directory only when invoked by JVM, Android host, or desktop Native test entries. Device,
simulator, and browser source sets do not contain that entry. The scenario creates the remote process it needs and
closes its serializable resource value with structured cleanup. Reuse one resource across compatible ordered phases, but
do not move startup into lifecycle hooks merely to exclude it from the test timeout.

Run `:minecraft-test-support:jvmTest` after contract or client changes.
