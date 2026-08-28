# world-io

This module owns Okio-based paths, mutable world leases, live read-only access, and filesystem-backed stores. It targets
configured filesystem runtimes only; browser and Wasm do not receive partial implementations.

## API layers

- Keep the vertical dependency direction explicit: raw Okio file stores feed format stores; format stores feed Minecraft
  path/policy stores; mutable and live world facades add lifecycle and, where applicable, coordination. Each layer
  delegates semantic work downward once; it must not depend upward, repeat parsing/encoding, reacquire the same logical
  admission, or reopen a file already borrowed by the current operation.
- Public filesystem paths, handles, callback streams, and I/O failures use Okio. Common production code never exposes
  Java, platform filesystem, or kotlinx-io I/O types. Cross to lower format modules only at internal boundaries with the
  official `kotlinx-io-okio` adapters; never hand-copy stream bytes or instantiate a replacement I/O exception. A
  terminal parser or serializer call has no returned stream to adapt back, so its failure-only boundary must route a
  kotlinx-io `IOException` through the official reverse adapter before it can leave `world-io`.
- Public callbacks use Okio `BufferedSource` and `BufferedSink` for raw, NBT, JSON, Region, and Chunk content. These
  callback methods are the canonical byte path. Detached-value and serializer helpers delegate to them; typed NBT and
  JSON connect the borrowed stream directly to the selected serializer without first assembling a complete byte array,
  string, NBT tree, or JSON tree.
- Use precise complete-value names: NBT `*Document`, UTF-8 JSON `*Text`, compressed values `*CompressedChunk`, and
  selected-release semantic values `*Chunk`.
- Keep coordinate overloads symmetric. World/directory owners accept an absolute `ChunkPosition` or a region/local pair;
  region-bound owners accept local or validated absolute positions.
- Public APIs expose logical stores and region handles, not exact-file owners, allocators, lock state, sidecar grouping,
  or lifecycle internals.
- Mutable resources provide suspend `use` around suspend `close`; live Region resources provide synchronous `use`
  around synchronous `close`. Borrowed streams and multi-operation scopes remain callback-bound so resources and
  admission cannot escape.
- Ordinary Chunk, Entity, and POI Region handles preserve their type distinction in `RegionReadScope`,
  `EntityRegionReadScope`, and `PoiRegionReadScope`. Their common Anvil, compression, and NBT reads come from
  `AnvilRegionReadScope`; semantic `readChunk` returns only the value appropriate to the handle that created the scope.
  POI has no caller-supplied registry context, so its handle owns the matching codec instead of exposing a redundant
  codec parameter.
- Do not preflight or reject a read by comparing persisted `DataVersion` with a library- or caller-selected version.
  Carry the field through semantic values; callers own any compatibility check or migration decision.
- Live Region resources intentionally do not implement `AutoCloseable`: their member `use` preserves project failure
  combination and must remain the single Kotlin completion entry instead of competing with the standard extension.
- Anvil allocation is the explicit write-side exception: when the producer does not already know the compressed length,
  Region encoding may retain the one final compressed payload needed to determine record length. It must not also stage
  a complete uncompressed payload. A caller that supplies `compressedByteCount` streams directly to the Region sink.

## Storage behavior

- Standalone NBT stores compose `nbt-serialization` with filesystem and replacement policy but own no coordinator. The
  mutable facade routes every typed, document, text, and raw semantic operation for one logical file through the same
  coordinator entry.
- The mutable and live facades keep corresponding read names and parameters aligned. The live side differs only by
  synchronous execution, absence of writes, and ownership of caller-closed Region resources. Strong convenience methods
  delegate once to the generic serializer/stream path; they do not acquire a second logical admission.
- Level/player NBT uses sibling temporary files and backups; saved data uses a synced direct write; player JSON
  truncates and writes its final path. Level and player stores delegate their common primary/previous streaming and
  replacement mechanism to one physical implementation; only their official recovery decisions differ. Preserve these
  distinct policies. A successfully parsed `level.dat_old` remains the read result even if its best-effort promotion has
  an I/O failure, matching the official ignored restoration result. An unusable current and previous player file
  produces the official empty result. Mutable access may preserve the official-style corrupt-current evidence
  best-effort, but does not promote or make an extra corrupt copy of the previous player file. Recovery candidates are
  rejected only for filesystem, compression, or intrinsic `NbtBinaryFormatException` failure; a serializer/schema
  mapping `NbtDecodingException` is a caller/program failure and must not mutate either file.
- Region writes reserve the old allocation until the new record and complete header are committed. Do not shrink or
  replace an existing MCA for a single-chunk update.
- `replaceRegion` stages one complete logical replacement under exclusive admission and commits one header; omitted
  positions are cleared. It is not repeated public single-chunk writes and does not promise cross-file atomicity with
  MCC sidecars.
- Compression choice and internal/external placement apply only to newly encoded chunks. Raw writes accept
  already-compressed built-in or registered CUSTOM payloads; callers do not control timestamps or external markers.
- Reads, existence checks, and clears do not create missing region directories or files. A write may create them;
  clearing an existing final chunk leaves a valid empty MCA.
- Stateless one-shot methods own one open/close lifetime per call. Separate caller operations such as reading metadata
  and then reading content may therefore open the same file twice; do not add hidden cross-call caching to prevent it.
  Within one semantic call, reuse its borrowed source instead of reopening solely for format detection or parsing.
- `WorldDataPackReader` treats enabled directory/ZIP data packs as immutable inputs for the lifetime of their use. It
  takes no data-pack read lock or mutation coordinator; `session.lock` remains a property of the mutable world lease.
  Both facades expose matching read-only operations. Their no-argument form reads `level.dat` once under its existing
  recovery/coordination path, then returns a detached `WorldDataPackLoadResult`; only `file/...` members are filesystem
  work here, while completing core, built-in, or loader members belongs to a higher layer.
- Region and metadata entries are active-operation pins, not idle caches. Final release flushes/closes and reports
  cleanup failure to the operation that owns it.

## Concurrency and lifetime

- `MinecraftWorldAccess` holds the system-filesystem `session.lock` lease until admitted operations and owned resources
  drain. The lock is not a world-wide I/O mutex, and injectable stores do not simulate a cross-process lease.
- Logical file groups use writer-preferring shared-read/exclusive-write admission. Existing readers may finish together;
  a waiting writer blocks later readers; unrelated groups proceed independently. Chunk, Entity, and POI Region
  directory/position identities must not serialize with one another or with an unrelated metadata key.
- `RegionHandle` pins one logical region, opens it lazily, and acquires admission per operation. Concurrent reads and
  serialized same-region writes are legal. Closing seals new calls, waits for admitted calls, then releases inner and
  outer ownership in order.
- One active mutable Region state owns at most one `.mca` handle. Reuse it across overlapping one-shot operations and
  caller-owned handles, and close it only after the final state pin is released; do not reopen between an admitted read
  and its queued write.
- Cancellation is checked at admission boundaries, not used to interrupt synchronous Okio work. Once a physical commit
  starts, finish its consistency and cleanup transitions before rethrowing cancellation.
- Coordination owns no dispatcher or thread pool. Keep mutex bookkeeping free of I/O, codec work, file-access waits, and
  resource close.

## Live read-only access

- `LiveMinecraftWorldAccess` observes a world owned by another process. It takes no `session.lock`, performs no repair
  or mutation, creates no logical-file coordinator, and owns no close lifecycle.
- `openRegion`, `openEntityRegion`, and `openPoiRegion` return caller-owned resources. Each handle independently opens
  and retains the `.mca` file found at creation and closes it synchronously; handles share no registry, reference count,
  file object, or lifecycle state. External `.mcc` sidecars remain per-Chunk resources.
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
