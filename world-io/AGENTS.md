# world-io

This module owns world paths and filesystem adapters built on Okio. Kotlin/JS supports the Node runtime through its real
filesystem; browser and Wasm targets use the stream modules and do not receive a partial filesystem implementation.

## Invariants

- Common production code contains no Java or platform filesystem APIs.
- Convert between Okio sources or sinks and `kotlinx.io` only through `kotlinx-io-okio`; keep size limits, byte counts,
  durable flushes, and ownership policy in this module instead of duplicating the library's transport adapters. At the
  stream boundary, retain the adapters' documented mapping into the wrapping library's I/O exception hierarchy.
- Standalone NBT stores compose `nbt-serialization` with compression and filesystem policy; byte grammar remains in the
  serialization module.
- Current paths derive from official resource constants and migration code; historical paths remain explicit API
  variants.
- Region updates allocate and write new sectors in place while the old allocation remains reserved. They commit the
  complete header before retiring old sectors, never replace a complete MCA, and preserve the official sidecar order.
- Region timestamps and internal/external selection are storage policy. NBT convenience writes use the configured
  official or registered CUSTOM compression, while raw chunk writes may provide an already-compressed GZIP, ZLIB, NONE,
  LZ4, or CUSTOM payload; neither API accepts caller-controlled timestamps or external markers.
- Standalone files keep their distinct official policies: level/player NBT use sibling temporary files and backups,
  dimension saved data uses a synced direct write, and player JSON truncates and writes its final path directly.
- System-filesystem world access holds `session.lock` until all owned region stores close. Injectable raw stores do not
  pretend a fake filesystem provides a cross-process lock.
- Establish `session.lock` behavior from the matching official server's `DirectoryLock` first and the repository Java
  major's OpenJDK `FileChannel` implementation second. The JVM path mirrors the official control flow and exception
  ordering; Native and Node reproduce the same observable open, marker-write, force, non-blocking whole-file lock, and
  cleanup semantics. Confirmed in-process overlap, non-blocking lock refusal, and marker-write lock violation are
  exposed uniformly as `WorldLockException`; unrelated open, permission, and device I/O failures retain their Okio I/O
  type.
- Node durable writes use the host `fsync` primitive, and its `session.lock` uses the pinned native addon to request the
  same non-blocking whole-file exclusive OS lock as the official JVM implementation.

## Tests

Filesystem behavior expressible through Okio or its fake filesystem belongs in `commonTest`. Keep the shared
official-server test's Host-filesystem namespace restriction explicit in its KDoc. Each execution writes marked GZIP,
ZLIB, NONE, and LZ4 chunks through that platform's public store API and requires the matching official server to load,
save, restart, and reload them. The runner and its annotated entry live in the unique `hostFilesystemTest` capability
source set. JVM, JS Node, and desktop Native test source sets depend on it directly and do not need platform-specific
source files; Android Host and every isolated/device/Wasm runtime inherit portable `commonTest` coverage only. Its
non-default official-server properties automatically select prepared runtime without the stopped default world template;
no workspace policy crosses the test API. A longer `runTest` timeout is allowed for this real process path, but
readiness remains event-driven. Other JVM-specific filesystem oracles belong in `jvmTest`.

Run `:world-io:jvmTest` first, then `:world-io:jsNodeTest` for Node filesystem changes.
