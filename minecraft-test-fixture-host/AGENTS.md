# minecraft-test-fixture-host

This private JVM module owns everything behind the remote fixture boundary: official artifact paths, external processes,
the eight-slot pool, unique workspaces, bounded logs, readiness probes, codec execution, the working-directory backdoor,
and final cleanup.

## Lifecycle

- Only `MinecraftTestFixtureService` launches the host. It remains project-internal infrastructure, not a user-facing
  CLI or custom E2E task.
- The control server binds to loopback and associates each resource with the task-owner ID supplied by the Build
  Service.
- The control protocol is trusted, project-internal, and versioned only for this repository. It has no authentication,
  authorization, TLS, discovery, persistence, or public compatibility layer.
- A pool slot covers startup, running, a stopped process with retained files, process termination, and workspace
  deletion. Creation suspends while all slots are occupied. `closeProcess` waits for graceful-or-forced termination and
  retains the workspace and slot. `deleteWorkingDirectory` waits for deletion and releases the slot. Close schedules
  those same two stages idempotently and returns immediately; cleanup never deletes a workspace before its process has
  exited.
- Every process has a unique workspace and an internally selected endpoint. Official-server readiness requires a full
  status response and pong while process exit is monitored; bind failures retry before a ready resource value is
  returned.
- The immutable official-server and headless-client roots are never launched in place. An exact default server
  configuration clones the stopped server template; any non-default server option starts from the extracted immutable
  runtime without template state. A headless client's required player name is independent of template selection:
  default optional lifecycle values clone the stopped client template, while non-default optional values use the
  assembled runtime and a fresh game directory. The complete read-only client Minecraft runtime and official-server
  library directory use one directory symbolic link when supported. If symbolic links are unavailable, they use private
  directory trees with per-file hard links or copies. Other immutable runtime files, the HeadlessMC launcher, the sole
  client mod, and Fabric's processed-mod cache use hard links with copy fallback. Generated options and all other
  mutable template files always use real copies. Deletion unlinks directory symbolic links without traversing their
  targets.
- Server templates retain the generated world, stable empty access-control files, EULA, and empty directory skeleton;
  extracted server libraries and versions are immutable runtime. Client templates retain generated options, the sole
  HMC-Specifics mod, Fabric's processed-mod cache, and every reusable empty directory. Sanitization removes file content
  under the fixed manifest paths without deleting reusable empty directories.
- A new headless client is ready only after HMC-Specifics initializes and a post-command `gui` response reports the
  vanilla title screen. Connecting is explicit and does not claim Play; production-server packet observations establish
  Play. Normal close sends HMC-Specifics `quit`, waits for correlated new output, output EOF, process exit, and code
  zero. Forced process-tree termination is cleanup fallback, not a successful lifecycle result.
- Directly launched processes remain retained until cleanup. HeadlessMC uses its wrapper and supported in-memory launch
  mode instead of an opaque descendant process.
- Every official-server and HeadlessMC child command uses `fixtureJavaCommand`, which executes literal `java` from
  `PATH` and inserts `--enable-native-access=ALL-UNNAMED` before application arguments. Build logic supplies the same
  flag to this host JVM because the codec oracle runs in-process through its isolated class loader.
- A test in one subproject and platform amortizes a compatible process across sequential phases through one serializable
  resource value created and closed inside the annotated test scenario. A global or class-scoped resource is reserved
  for a
  genuinely suite-scoped stateless or recoverable fixture with explicit after-all cleanup; it is not a way to hide
  startup from a test timeout. Owner cleanup handles an aborted test task, and Host shutdown remains the final fallback.
  Fixture tests likewise combine compatible lifecycle assertions around one subprocess. Fresh processes remain mandatory
  when termination, forced cleanup, inherited pipes, a clean workspace, or a fixed endpoint is under test. The Host does
  not pool mutable processes across separate platform test-task owners by default.

Logs cross the kRPC WebSocket as JSON data. Codec inputs cross as JSON values; success has no result payload, and
verification failures cross as exceptions with bounded diagnostics. Process objects never cross the protocol.
`hostWorkingDirectory` is the one documented path backdoor: it returns an absolute Host path to same-filesystem tests
without transferring or wrapping the directory contents.

Process output has a monotonically increasing internal sequence. Every command that waits for a marker records its
pre-send sequence and accepts only later output; commands for one resource are serialized. A marker left by startup or a
previous command cannot satisfy a later operation.

The host process reserves standard input for Build Service control commands and standard output for its single
`READY_PREFIX` connection announcement. That machine-readable handshake is not a diagnostic log. Host diagnostics use
kotlin-logging, and the raw-standard-stream programs under `src/test/resources` exist only to verify process-pipe
behavior.

Consume only artifact and directory providers supplied by Gradle. The host reads their manifests and paths but never
downloads, repairs, rediscovers, hashes, or size-checks prepared fixture resources. Mutable filesystem state stays below
the explicit `build/minecraft-test-support/` work root and is deleted after use. Do not persist standalone files merely
to record successful test execution.

Run `:minecraft-test-fixture-host:test` after host, process, pool, or protocol-handler changes.
