# Launcher demo

This subproject is a private terminal application that demonstrates `account-auth` and `protocol-auth`; it is not a
published library or a protocol-client example. It downloads an official game layout, manages offline and Microsoft
accounts, and starts the official Java client.

## Local design

- `LauncherController` owns screen state and operation cancellation. UI composables render that state and delegate
  actions; they do not perform downloads, authentication, persistence, or process control directly.
- `LauncherStore` owns `auth.json`, `installed.json`, and per-version directories below the launcher's canonical working
  directory. Keep writes atomic, validate decoded state before use, and key installations by both version and platform.
- `AccountService` owns the loopback OAuth callback, account replacement, entitlement/profile completion, and serialized
  refresh per identity. A failed refresh remains visible as expired login state until an explicit sign-in succeeds.
- `MetadataPlanner` is intentionally strict: it accepts only metadata shapes it can evaluate safely, preserves argument
  boundaries and rule order, and rejects traversal, legacy arguments, native-classifier libraries, and unsupported asset
  layouts. Do not silently guess how to launch an unsupported version.
- `ResourceDownloader` streams into a sibling temporary file, verifies the declared size and SHA-1, and publishes with
  an atomic move. Cancellation stops retries and incomplete downloads never become installed state.
- `GameProcessService` validates the available Java major, removes its temporary argument file, drains both output
  streams, normalizes terminal text, and redacts the online access token. The token must never enter the Java argument
  file or an error shown by the TUI.
- Mosaic owns the terminal. Keep JVM logging on the configured no-op backend and do not mix diagnostic output with the
  interactive screen.

## Verification

Run `:demo:launcher:jvmTest` first. Storage, HTTP, planning, controller, and UI behavior belongs in portable tests using
the fake filesystem and mock HTTP engine. Process-stream behavior has target-specific entries; after changing packaging
or native process behavior, compile or install the affected distribution task documented in [README.md](README.md).
