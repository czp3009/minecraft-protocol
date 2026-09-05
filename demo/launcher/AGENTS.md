# Launcher demo

This subproject is a private terminal application that demonstrates `distribution-metadata`, `account-auth`, and
`protocol-auth`. It downloads an official game layout, manages offline and Microsoft accounts, and starts the official
Java client.

## Local design

- `LauncherController` owns screen state and operation cancellation. UI composables render that state and delegate
  actions; they do not perform downloads, authentication, persistence, or process control directly.
- `LauncherStore` owns `auth.json`, `installed.json`, and per-version directories below the launcher's canonical working
  directory. Keep writes atomic, validate decoded state before use, and key installations by version because all
  launcher targets on one machine start the same official Java client from the same game directory.
- `AccountService` owns the loopback OAuth callback, account replacement, entitlement/profile completion, and serialized
  refresh per identity. A failed refresh remains visible as expired login state until an explicit sign-in succeeds.
- `MetadataPlanner` consumes the modern models from `distribution-metadata`, preserves argument boundaries and rule
  order, and rejects unsafe installation paths and unknown launch rules. Use the module's `toDownload()` projections and
  `minecraftAssetPath(hash)`; the planner supplies installation directory prefixes. Expand `defaultUserJvm` before the
  version's `jvm` arguments; only user-supplied JVM options may replace `defaultUserJvm` if such an option is added.
- `LauncherPlatform` detects the host OS version with system commands shared by JVM and Native. Preserve the Windows
  build number for version ranges; evaluate ranges only after the rule's OS and architecture match.
- `InstallationService` reads metadata through the shared HTTP client's distribution API and writes the typed asset
  index atomically. `ResourceDownloader` consumes the distribution API's download streams, verifies their declared size
  and SHA-1, and publishes with an atomic move. Cancellation stops retries and incomplete downloads never become
  installed
  state.
- `GameProcessService` validates the available Java major, removes its temporary argument file, drains both output
  streams, normalizes terminal text, and redacts the online access token. The token must never enter the Java argument
  file or an error shown by the TUI.
- Mosaic owns the terminal. Keep JVM logging on the configured no-op backend and do not mix diagnostic output with the
  interactive screen.

## Verification

Run `:demo:launcher:jvmTest` first. Storage, HTTP, planning, controller, and UI behavior belongs in portable tests using
the fake filesystem and mock HTTP engine. Process-stream behavior has target-specific entries; after changing packaging
or native process behavior, compile or install the affected distribution task documented in [README.md](README.md).
