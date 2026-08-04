# minecraft-test-fixture-host

This private JVM module owns everything behind the remote fixture boundary: official artifact paths, external processes,
the eight-slot pool, unique workspaces, bounded logs, readiness probes, reports, codec execution, world transfer, and
final cleanup.

## Lifecycle

- Only `MinecraftTestFixtureService` launches the host. It remains project-internal infrastructure, not a user-facing
  CLI or custom E2E task.
- The control server binds to loopback and associates each resource with the task-owner ID supplied by the Build
  Service.
- The control protocol is trusted, project-internal, and versioned only for this repository. It has no authentication,
  authorization, TLS, discovery, persistence, or public compatibility layer.
- A pool slot covers startup, running, process termination, and workspace deletion. Creation suspends while all slots
  are occupied. Close schedules idempotent cleanup and returns immediately; the slot is released after cleanup
  completes.
- Every process has a unique workspace and an internally selected endpoint. Official-server readiness requires a full
  status response and pong while process exit is monitored; bind failures retry before a ready handle is returned.
- Directly launched processes remain retained until cleanup. A launcher's supported in-process mode is used instead of
  an opaque descendant process where available.
- A test in one subproject and platform amortizes a compatible process across sequential phases through one remote
  handle created and closed inside the annotated test scenario. A global or class-scoped handle is reserved for a
  genuinely suite-scoped stateless or recoverable fixture with explicit after-all cleanup; it is not a way to hide
  startup from a test timeout. Owner cleanup handles an aborted test task, and Host shutdown remains the final fallback.
  Fixture tests likewise combine compatible lifecycle assertions around one subprocess. Fresh processes remain mandatory
  when termination, forced cleanup, inherited pipes, a clean workspace, or a fixed endpoint is under test. The Host does
  not pool mutable processes across separate platform test-task owners by default.

Logs, reports, codec inputs/results, and world snapshots cross the kRPC WebSocket as JSON data. Host paths and process
objects never cross the protocol.

The host process reserves standard input for Build Service control commands and standard output for its single
`READY_PREFIX` connection announcement. That machine-readable handshake is not a diagnostic log. Host diagnostics use
kotlin-logging, and the raw-standard-stream programs under `src/jvmTest/resources` exist only to verify process-pipe
behavior.

Consume only artifact and directory providers supplied by Gradle. Gradle owns download integrity; the host reads the
required metadata and paths but never downloads, repairs, rediscovers, or rehashes immutable fixture downloads. Mutable
filesystem state stays below the explicit `build/minecraft-test-support/` work root.

Run `:minecraft-test-fixture-host:jvmTest` after host, process, pool, or protocol-handler changes.
