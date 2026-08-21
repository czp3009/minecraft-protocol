# nbt-serialization

This module owns the physical Java Edition binary NBT and SNBT grammars plus the
`kotlinx.serialization` formats that map arbitrary supported serializers to the model in `nbt`.

## Invariants

- Named, any-tag, unnamed, and compound-document roots are explicit APIs.
- Binary strings use Java modified UTF, numeric payloads are big-endian, and mixed logical lists use the
  compound-wrapper convention from the selected official Minecraft release.
- The codec imposes no policy-sized depth, collection, primitive-array, string, or total-byte ceiling. Reject negative
  binary lengths and retain only bounds encoded by NBT itself, notably the unsigned-short modified-UTF byte length.
- Byte-array decoding rejects trailing input; stream decoding consumes exactly one value and never closes caller-owned
  `Source` or `Sink` instances.
- Binary serialization writes directly from serializer events to the caller's `Sink` and reads directly from the
  caller's `Source`; tree and byte-array APIs are optional adapters, not hidden staging in the stream path.
- Generic `NbtDocument` receiver extensions for serializer projection and binary output belong here so IDE completion
  can continue from the tree without making the logical `nbt` module depend on physical serialization.
- SNBT tag serialization traverses directly to a caller-owned `Sink`, and parsing incrementally decodes UTF-8 from a
  caller-owned `Source` without staging the complete text. A returned tag tree and the shared tree used by generic
  `kotlinx.serialization` conversion are the intentional retained forms.
- SNBT follows the selected release's heterogeneous lists, numeric literals, typed arrays, boolean and UUID operations,
  trailing separators, quoting, and escapes. It rejects values with no round-trippable SNBT representation. The portable
  default accepts numeric Unicode escapes; `\N{name}` requires a caller-supplied Unicode-name resolver because Kotlin
  Multiplatform has no common Unicode character-name database.
- NBT exceptions inherit `SerializationException`. Integrations propagate them unless they add genuinely distinct
  behavior; do not wrap them merely to rename the same serialization failure.
- Compression, filesystems, packets, Anvil containers, and sockets remain in their owning modules.
- The production API remains independently consumable with only `nbt`,
  `kotlinx-serialization-core`, and `kotlinx-io-core`; Fixture Host integration is test-only evidence.
- The official NBT/SNBT differential entry lives in `commonTest` under the `fixturetest` package; unsupported fixture
  tasks filter that entry while retaining ordinary format coverage.

## Verification

Run `:nbt-serialization:jvmTest`. A binary behavior change also requires
`:protocol-serialization:jvmTest`, `:world-format:jvmTest`, and
`:world-io:jvmTest`, followed by the official world reload scenario.
