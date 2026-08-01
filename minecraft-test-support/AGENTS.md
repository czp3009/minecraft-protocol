# Minecraft test-support guidance

This module inherits the repository guidance.

- Expose ordinary Kotlin Multiplatform library APIs called directly by standard test source sets. Keep external-process
  support on JVM, desktop Native, and Node runtimes; do not fake process support on mobile devices or browsers.
- Acquire, verify, cache, and prepare test-only official artifacts at test runtime; do not require Gradle preparation
  tasks or system-property wiring.
- Use Ktor's timeout/retry plugins and suspending streaming APIs for downloads, `kotlinx-io` for the streamed body, and
  structured coroutine workers for parallel acquisition. Do not add blocking HTTP clients, sleeps, or executor pools.
- Manage external Minecraft and launcher processes as test resources. Never turn this library into a CLI application.
- Prefer a launcher's supported in-process mode so the resource directly owns the actual peer process. Do not discover
  descendants by polling the operating-system process table or maintain a global PID registry; retain each process
  object created by the resource and make cleanup idempotent.
- Resource objects own artifact preparation, an atomically unique work directory, configuration, process lifecycle,
  bounded logs, readiness, final endpoint, and cleanup. Expose the endpoint only after readiness succeeds.
- Determine official-server readiness through a complete network status-response and pong exchange while monitoring
  process exit; startup log text may locate the lifecycle phase but is not itself a ready endpoint.
- Share only immutable verified downloads. Never share a running process, port, world, game directory, or mutable log;
  retry official-server bind failures rather than exposing a guessed port.
- Keep portable network scenarios and assertions in the consuming module's `commonTest`; capability-gated test entry
  points may live in a shared host-process test source set used by JVM, desktop Native, and Node targets. The official
  peer being a JAR is not a reason to make otherwise portable protocol logic JVM-specific.
- Keep module-specific protocol assertions in the consuming module's tests.
- Keep shared caches and all test work under repository or module `build/`
  directories.
