# world-format

This module owns filesystem-independent Anvil coordinates, region headers and sectors, compression dispatch, external
chunk representation, and NBT composition.

## Invariants

- Container parsing remains separate from filesystem access, decompression, and NBT decoding.
- Region, compression, and chunk-NBT stream methods are canonical and never close caller-owned endpoints. In-memory
  methods wrap those paths; compressed byte arrays remain only where they are the value owned by `RegionChunk`.
- Region chunk composition delegates compound-document bytes to `nbt-serialization` and exposes NBT model values from
  `nbt`; it does not duplicate their grammar.
- Callers can inspect or repack a region without inflating preserved compressed payloads.
- Sector, version, compression, checksum, and external-chunk behavior match the selected official server.
- Parsing rejects overlaps, truncation, overflow, invalid versions, checksum failures, and decompression-limit
  violations.
- Custom compression stays injectable through the public registry; built-in machinery remains private.
- Format and compression I/O failures are `IOException` subtypes or propagate an existing lower-layer `IOException`; do
  not build redundant wrapper chains.

## Verification

Run `:world-format:jvmTest`. A region-wire change also requires `:world-io:jvmTest`.
