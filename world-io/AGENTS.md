# world-io

This module owns world paths and filesystem adapters built on Okio. Kotlin/JS supports the Node runtime through its real
filesystem; browser and Wasm targets use the stream modules and do not receive a partial filesystem implementation.

## Invariants

- Common production code contains no Java or platform filesystem APIs.
- Convert between Okio sources or sinks and `kotlinx.io` only through `kotlinx-io-okio`; keep size limits, byte counts,
  durable flushes, and ownership policy in this module instead of duplicating the library's transport adapters. At the
  stream boundary, retain the adapters' documented mapping into the wrapping library's I/O exception hierarchy.
- Public filesystem/store entry points expose Okio I/O exceptions. Normalize only a downstream `kotlinx.io.IOException`
  left after an official adapter crossing, restore a preserved Okio cause when present, and leave format/NBT,
  argument/state, cancellation, and cleanup-rethrow failures in their owning semantic category.
- Standalone NBT stores compose `nbt-serialization` with compression and filesystem policy; byte grammar remains in the
  serialization module.
- Current paths derive from official resource constants and migration code; historical paths remain explicit API
  variants.
- Region updates allocate and write new sectors in place while the old allocation remains reserved. They commit the
  complete header before retiring old sectors, never replace a complete MCA, and preserve the official sidecar order.
- Region timestamps and internal/external selection are storage policy. NBT convenience writes use the store's
  configured default or a per-write official or registered CUSTOM compression. `MinecraftWorldAccess` shares one region
  configuration across every storage directory and dimension. These choices affect only newly encoded NBT and never
  migrate untouched chunks. Raw chunk writes may provide an already-compressed GZIP, ZLIB, NONE, LZ4, or CUSTOM payload;
  neither API accepts caller-controlled timestamps or external markers.
- Standalone files keep their distinct official policies: level/player NBT use sibling temporary files and backups,
  dimension saved data uses a synced direct write, and player JSON truncates and writes its final path directly.
- System-filesystem world access uses `session.lock` only as the process-exclusive world-directory lease and holds it
  until every admitted operation and owned resource has drained. It is not a world-wide I/O mutex. Injectable raw stores
  do not pretend a fake filesystem provides a cross-process lock.
- Mutable high-level stores coordinate each logical file group with writer-preferring shared-read/exclusive-write
  admission. Existing readers may finish together; a waiting writer blocks later readers; the writer covers only the
  physical file commit. Different groups proceed independently. One region group includes an MCA header and all MCC
  sidecars it addresses; level, player, canonical saved-data, statistics, and advancements groups follow their complete
  commit and recovery path sets.
- Region and metadata entries are active-operation pins, not idle caches. Remove the coordinator and close an opened
  region handle after its final user, including a user still encoding or decoding outside physical file access. With
  `syncWrites = false`, that final release performs the automatic durable flush and close; with `syncWrites = true`,
  each region commit also flushes. Do not restore an LRU or an idle per-file lock map.
- `RegionFileStore` is an uncoordinated byte-level primitive. Direct callers and separate store instances own all
  read/write/close exclusion. `WorldRegionStore` and `MinecraftWorldAccess` provide coordination only within their own
  registry.
- `LiveMinecraftWorldReader` is a simple bypass observer for a world owned by another process. It takes no
  `session.lock`, creates no logical-file coordinator or per-file registry, and retains no region handle between calls.
  Every metadata, MCA, and MCC read proceeds independently, including concurrent reads of the same file. It has no
  mutable lifecycle and requires no `close()`. Live handles must permit the official server's concurrent write, delete,
  and replacement operations. The reader never repairs or mutates files, and stale or torn input and parse failures are
  expected.
- Coordination never chooses a dispatcher or owns a thread pool. Blocking filesystem operations and NBT/compression work
  stay synchronously on the calling thread; keep bookkeeping mutex sections free of I/O, codec work, file-access waits,
  and resource close.
- Establish `session.lock` behavior from the matching official server's `DirectoryLock` first and the repository Java
  major's OpenJDK `FileChannel` implementation second. The JVM path mirrors the official control flow and exception
  ordering; Native and Node reproduce the same observable open, marker-write, force, non-blocking whole-file lock, and
  cleanup semantics. Confirmed in-process overlap, non-blocking lock refusal, and marker-write lock violation are
  exposed uniformly as `WorldLockException`; unrelated open, permission, and device I/O failures retain their Okio I/O
  type.
- Node durable writes use the host `fsync` primitive, and its `session.lock` uses the pinned native addon to request the
  same non-blocking whole-file exclusive OS lock as the official JVM implementation.

## Tests

Portable coordination state tests belong in `commonTest`. Cover shared readers, every read/write ordering, writer
preference, failure, cancellation, and admission cleanup with `runTest` and explicit coroutine signals. JVM filesystem
race tests use controlled gates at exact source, sink, handle, codec, and close operations; do not use delays, repeated
stress, or scheduler luck as concurrency evidence. Okio `FakeFileSystem` is not JVM-thread-safe, so a concurrent oracle
must protect its model or use a purpose-built filesystem while observing admission before that protection.

Live-reader tests separately gate level, player, saved-data, statistics, advancements, MCA, and MCC reads. Prove that
same-file reads reach I/O together, that a slow read never delays an external writer, and that repeated missing-file
observations retain neither handles nor entries.

Filesystem behavior expressible through Okio or its fake filesystem belongs in `commonTest`. Keep the shared
official-server test's Host-filesystem namespace restriction explicit in its KDoc. Each execution uses one public store
to write marked GZIP, ZLIB, NONE, and LZ4 chunks through per-write selections and requires the matching official server
to load, save, restart, and reload them. The runner and its annotated entry live in the unique `hostFilesystemTest`
capability source set. JVM, JS Node, and desktop Native test source sets depend on it directly and do not need
platform-specific source files; Android Host and every isolated/device/Wasm runtime inherit portable `commonTest`
coverage only. Its non-default official-server properties automatically select prepared runtime without the stopped
default world template; no workspace policy crosses the test API. A longer `runTest` timeout is allowed for this real
process path, but readiness remains event-driven. Other JVM-specific filesystem oracles belong in `jvmTest`.

Run `:world-io:jvmTest` first, then `:world-io:jsNodeTest` for Node filesystem changes.
