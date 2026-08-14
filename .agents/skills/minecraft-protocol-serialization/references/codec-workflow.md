# Physical packet codec workflow

## Inventory the official bytes

Trace the packet from its registered `ProtocolInfo` or equivalent protocol binding to its exact `StreamCodec`. Inspect
nested codecs until every byte has an owner. Record:

- field wire order, primitive width, signedness, endianness, and VarInt/VarLong use;
- Boolean strictness, enum or registry ID mapping, flags, and discriminators;
- string character and encoded-byte limits;
- array, collection, map, and remaining-payload length prefixes and bounds;
- nullable, optional, conditional, and sentinel representation;
- registry-aware holders and connection-supplied context;
- NBT root mode and optional/sentinel wrapper;
- chunk section count, palette thresholds, and global registry sizes;
- validation performed before allocation or construction.

Do not infer one field's encoding from its Kotlin type or from a similarly named packet. Follow the selected official
codec all the way to primitive operations.

For an exhaustive serialization audit, pair every runtime packet-registry entry and handwritten discriminator family
with its selected-release official codec. Do not limit inspection to compiler failures or the samples already present in
the oracle fixture set.

## Map into the serialization architecture

`MinecraftProtocolFormat` interprets descriptors and annotations from `protocol-model`. Use ordinary generated
`kotlinx.serialization` structure when descriptor order plus existing annotations matches the wire. Use a custom logical
`KSerializer` in the model for a sealed or conditional logical shape that remains independent of bytes. Extend the
physical format only when the wire representation itself requires special behavior.

Keep NBT packet wrappers distinct from binary NBT semantics. Delegate the latter to `nbt-serialization`. Keep packet IDs
outside payload encoding and keep outer length framing, zlib envelopes, AES/CFB8, and network channels outside this
module.

Audit contextual defaults such as block-state and biome registry sizes against the selected vanilla data. Prefer
connection-synchronized context where the protocol supplies it; do not let a stale default silently establish release
compatibility.

## Design verification samples

For each changed codec, include:

1. an ordinary exact-byte sample;
2. every discriminator and conditional presence branch;
3. empty, minimum, maximum, and threshold-adjacent values where applicable;
4. truncated and trailing data;
5. invalid length, ID, enum, sentinel, or allocation-amplifying input.

The official codec oracle prepends the packet ID, decodes through the matching vanilla runtime, requires complete
consumption, and re-encodes the result. A pass proves compatibility only for the supplied samples. Ensure changed
branches enter the oracle fixture set; a minimal generated sample and a local self-round-trip are insufficient by
themselves.

When official decode normalizes bytes, require the normalized result to pass through the official codec again and assert
semantic expectations in focused Kotlin tests.

## Maintain the official oracle boundary

The handwritten bridge is
`minecraft-test-fixture-host/src/main/resources/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java`. The root
`compileOfficialCodecOracle` producer compiles it against the extracted selected server runtime, and
`prepareOfficialMinecraftCodecOracle` is its actionless preparation gate.

Distinguish a bridge compile/load failure from an official rejection of a Kotlin fixture. When selected-release official
imports, protocol bindings, registry bootstrap, or codec APIs change, update only the bridge needed to invoke the new
official API. Keep the oracle independent of project codec code; do not reproduce vanilla encoding in the bridge, weaken
full-consumption/re-encoding checks, or turn it into another generator.

After a bridge change, run `./gradlew prepareOfficialMinecraftCodecOracle` and then
`./gradlew :protocol-serialization:jvmTest`. Standard consuming tests remain the compatibility gate available to both
humans and agents.
