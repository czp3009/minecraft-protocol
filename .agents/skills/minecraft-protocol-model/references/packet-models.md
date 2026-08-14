# Packet and shared-value modeling

## Resolve packet identity

Use the selected release's Gradle-produced `packets.json` for state, direction, protocol ID, and namespace-free
`@PacketInfo.officialName`. The report currently describes the official packet inventory, not payload fields. Allow only
the explicitly modeled legacy unframed server-list ping outside that inventory.

Locate the corresponding official packet class through the official protocol registration and `PacketType`, then inspect
its `STREAM_CODEC`, constructors, record components, fields, accessors, and handling paths. For clientbound data,
inspect the official server producer and client consumer; reverse the roles for serverbound data. Shared official codec
classes remain primary evidence.

For an exhaustive protocol-model audit, iterate every official report entry and every nested shared value it reaches.
KSP coverage proves identity inventory only; it is not permission to sample packet fields or assume an unchanged class
retained its previous shape.

## Preserve names and shapes

Choose property names in this order:

1. record component name;
2. consistent backing-field and constructor-parameter name;
3. consistent accessor and codec-binding name;
4. direction-specific producer/consumer usage when the class alone does not expose a stable name.

Require agreement among the available official forms. Keep the official property order. If official logical declaration
order and wire order differ, preserve the official logical model and use a logical or physical serializer at the owning
boundary rather than renaming or reordering fields silently.

Map official domain values to existing project types such as `Identifier`, `ByteString`, `GameProfile`, NBT tags, sealed
holders, and immutable collections when those types preserve the same semantics. Do not let a Java implementation
container dictate an inappropriate Kotlin API, but do not erase a meaningful discriminator, sentinel, bound, or absence
state.

## Resolve nullability and absence

Apply this sequence to every reference-like property:

1. inspect the nearest official package default, including JSpecify `@NullMarked` or `@NullUnmarked`;
2. inspect type-use annotations on the record component, field, canonical constructor parameter, accessor return, and
   relevant generic arguments;
3. inspect the codec for `readNullable`/`writeNullable`, optional codecs, Boolean presence flags, sentinels, or
   conditional omission;
4. inspect official producer and consumer paths for actual absent values;
5. consult revision-matched Wiki prose, then exact-version MCProtocolLib and Minestom only when official evidence is
   insufficient.

Distinguish these cases:

- official `@Nullable T` maps to a nullable Kotlin reference;
- non-null `Optional<T>` or an explicit presence codec may map to a nullable Kotlin value as the logical absence
  representation, but its nullability is known;
- a numeric or registry sentinel remains a distinct semantic choice and is not automatically converted to null;
- an unresolved reference remains nullable and carries `@UnknownNullability` on the property.

Never add `@UnknownNullability` merely because the wire value is optional. Document and report why each use remains
unresolved. Official behavior wins every conflict.

## Cover the contract

Test defaults and every conditional branch relevant to the changed declaration. A self-round-trip does not establish
official compatibility; rely on the serialization workflow's official codec oracle for wire-visible samples. Field names
and nullability still require source/bytecode inspection because an oracle cannot validate Kotlin API names.
