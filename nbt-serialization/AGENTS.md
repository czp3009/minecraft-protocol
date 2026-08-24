# nbt-serialization

This module owns binary NBT, SNBT, and their `kotlinx.serialization` formats.

## Local invariants

- Named, unnamed, any-tag, and compound-document roots are explicit contracts. Binary numeric payloads are big-endian,
  strings use Java modified UTF, and heterogeneous logical lists use the selected release's compound-wrapper
  representation.
- Reject invalid intrinsic lengths and the modified-UTF unsigned-short overflow, but do not add policy-sized depth,
  collection, array, string, or total-byte limits.
- `decodeFromByteArray` rejects trailing bytes. Stream APIs consume or write one value and never close caller-owned
  `Source` or `Sink` instances.
- Keep stream paths incremental. Tree and byte-array helpers adapt the stream/format contracts rather than becoming
  hidden staging layers.
- Generic `NbtDocument` conversion extensions live here so `nbt` remains independent of physical serialization.
- SNBT supports the selected release's literals, arrays, heterogeneous lists, booleans, UUIDs, separators, quotes, and
  escapes. Numeric Unicode escapes are portable; named Unicode escapes require a caller-supplied resolver.
- NBT format exceptions remain `SerializationException` values. Do not wrap them merely to rename the same failure.

## Verification

Run `:nbt-serialization:jvmTest`. Binary changes also require the affected packet and world-format suites.
