# Minecraft test support

This private JVM library provides reusable, Testcontainers-style fixtures for the repository's standard `jvmTest`
suites. Calling its APIs acquires and verifies official artifacts, prepares isolated runtimes, and manages test-local
files.

It is not published and does not add Gradle preparation, interoperability, or verification tasks.
