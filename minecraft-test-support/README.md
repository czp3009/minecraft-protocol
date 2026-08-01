# Minecraft test support

This private Kotlin Multiplatform library provides reusable, Testcontainers-style fixtures for the repository's standard
tests. Its external-process resources run on JVM, desktop Native, and Node-based JS/Wasm targets. Calling its APIs
acquires and verifies official artifacts, prepares isolated runtimes, and manages test-local files and processes.

It is not published and does not add Gradle preparation, interoperability, or verification tasks.
