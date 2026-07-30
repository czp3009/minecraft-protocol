# Modeling and serialization rules

## Protocol and source authority

- The checked-in target is the version in `wiki-protocol-snapshot.json`.
- A default skill invocation updates that snapshot from the Wiki's current stable `Java Edition protocol/Packets` page.
- Never mix packet definitions from multiple protocol revisions.
- Use evidence in this order:
    1. matching official server JAR;
    2. revision-pinned Minecraft Wiki;
    3. matching MCProtocolLib;
    4. matching Minestom.
- Start with vanilla reports for structured facts and decompiled packet/codec sources for control flow. The matching
  official JAR is the primary source and final behavioral authority.
- Use the Wiki second for descriptions, names, Notes, and details that official code does not expose directly.
- Compare every finished class and its actual wire behavior with the official JAR.
- Record every source disagreement. Never silently choose an auxiliary implementation.

## Module boundaries

- `protocol-model` owns format-neutral packet/nested models, wire annotations, and logical custom serializers.
- `nbt` owns the shared binary NBT grammar and stream codec.
- `protocol-serialization` owns the Minecraft binary format, physical wire rules, packet registries, payload helpers,
  and the unnamed packet-NBT adapter. It does not duplicate the NBT grammar.
- `protocol-vanilla-data` owns committed, typed, version-matched protocol data captured from the official server,
  including Configuration registries, Known Packs, feature flags, and tags. It is protocol data, not gameplay, world
  generation, entity behavior, or a general Datapack implementation. Preserve registry-packet and registry-entry order
  because entry position defines runtime numeric IDs. For reproducible committed snapshots, canonicalize only
  order-insensitive structures such as NBT compound keys, tag-registry/tag ordering, and tag membership sets. Require
  every raw captured packet to round-trip byte-for-byte before canonicalization.
- `compression` owns portable raw DEFLATE shared with world storage.
- `protocol-transport` owns Ktor socket exposure, framing, the Minecraft zlib wrapper, compression thresholds, and
  stream encryption. It stops at packet-data bytes.
- `protocol-session` owns typed packet dispatch, direction validation, and connection-state transitions.
- `protocol-auth` owns offline identity, server hashes, session-service calls, and portable cryptographic abstractions.
- `protocol-client` and `protocol-server` own their respective connection orchestration APIs. The server returns control
  after Play entry and does not contain a gameplay loop.
- Keep handwritten or generated code in functional packages such as `model`; never use a package named `generated`.
- Do not change source sets merely to distinguish generated code.
- Keep vanilla data, compression, encryption, TCP, state transitions, authentication, and connection orchestration
  outside payload serializers.
- Keep application gameplay, persistence, worlds, entities, and player behavior outside every protocol module.

## Model rules

- Treat official Java code as wire-behavior evidence, never as a Kotlin class template. Preserve bytes and protocol
  semantics while using idiomatic Kotlin structure, naming, immutable values, sealed hierarchies, nullability, and
  companion objects; do not produce a line-by-line Java translation.
- Make network models immutable and `@Serializable`. Use Kotlin's default public visibility without spelling the
  redundant `public` modifier. Keep only types and members that callers must construct or inspect public; mark
  serializers, descriptor helpers, dispatch tables, constants, validators, and other codec machinery `internal` or
  `private` as narrowly as possible. Never widen production visibility merely to make a test convenient.
- Keep models free of buffer reads/writes and business logic.
- Give every packet `@PacketInfo` with exact state, direction, ID, and vanilla
  `officialName`; this identity is required to detect ID shifts.
- Keep declarative protocol facts adjacent to their model with `@SerialInfo`
  annotations (and type-level metadata annotations where appropriate) instead of duplicating them in distant codec
  tables. Leave only genuinely dynamic presence and discriminator decisions in custom serializers.
- Prefer a type's companion object for type-specific factories, safe constructors, lookup functions, and constants
  instead of unrelated public top-level declarations.
- Prefer semantic types such as `Identifier`, `Uuid`, `BlockPosition`,
  `ByteString`, and `TextComponent`.
- Preserve unknown/extensible protocol values instead of crashing through a closed enum when extensions are permitted.
- Use content-equality wrappers for primitive arrays exposed as values.
- Use a sealed hierarchy for discriminated conditional payloads.
- Preserve official codec field order in serial descriptors. Use Wiki names and descriptions where they are consistent
  with official behavior.
- Validate intrinsic value invariants in the value type; enforce packet-context limits in serializers/the format.
- Distinguish:
    - Boolean-prefixed presence: nullable property;
    - presence controlled by another value: sealed/custom serializer;
    - zero-based or ID+1 optionals: dedicated serializer/value type;
    - remaining payload bytes: `@RemainingBytes`;
    - prefixed bytes: normal `ByteString`;
    - fixed bytes: `@FixedLength`.

## Nullability evidence

Audit nullability as a separate concern for **every constructor property**, including properties currently modeled as
non-null. Record every available source for cross-checking, but determine the result in this exact priority:

1. inspect the matching official JAR codec, constructor/access paths, JSpecify/`@Nullable` annotations, and sentinel
   handling;
2. when official evidence is inconclusive, decide from the Wiki field row, Notes, and any Optional or
   conditional-presence statement;
3. when both remain inconclusive, decide from exact-version MCProtocolLib nullability annotations and usage;
4. when all earlier sources remain inconclusive, decide from exact-version Minestom nullability annotations and usage.

Lower-priority findings remain useful corroborating or contradictory evidence, but cannot override a decisive
higher-priority source.

Do not confuse a conditionally present wire field with a value that is nullable inside an always-present field. If all
four sources still leave nullability undetermined, model the Kotlin property as nullable and annotate it with
`@UnknownNullability`. That annotation records missing evidence only; it never chooses a Boolean prefix or any other
wire representation. Keep the actual presence/sentinel encoding next to the model through the appropriate protocol
annotation or logical serializer. Record every `@UnknownNullability` field in the conformance ledger so a later update
can resolve and remove it.

`checkProtocolNullability` must validate two layers:

- an all-property source signature proving that every current constructor property, including non-null properties,
  belongs to a reviewed source batch;
- ordered four-source evidence for every nullable or unresolved property.

Canonicalize the global all-property signature by stable property ID so filesystem traversal and case-sensitive file
ordering cannot invalidate otherwise identical evidence. Keep each source-batch signature scoped to that source.

Never treat “the Kotlin source already says non-null” as evidence. A source signature is only a stale-change gate; the
language-model review that approves it must inspect the official codec first and the Wiki when official evidence is
inconclusive before recording `confirmed`. Clear official evidence cannot be overridden by a lower-priority source.

## kotlinx.serialization rules

- Generated serializers are the default for unconditional sequential fields.
- `@SerialInfo` annotations describe Minecraft physical encodings:
  VarInt/VarLong, unsigned widths, limits, fixed/remaining bytes, and element encodings.
- Logical custom serializers must be format-independent:
    - use only public kotlinx.serialization interfaces;
    - expose truthful descriptors;
    - emit discriminators before dependent fields;
    - implement sequential and indexed decoding;
    - do not cast to or depend on the concrete Minecraft format implementation.
- The models target Minecraft protocol serialization. Do not scatter or duplicate codec logic merely to manufacture a
  particular JSON shape; a future non-Minecraft format may deliberately honor or ignore the protocol annotations
  according to its own contract.
- Expose the stateless default `MinecraftFormat` as its named `Default`
  companion object. Create connection- or application-specific instances through the companion factory; do not
  reintroduce a top-level singleton alias.
- Never cast from model code to `MinecraftEncoder`, `MinecraftDecoder`, or
  `MinecraftFormat`.
- Never use default sealed-polymorphic wire encoding for Minecraft unions.
- Put physical byte representation in `MinecraftFormat`; use model serializers only for logical shape and field
  presence.
- Do not apply a `KSerializer<T?>` directly to a nullable property. The generated serializer adds its own nullable
  wrapper around field serializers. Use a `KSerializer<T>` when the ordinary Boolean-prefixed nullable encoding is
  correct; use a class-level logical serializer when absence uses a sentinel or another field-dependent representation.

## Minecraft binary-format rules

- Non-VarInt/VarLong numerics are big-endian.
- String is VarInt UTF-8 byte length plus UTF-8, enforcing the limits in the official codec; use the Wiki as secondary
  descriptive evidence.
- Ordinary collections use VarInt element counts.
- Ordinary nullable values use Boolean followed by the value when present.
- Enum ordinals use VarInt unless a field annotation/custom serializer says otherwise.
- Reject oversized VarInt/VarLong; non-minimal encodings follow configuration.
- Bound all untrusted allocations and recursion.
- Position is packed signed 26/12/26; UUID is most-significant Long then least-significant Long.
- Protocol text components use unnamed network NBT; login JSON text is distinct.
- Packet ID/state/direction selection belongs to a registry outside payload serializers.
- Decoding must consume the full payload unless a field is explicitly remaining bytes.

## Difficult protocol families

Do not flatten these into unexplained bytes:

- command graph nodes/parser properties;
- item stacks and data components;
- entity metadata dispatch/terminator;
- chunks, palettes, packed data, heightmaps, block entities, and light;
- particle type/value dispatch;
- sound ID-or-inline values;
- recipe and slot displays;
- player-info action sets;
- boss-bar actions;
- scoreboard objective/team action fields;
- map icon/color optionals;
- advancement/criteria maps;
- signed chat signatures/bit sets;
- custom payload remaining bytes.

Opaque bytes are acceptable only where the protocol itself defines a channel-specific or opaque payload.

For data components, enumerate the resolved official registry after vanilla bootstrap and inspect the effective stream
codec for every entry. The absence of an explicitly supplied network codec is not evidence that a component is absent
from the wire: vanilla may derive a fallback stream codec from its persistent codec. When that happens, model the
physical fallback value explicitly and verify it through both the finite-registry audit and the exact official codec
oracle.

## Per-class evidence

A class is complete only when all are present:

1. official class/source mapping from
   `protocol-specification/official-packet-classes.csv`;
2. strong comparison against the current official codec covering field order, conditional presence, discriminators,
   physical encodings, and limits rather than only identity or class shape;
3. exact Wiki section and field/condition review;
4. exact packet registry identity;
5. relevant MCProtocolLib/Minestom comparison when an exact version exists or a recorded unavailable/version-drift
   result;
6. model, format, packet, and test-transport layer tests as applicable;
7. golden bytes, generated/explicit branch samples, limits, and malformed input;
8. executable differential results from the matching official codec for every available sample;
9. machine-validated official conformance-ledger entry tied to the current Wiki revision, server SHA-1, official
   source/class SHA-256, and executable test evidence.

The ledger also carries one aggregate fingerprint over every Kotlin
`commonMain` source in both modules. Run
`refreshOfficialProtocolConformance` after any implementation change. Its pending verdicts are an LLM review queue, not
a value that a script may automatically promote to pass.
