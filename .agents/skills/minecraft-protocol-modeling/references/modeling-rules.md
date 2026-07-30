# Modeling and serialization rules

## Layer ownership

- `protocol-model`: format-neutral immutable payloads, shared values, wire annotations, and logical serializers.
- `nbt`: binary NBT grammar and stream codec.
- `protocol-serialization`: Minecraft physical encodings and runtime packet registry.
- `protocol-vanilla-data`: generated version-matched static and synchronized protocol data.
- `compression`: portable raw DEFLATE.
- `protocol-transport`: sockets, framing, compression envelope, and encryption.
- `protocol-session`: typed dispatch and state transitions.
- `protocol-auth`: identities, session service, hashes, and cryptographic abstraction.
- `protocol-client` / `protocol-server`: connection orchestration, not gameplay.

Keep generated files in functional packages but under `build/generated`. Do not commit generated source or use a package
named `generated`.

## Models

- Use idiomatic Kotlin, immutable values, sealed variants, semantic types, and generated serializers by default.
- Every packet has exact `@PacketInfo` state, direction, ID, and official report name.
- Keep models free of buffers, socket/filesystem behavior, and business logic.
- Put intrinsic invariants in value constructors and hostile-input/context limits in physical codecs.
- Preserve unknown/extensible values where the protocol permits them.
- Use content-equality wrappers for primitive arrays exposed as values.
- Represent Boolean presence with nullable values only when that is the actual wire rule; use logical serializers or
  sealed variants for sentinel/discriminator-dependent shapes.
- Keep codec machinery internal/private and omit redundant `public`.

## Nullability

Decide every changed constructor property's nullability in this order: official codec/constructor/access paths and
annotations; Wiki Optional/condition prose; exact-version MCProtocolLib; exact-version Minestom. Lower evidence cannot
override clear higher evidence. If still unresolved, keep the value nullable and add `@UnknownNullability`.

Do not confuse a conditionally present field with a nullable value inside an always-present field. Add tests for the
chosen physical representation. No hand-maintained nullability ledger exists.

## Physical format

- Big-endian fixed numerics; bounded VarInt/VarLong; VarInt collection counts.
- UTF-8 strings use VarInt byte length and official limits.
- Ordinary nullable values use Boolean then value.
- UUID is two Longs; position is signed 26/12/26.
- Protocol text components use unnamed network NBT where official codecs do.
- Decode the entire packet payload unless a field is explicitly remaining bytes.
- Bound allocations, recursion, compression output, frame sizes, and all untrusted lengths before allocation.

Do not flatten command graphs, data components, entity metadata, chunks/palettes/light, particles, sound holders,
recipes/slot displays, player-info actions, scoreboard unions, map optionals, advancements, or signed chat into
unexplained bytes. Raw bytes are appropriate only for protocol-defined opaque channel payloads.

## Evidence and tests

Matching an ID/name is insufficient. Compare field order, conditions, discriminators, primitive encodings, collection
and optional shapes, and limits against official codecs. Add model invariants, codec branches/golden bytes, malformed
input, registry-wide round trips, transport/session tests, and official differentials at the layers affected.

The official codec test executes the matching JAR directly. Never copy an official codec implementation into project
source merely to test against it.
