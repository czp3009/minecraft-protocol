# Minecraft test-support guidance

This module inherits the repository guidance.

- Expose ordinary JVM library APIs called directly by standard `jvmTest`
  source sets.
- Acquire, verify, cache, and prepare test-only official artifacts at test runtime; do not require Gradle preparation
  tasks or system-property wiring.
- Manage external Minecraft and launcher processes as test resources. Never turn this library into a CLI application.
- Keep module-specific protocol assertions in the consuming module's tests.
- Keep shared caches and all test work under repository or module `build/`
  directories.
