# world-io

## API and stream boundary

- Layers proceed from raw Okio files to format stores, Minecraft path/policy stores and mutable/live facades. Delegate
  semantic work downward once; reuse the current borrowed source and logical admission.
- Public filesystem types and I/O failures are Okio types. Use the official kotlinx-io/Okio adapters internally. A
  terminal lower-format call has no returned stream to adapt back, so its kotlinx-io `IOException` must pass through
  the reverse adapter before leaving this module; do not rely on JVM type aliases or construct a replacement error.
- Callback-bound `BufferedSource`/`BufferedSink` operations are the canonical byte path. Connect typed serializers
  directly, without staging a complete uncompressed byte array, string or NBT/JSON tree.
- `NbtFileStore`/`Utf8JsonFileStore` capture formats at construction. World configuration supplies `standaloneNbtFormat`
  and `standaloneJson` once; data-pack parsing keeps its independent `DataPackFormat`.
- Keep JSON callbacks and typed values on `readJson`/`writeJson`; tree variants use `*JsonElement`. Do not add JSON
  string helpers. NBT documents, compressed chunks and semantic chunks keep their distinct representation names.
- Directory owners accept absolute Chunk coordinates or a region/local pair. Region owners accept local or validated
  absolute positions. Public handles expose logical resources, not allocators, lock state or exact-file owners.
- Mutable resources have suspend `use`/`close`; live Region resources have synchronous member `use`/`close`. Keep live
  resources free of `AutoCloseable` so the standard extension cannot compete with their failure-combining `use`.
- Keep Chunk, Entity and POI read scopes distinct. Their `AnvilRegionReadScope` base shares raw reads; each semantic
  `readChunk` returns only its own kind. Bind terrain/Entity decoders on dimension views or batches only while all facts
  (including the terrain tick base) remain stable, and retain an
  unbound path for per-record codecs. POI's complete decoder context includes its caller-selected position.
- Each semantic write accepts its operation's encoder or complete context. Changing `LastUpdate` cannot be retained
  in a long-lived handle; tick-base changes likewise require a new codec binding. Adapt the borrowed decompressed stream
  directly to the codec. Compression receives only its registry, not a second NBT configuration; native kotlinx-io
  codec output stays on that stream until compression completes. Raw Okio callbacks use the boundary adapter.
- Anvil allocation may retain one final compressed payload when its size was unknown. Never also retain the complete
  uncompressed payload; a caller-supplied compressed length permits direct streaming.

## File families

- Keep `level.dat` on the world facade; UUID files under `players`; root/dimension saved data under the corresponding
  `data`; pack reads under `dataPacks`; region/entities/poi under the selected dimension. Arbitrary exact paths belong
  to `directFiles` and do not acquire semantic coordination.
- `DimensionId`/`SavedDataId` map only to the selected namespaced layout. Do not add historical root-region or
  `DIM-1`/`DIM1` selectors. Standard player and saved-data reads return `null` for missing files in every API form.
- Root saved data uses generic `data.read`/`write`; keep the README's ID/payload mapping discoverable. Dimension data
  retains the four conveniences for world border, Chunk tickets, raids and the Ender Dragon fight.
- Mutable/live read families keep matching names, parameter order, nullability and defaults. Only suspension, writes,
  policy and resource lifetime differ. Strong shortcuts delegate to the generic path under the same admission.
- Level/player NBT shares primary/previous streaming and synced replacement machinery. Level recovery may promote a
  usable previous file; failed best-effort promotion still returns that parsed value. Player recovery may preserve
  corrupt current evidence, never promotes/copies the previous file and returns `null` when both are unusable.
- Only filesystem, compression and intrinsic `NbtBinaryFormatException` failures invalidate a recovery candidate.
  Serializer/schema `NbtDecodingException` propagates without fallback, promotion or corrupt-copy mutation.
- Saved data uses synced direct writes; player JSON truncates its final path. Preserve those distinct policies.
- World pack inputs are immutable during reader use and acquire no pack lock/coordinator. No-argument selection reads
  `level.dat` once through its existing recovery path, loads only selected `file/...` packs and returns a detached
  `WorldDataPackLoadResult`. Higher layers complete core/built-in/loader packs.

## Mutable Region storage and lifetime

- `MinecraftWorldAccess` owns the system-filesystem `session.lock` until admitted work and resources drain.
  Injectable stores do not simulate a process lease; the lease itself is not an I/O mutex.
- Logical groups use writer-preferring shared-read/exclusive-write admission. Unrelated metadata and Chunk/Entity/POI
  directory-position keys progress independently. A waiting writer blocks later readers of its own group only.
- A `RegionHandle` pins one logical Region, opens lazily and acquires admission per operation. Overlapping handles and
  one-shot calls share at most one active `.mca` handle; final release flushes/closes and reports cleanup to its owner.
  Entries are active pins, not idle caches. Separate one-shot calls own separate lifetimes.
- Closing seals new work, drains admitted operations and releases inner/outer ownership in order. Keep I/O, codec work,
  filesystem waits and close outside mutex bookkeeping. Coordination chooses no dispatcher.
- Check cancellation at admission boundaries. Once synchronous physical commit starts, finish consistency and cleanup
  before rethrowing cancellation.
- Preserve the old allocation until the new record and complete header commit. Do not shrink or replace an MCA for a
  single-record update. `replaceRegion` stages a complete logical replacement under one exclusive admission/header
  commit; omitted slots clear, and sidecars do not gain cross-file atomicity.
- Compression/placement policy applies to new encodings. Raw writes preserve compressed payloads while the store owns
  timestamps and external markers. Region epoch-second timestamps remain independent of Chunk `LastUpdate` game time.
- Reads, existence checks and clears never create absent Regions. Writes may create them; clearing the last record
  leaves a valid empty MCA.

## Live observation

- `LiveMinecraftWorldAccess` has no lease, repair, mutation, logical coordinator or world close lifecycle.
- Every live Region handle independently retains the MCA found at creation; handles share no registry, file object or
  reference count. MCC sidecars are per-record resources. A handle opened on a missing path remains empty.
- Ordinary calls reread the header; `withReadScope` reuses it only within that callback. Neither promises freshness,
  atomicity or header/payload agreement under external writes. Propagate stale/torn/missing-input failures.
- Calls may run concurrently, but close does not coordinate with them: callers finish operations/callbacks before close.

## Verification

Run `:world-io:jvmTest`; filesystem/adapter changes also need JS Node and applicable host Native tests. The official
world scenario lives in `hostFilesystemTest` and stops its server before same-host filesystem access.

For public API changes, inspect the mutable/live facade, child and Region families side by side. Compile ordinary
built-in, explicit-strategy, reified, tree and callback calls across affected targets. Assert missing-file behavior,
serializer-module lookup and stream ownership. Coordination tests use filesystem gates and physical open/close counts;
recovery tests distinguish binary corruption from schema mismatch and inject commit/cleanup failures.
