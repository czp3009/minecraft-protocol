# Data-pack format workflow

## Audit the official inputs

Inspect the selected release's core and built-in packs, representative world directory and ZIP packs, pack metadata, and
the official resource-stack consumer. Establish:

- pack IDs and low-to-high priority order;
- metadata format ranges, overlays, filters, and requested feature flags;
- normalized resource paths and supported file content stages;
- tag replacement and merge behavior;
- which rules are intrinsic parsing/stack behavior versus repository discovery or server-start selection policy.

Do not silently turn persisted selection into a simulation of a future official server start. Discovery of unlisted
packs requires repository-source metadata and is separate from completing the IDs a caller selected.

## Preserve representation stages

Keep these filesystem-independent stages distinct:

- `DataPackArchive`: complete raw file bytes;
- `DataPack`: parsed file content and metadata;
- `DataPackStack`: complete low-to-high pack order before resource merging;
- `ResolvedDataPackStack`: overlays, filters, replacement, and tags applied;
- `WorldDataPackLoadResult`: detached persisted configuration plus a partial set of already supplied packs.

Completing `WorldDataPackLoadResult` must retain enabled order, prefer an already loaded member, validate an externally
supplied member's ID, and report all unavailable IDs together. It must not retain a path, open resource, filesystem
reader, vanilla payload, or protocol value.

## Keep byte ownership explicit

Filesystem-independent stream formats use `kotlinx.io`. Complete archive and parsed-pack values may necessarily retain
file content, but do not create an additional complete byte copy or intermediate text/tree solely to cross an API
boundary. Keep lazy NBT decoding detached from the original container.

Use `world-io` for directory/ZIP inspection and borrowed Okio sources. Use `protocol-datapack` for Configuration
projection and `protocol-datapack-vanilla` for release-matched bundled inputs.

## Verify

Use `:world-format:jvmTest` for parsing, malformed metadata, overlay/filter/tag resolution, order, and partial-selection
completion. Run the applicable `world-io` tests when directory or ZIP behavior changes, and the vanilla-data tests when
bundled completion or protocol projection changes.
