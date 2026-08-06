# nbt-serialization

This module owns the physical Java Edition binary NBT grammar and the
`kotlinx.serialization` format that maps arbitrary supported serializers to the model in `nbt`.

## Invariants

- Named, any-tag, unnamed, and compound-document roots are explicit APIs.
- Binary strings use Java modified UTF, numeric payloads are big-endian, and Minecraft 26.2 mixed lists use the official
  compound-wrapper convention.
- Depth, collection, primitive-array, string, and total-byte limits are applied before untrusted allocation or work.
- Byte-array decoding rejects trailing input; stream decoding consumes exactly one value and never closes caller-owned
  `Source` or `Sink` instances.
- Binary serialization writes directly from serializer events to the caller's `Sink` and reads directly from the
  caller's `Source`; tree and byte-array APIs are optional adapters, not hidden staging in the stream path.
- NBT exceptions inherit `SerializationException`. Integrations propagate them unless they add genuinely distinct
  behavior; do not wrap them merely to rename the same serialization failure.
- Compression, filesystems, packets, Anvil containers, and sockets remain in their owning modules.
- The production API remains independently consumable with only `nbt`,
  `kotlinx-serialization-core`, and `kotlinx-io-core`; Fixture Host integration is test-only evidence.

## Verification

Run `:nbt-serialization:jvmTest`. A binary behavior change also requires
`:protocol-serialization:jvmTest`, `:world-format:jvmTest`, and
`:world-io:jvmTest`, followed by the official world reload scenario.
