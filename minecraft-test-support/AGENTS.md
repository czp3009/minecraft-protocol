# Minecraft test-support guidance

This module inherits the repository guidance.

- Expose ordinary Kotlin Multiplatform library APIs called directly by standard test source sets. Keep external-process
  support on JVM, desktop Native, and Node runtimes; do not fake process support on mobile devices or browsers.
- Consume only immutable official artifacts prepared and verified by the root Gradle download tasks. Test runtime code
  validates those local inputs but never downloads, refreshes, or repairs them and never relies on system properties for
  fixture paths.
- Manage external Minecraft and launcher processes as test resources. Never turn this library into a CLI application.
- Prefer a launcher's supported in-process mode so the resource directly owns the actual peer process. Do not discover
  descendants by polling the operating-system process table or maintain a global PID registry; retain each process
  object created by the resource and make cleanup idempotent.
- Resource objects own artifact preparation, an atomically unique work directory, configuration, process lifecycle,
  bounded logs, readiness, final endpoint, and cleanup. Expose the endpoint only after readiness succeeds.
- Determine official-server readiness through a complete network status-response and pong exchange while monitoring
  process exit; startup log text may locate the lifecycle phase but is not itself a ready endpoint.
- Share only immutable verified downloads implicitly. Callers may deliberately share one resource through ordinary test
  lifecycle scope, but the library never pools running processes by configuration; retry official-server bind failures
  rather than exposing a guessed port.
- `MinecraftTestSupport` owns the executable-wide cleanup scope and resource registry. Each resource has one
  `official-{server,client}/<version>/<UUID>` directory, implements `AutoCloseable`, makes `close()` idempotent and
  non-blocking, and completes process/directory cleanup asynchronously with shutdown cleanup as a best-effort fallback.
- Keep portable network scenarios and assertions in the consuming module's `commonTest`; capability-gated test entry
  points live in the shared `hostProcessTest` source set used by JVM, host desktop Native, and Node targets. The
  official
  peer being a JAR is not a reason to make otherwise portable protocol logic JVM-specific.
- Keep module-specific protocol assertions in the consuming module's tests.
- Keep shared caches and all test work under repository or module `build/`
  directories.
