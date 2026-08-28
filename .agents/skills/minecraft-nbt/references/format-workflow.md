# NBT format workflow

## Inventory the logical algebra

Inspect the selected official implementation for every tag ID and payload type, numeric and array representation,
compound key/value behavior, list element rules, equality/copy behavior relevant to the public model, and the role of
the end tag. Establish whether a list is physically homogeneous, logically homogeneous, or uses an official wrapper to
preserve mixed logical values; do not infer this from an older release.

Keep `NbtDocument` root-name semantics separate from a raw tag value. Preserve the exact logical distinction the
official APIs expose for a compound document, an unnamed root, and an arbitrary root tag.

## Inventory binary entry points

Trace official `NbtIo` and nested reader/writer operations for:

- tag ID placement and root-name handling;
- byte order and scalar widths;
- modified-UTF length and decoding rules;
- list, byte-array, int-array, long-array, and compound lengths;
- any-root, unnamed-root, and document/compound-root entry points;
- nesting/accounting rules and end-of-input behavior.

Match the official method appropriate to each repository API. Do not make all root modes aliases just because their
payloads can coincide for one sample.

## Validate intrinsic structure without policy ceilings

Test negative lengths, unknown tag IDs, missing end tags, truncated scalars and UTF, invalid root types, trailing bytes
where the API requires complete consumption, and lengths that exceed their binary representation. Do not impose a
project policy ceiling on otherwise representable bytes, collections, arrays, or nesting.

Those intrinsic binary failures use `NbtBinaryFormatException`. A valid NBT value that does not match a selected
serializer—missing or unknown fields, tag/Kotlin type mismatch, enum mismatch, or unsupported descriptor shape—uses
`NbtDecodingException` but not its binary-format subtype.

## Use official interoperability

The standard `nbt-serialization` JVM suite passes Kotlin-produced bytes through the matching official `NbtIo` root
method and compares official re-encoding or expected rejection. Add fixtures for every changed root mode and logical
edge. Keep focused Kotlin tests for API semantics the byte oracle cannot observe.

The `runNbt` entry in
`minecraft-test-fixture-host/src/main/resources/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java` is a
handwritten adapter to official `NbtIo`, compiled by the existing root oracle producer. If selected-release `NbtIo`,
tag, or accounting APIs make that bridge fail to compile or load, update only the adapter calls; never copy the official
parser or project NBT implementation into the oracle. Verify the bridge with
`./gradlew prepareOfficialMinecraftCodecOracle` before rerunning `./gradlew :nbt-serialization:jvmTest`.

Compression is composition outside binary NBT: standalone `level.dat`-style GZIP handling belongs to `world-io`, while
compression codecs used by region chunks belong to `world-format`.
