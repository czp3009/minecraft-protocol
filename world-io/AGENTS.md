# world-io

This module owns Okio-based paths, mutable world leases, live read-only access, and filesystem-backed stores. It targets
configured filesystem runtimes only; browser and Wasm do not receive partial implementations.

## API layers

- Common production code uses Okio, never Java or platform filesystem APIs. Cross to `kotlinx.io.Source`/`Sink` only
  with `kotlinx-io-okio` adapters.
- Public callbacks use `kotlinx.io` streams for NBT, JSON, Region, and Chunk content. Detached-value and serializer
  helpers delegate to the same streaming path.
- Use precise complete-value names: NBT `*Document`, UTF-8 JSON `*Text`, compressed values `*CompressedChunk`, and
  selected-release semantic values `*Chunk`.
- Keep coordinate overloads symmetric. World/directory owners accept an absolute `ChunkPosition` or a region/local pair;
  region-bound owners accept local or validated absolute positions.
- Public APIs expose logical stores and region handles, not exact-file owners, allocators, lock state, sidecar grouping,
  or lifecycle internals.
- Mutable resources provide suspend `use` around suspend `close`; live Region resources provide synchronous `use`
  around synchronous `close`. Borrowed streams and multi-operation scopes remain callback-bound so resources and
  admission cannot escape.
- Live Region resources intentionally do not implement `AutoCloseable`: their member `use` preserves project failure
  combination and must remain the single Kotlin completion entry instead of competing with the standard extension.
- Do not add policy-sized read, write, decompression, tree-depth, allocation, pack-file, or file-count limits.
  Complete-value helpers may retain their documented value; streaming paths avoid unnecessary duplication.

## Storage behavior

- Standalone NBT stores compose `nbt-serialization` with filesystem and replacement policy. Typed, document, text, and
  raw operations for one logical file share its coordinator.
- Level/player NBT uses sibling temporary files and backups; saved data uses a synced direct write; player JSON
  truncates and writes its final path. Preserve these distinct policies.
- Region writes reserve the old allocation until the new record and complete header are committed. Do not shrink or
  replace an existing MCA for a single-chunk update.
- `replaceRegion` stages one complete logical replacement under exclusive admission and commits one header; omitted
  positions are cleared. It is not repeated public single-chunk writes and does not promise cross-file atomicity with
  MCC sidecars.
- Compression choice and internal/external placement apply only to newly encoded chunks. Raw writes accept
  already-compressed built-in or registered CUSTOM payloads; callers do not control timestamps or external markers.
- Reads, existence checks, and clears do not create missing region directories or files. A write may create them;
  clearing an existing final chunk leaves a valid empty MCA.
- `WorldDataPackReader` treats enabled directory/ZIP data packs as immutable inputs for the lifetime of their use. It
  takes no data-pack read lock or mutation coordinator; `session.lock` remains a property of the mutable world lease.
- Region and metadata entries are active-operation pins, not idle caches. Final release flushes/closes and reports
  cleanup failure to the operation that owns it.

## Concurrency and lifetime

- `MinecraftWorldAccess` holds the system-filesystem `session.lock` lease until admitted operations and owned resources
  drain. The lock is not a world-wide I/O mutex, and injectable stores do not simulate a cross-process lease.
- Logical file groups use writer-preferring shared-read/exclusive-write admission. Existing readers may finish together;
  a waiting writer blocks later readers; unrelated groups proceed independently.
- `RegionHandle` pins one logical region, opens it lazily, and acquires admission per operation. Concurrent reads and
  serialized same-region writes are legal. Closing seals new calls, waits for admitted calls, then releases inner and
  outer ownership in order.
- Cancellation is checked at admission boundaries, not used to interrupt synchronous Okio work. Once a physical commit
  starts, finish its consistency and cleanup transitions before rethrowing cancellation.
- Coordination owns no dispatcher or thread pool. Keep mutex bookkeeping free of I/O, codec work, file-access waits, and
  resource close.

## Live read-only access

- `LiveMinecraftWorldAccess` observes a world owned by another process. It takes no `session.lock`, performs no repair
  or mutation, creates no logical-file coordinator, and owns no close lifecycle.
- `openRegion` and `openEntityRegion` return caller-owned resources. Each handle independently opens and retains the
  `.mca` file found at creation and closes it synchronously; handles share no registry, reference count, file object, or
  lifecycle state. External `.mcc` sidecars remain per-Chunk resources.
- Ordinary handle operations reread the Region header. `withReadScope` caches one header read only for its callback;
  neither path promises freshness, atomicity, or agreement between the header and subsequently read payload bytes.
  Stale, torn, replaced, overwritten, or missing input and the resulting read failures are expected live outcomes.
- Live Region calls may run concurrently, but close does not coordinate with them and starts only after their callbacks
  and concurrent operations return.
- Mutable and live entry points each carry immutable format configuration. Live configuration contains read formats
  only, not write storage policy.

## Tests

- The official world interoperability runner and annotated entry live only in `hostFilesystemTest`. They stop the remote
  server before using the documented same-host working-directory path.
- Run `:world-io:jvmTest` first and `:world-io:jsNodeTest` for Node filesystem changes.
