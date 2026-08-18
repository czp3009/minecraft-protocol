# world-io

This module owns world paths and filesystem adapters built on Okio. Kotlin/JS supports the Node runtime through its real
filesystem; browser and Wasm targets use the stream modules and do not receive a partial filesystem implementation.

## Invariants

- Common production code contains no Java or platform filesystem APIs.
- Convert between Okio sources or sinks and `kotlinx.io` only through `kotlinx-io-okio`; keep exact snapshot and
  declared byte counts, durable flushes, and ownership policy in this module instead of duplicating the library's
  transport adapters. At the stream boundary, retain the adapters' documented mapping into the wrapping library's I/O
  exception hierarchy.
- World-file APIs impose no policy-sized read, write, decompression, tree-depth, or allocation ceiling. Preserve only
  bounds intrinsic to the represented format and exact lengths needed for framing. Full `String`, JSON, NBT, region, and
  byte-array conveniences may retain their value in memory; also expose caller-owned streaming paths so large data need
  not be duplicated. Never add temporary files or extra filesystem passes solely to reduce memory pressure.
- Public filesystem/store entry points expose Okio I/O exceptions. Normalize only a downstream `kotlinx.io.IOException`
  left after an official adapter crossing, restore a preserved Okio cause when present, and leave format/NBT,
  argument/state, cancellation, and cleanup-rethrow failures in their owning semantic category.
- Standalone NBT stores compose `nbt-serialization` with compression and filesystem policy; byte grammar remains in the
  serialization module.
- Typed `level.dat`, advancement, and statistics operations accept caller-selected serializers and connect the file,
  compression decorator where applicable, and format stream API in one pass. Built-in models use the same generic path;
  tree/text/document conveniences are thin wrappers or direct complete-value operations and never stringify, reparse, or
  create a second complete representation.
- Typed, tree/document, text, and raw operations for one standalone file use the same logical-file coordinator and keep
  that file family's existing replacement policy. `LiveMinecraftWorldReader` provides the same typed reads without
  coordination, recovery, or mutation.
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
  physical file commit. This is not a fair/FIFO lock and promises no relative order among same-kind waiters. Different
  groups proceed independently. One region group includes an MCA header and all MCC sidecars it addresses; level,
  player, canonical saved-data, statistics, and advancements groups follow their complete commit and recovery path sets.
- Coroutine cancellation is an admission and waiting signal, not an interrupt inside synchronous Okio work. Check it
  after logical admission and before starting physical work. Once a synchronous standalone-file update or region
  record/header sequence starts, finish that sequence and every users/handle/barrier transition before rethrowing
  cancellation. State and resource cleanup runs with `NonCancellable`; when cancellation races an I/O or cleanup
  failure, keep cancellation primary and retain the other failure as suppressed. Never let a broad catch, `runCatching`,
  or standard `use` turn cancellation into an ordinary failure or skip cleanup.
- Region and metadata entries are active-operation pins, not idle caches. Remove the coordinator and close an opened
  region handle after its final user, including a user still encoding before exclusive file access or streaming and
  decoding under shared file access. With `syncWrites = false`, that final release performs the automatic durable flush
  and close; with `syncWrites = true`, each region commit also flushes. Do not restore an LRU or an idle per-file lock
  map.
- A region runtime path that needs both locks takes logical `fileAccess` before `openMutex`. Final cleanup may take
  `openMutex` alone only after bookkeeping atomically moves `users` to zero and sets `closing`: zero users excludes
  admitted paths, and `closing` redirects new acquisition to the completion signal. Never add an
  `openMutex`-to-`fileAccess` path.
- Final-entry cleanup is synchronous and reports failure to its last operation. Retain a cleanup failure for owner close
  only when close already sealed admission before cleanup finalized; this lets the active close barrier and its
  concurrent waiters report the failure without accumulating failures from completed earlier operations. Every caller of
  that same completed close barrier observes its finalized failure. Return a physical cleanup failure across a
  `NonCancellable` context and throw it only afterward so coroutine stack recovery cannot copy it or lose suppressed
  failures.
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

Cancellation tests gate before admission, inside a synchronous physical write/flush, and during owner close. Start a
competing same-file operation where applicable and prove it is registered but cannot cross the commit boundary before
releasing the gate. Assert the original or complete-new bytes can still be decoded, all entry/user/handle counts drain,
continuations after the cancelled call do not run, cleanup failures remain attached, and later close callers observe the
shared barrier result.

Streaming tests use small chunked or probing sources and sinks to prove incremental transfer and removal of policy
ceilings. Do not write hundreds of megabytes merely to cross a deleted limit. Real payload size is justified only when
testing an intrinsic format boundary such as Anvil's inline-to-external chunk transition.

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
