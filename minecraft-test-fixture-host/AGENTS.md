# minecraft-test-fixture-host

This private JVM module implements the remote fixture boundary: official artifact paths, external processes, the
four-slot pool, isolated workspaces, bounded logs, readiness checks, codec execution, and cleanup.

## Host and resource lifecycle

- Only `MinecraftTestFixtureService` launches the host. Its loopback control protocol is trusted repository
  infrastructure, not a public CLI or compatibility surface.
- Associate each resource with the task-owner ID. A pool slot remains occupied from creation through a stopped process
  with retained files until workspace deletion.
- `HostedFixtureResources` tracks in-flight creation jobs per owner. Closing an owner seals it against new resources,
  cancels its creation jobs, detaches already registered resources, and closes them. A resource that finishes startup
  after owner closure must be cleaned instead of becoming visible.
- `closeProcess` waits for graceful or forced termination and retains files. `deleteWorkingDirectory` requires exit and
  releases the slot. Combined close schedules those stages idempotently and never deletes a live process's workspace.
- Process cleanup and directory deletion are attempted independently. Complete registry, slot, and filesystem rollback
  for both stages and attach cleanup failures to the primary failure.
- Each process gets a unique workspace and host-selected endpoint. Bind failures retry before a ready resource is
  returned.
- Never launch immutable prepared roots or templates in place. Keep the symlink/hard-link/copy behavior in the existing
  filesystem helpers, copy mutable files, and unlink directory symlinks without traversing their targets.

## Readiness and commands

- Official-server readiness first observes a new official `Done (` server-thread event, then obtains a complete Status
  response and pong while monitoring process exit.
- A new headless client is ready after HMC-Specifics initialization and a correlated `gui` response reports the vanilla
  title screen.
- Connecting waits for another correlated `gui` response after scheduling the connect command. That GUI snapshot is
  control/liveness evidence, not proof of TCP connection or Play; the accepting peer and protocol packets provide those
  proofs.
- Normal headless close sends `quit`, waits for correlated output, EOF, process exit, and exit code zero. Forced
  process-tree termination is a cleanup fallback, not a successful lifecycle result.
- Output has a monotonically increasing sequence. Record the pre-send sequence and accept only later markers; serialize
  marker-waiting commands per resource.
- Publish process exit only after ordinary trailing output drains. Bound that drain so a descendant that inherited an
  output pipe cannot keep the resource alive indefinitely.
- Child JVM commands use `fixtureJavaCommand`, which inserts the shared native-access argument in the required position.

## Protocol and cleanup

- Logs and codec inputs cross kRPC as JSON values. Successful codec verification has no payload; failures carry bounded
  diagnostics. Process objects never cross.
- Serialize official-codec invocations because they temporarily mutate JVM-global logging and JOML properties, and
  restore every property plus the thread context class loader while preserving the primary failure.
- `hostWorkingDirectory` returns the absolute Host path only for the documented same-filesystem interoperability test.
- Standard input is reserved for Build Service control and standard output for the single `READY_PREFIX` announcement.
  All diagnostics use kotlin-logging.
- Consume only Gradle-supplied paths and manifests. The host never downloads, repairs, rediscovers, hashes, or
  size-checks prepared resources.
- Keep mutable state below `build/minecraft-test-support/` and remove it after use. Owner cleanup handles aborted tasks;
  host shutdown is the final fallback.

## Verification

Run `:minecraft-test-fixture-host:test` after host, process, pool, filesystem, or protocol-handler changes.
