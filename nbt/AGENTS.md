# nbt

This module owns named and unnamed binary NBT over `kotlinx.io.Source` and `Sink`. Packet integration and filesystem
adapters remain in their owning modules.

## Invariants

- Encoding matches the official named-root and Java modified-UTF behavior.
- Every NBT tag is preserved without imposing version-specific chunk semantics.
- Depth, allocation, string, and byte limits are applied before untrusted work.
- Byte-array decoding rejects malformed and trailing input; stream decoding consumes exactly one value.

## Verification

Run `:nbt:jvmTest`. A binary behavior change also requires `:protocol-serialization:jvmTest`,
`:world-format:jvmTest`, and `:world-io:jvmTest`.
