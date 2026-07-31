# World storage rules

## Module boundaries

- `compression` owns portable raw DEFLATE shared by network zlib and world-storage zlib/gzip wrappers.
- `protocol-model` owns the format-neutral NBT value algebra because packet models also use it.
- `nbt` owns binary NBT streams, named and unnamed roots, modified UTF, safety limits, and byte-array conveniences. Its
  primary API is
  `kotlinx.io.Source` and `kotlinx.io.Sink`.
- `world-format` owns filesystem-independent Anvil coordinates, header and sector layout, compression dispatch and
  wrapper checksums, external-chunk representation, and NBT composition.
- `world-io` owns `kotlinx.io.files.FileSystem` and `SystemFileSystem`
  adapters, world paths, atomic file replacement, and region-directory operations.

Browser-like targets consume `nbt` and `world-format` through streams. Publish
`world-io` only for targets where this project supports an actual filesystem. Do not add Okio or a JVM-only filesystem
abstraction to common production code.

## Data model

Preserve unknown NBT keys and compounds losslessly. Do not require a version-specific semantic chunk class merely to
read, copy, inspect, or rewrite a world. Add typed semantic views only in a separate layer when a consumer needs them.

Keep container parsing separate from decompression and NBT decoding. A caller must be able to inspect locations, copy
compressed chunks, or resolve external chunks without inflating a whole region.

Represent external chunk storage explicitly. Parsing an `.mca` stream may produce an unresolved external payload;
filesystem code resolves the matching sidecar before NBT decoding or re-encoding.

## Binary behavior

Derive all tag IDs, string rules, root naming, recursion accounting, region header fields, sector arithmetic,
stream-version flags, external thresholds, compression registrations, checksums, and directory layouts from the official
JAR for the selected release first. Then use the selected Wiki as descriptive evidence.

NBT strings must match the official Java binary behavior, including its modified-UTF representation. Region LZ4 must
match the exact legacy block stream used by the official dependency rather than assuming the standard LZ4 frame format.

Reject negative lengths, arithmetic overflow, truncated records, invalid tag or compression IDs, overlapping
allocations, invalid external stubs, checksum failures, decompression bombs, trailing bytes in byte-array conveniences,
and configured depth/size-limit violations.

Derive the target's behavior for an absent region file, a zero-byte region file, a header-only region, and a truncated
non-empty header separately. Keep their tests distinct; filesystem interoperability must not silently skip an on-disk
container merely because it contains no chunks.

Use immutable public values and content equality for primitive arrays. Keep codec machinery private or internal. Do not
widen production visibility for tests.

## Writes

Stream encoders leave caller-owned sources and sinks open. Filesystem helpers close resources they open.

File replacement uses the supplied `FileSystem`, writes a sibling temporary file, and atomically moves it over the
destination. Region updates write new external sidecars before committing the region header and remove obsolete sidecars
after the region commit.

The public timestamp input remains explicit. Do not hide wall-clock access inside the structural format layer.
