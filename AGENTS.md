# Repository guidance

## Scope and structure

- `nbt` owns the shared binary NBT stream format.
- `protocol-model` owns format-independent Minecraft Java Edition packet and shared value models.
- `protocol-serialization` owns the kotlinx.serialization binary format, physical wire encodings, and runtime packet
  registry.
- `protocol-vanilla-data` owns typed, committed static and Configuration data captured from the matching official
  server.
- `protocol-transport` owns Ktor socket exposure, framing, compression, and encryption.
- `protocol-session` owns typed packet dispatch and connection-state changes.
- `protocol-auth` owns offline identity, session services, and cryptographic abstractions.
- `protocol-client` and `protocol-server` own connection orchestration through Play entry; the server also owns finite
  initial chunk/entity projection.
- `world-format` owns filesystem-independent Anvil region containers, compression, coordinates, and chunk NBT
  composition.
- `world-io` owns `kotlinx.io.files` world paths and filesystem adapters.
- `protocol-specification` owns the checked-in, version-dependent target and evidence state.
- `.agents/skills` owns the indexed network, world-storage, and full-library closed-loop workflows.
- `buildSrc` owns shared Gradle configuration.

Guidance in a module-level `AGENTS.md` extends this file.

## Protocol authority

Use the matching official server JAR as the primary source and behavioral authority for protocol and storage behavior.
Use the Minecraft Wiki second for descriptions, names, and details that official code does not expose directly. Use
exact-version MCProtocolLib and then Minestom only as tertiary evidence. Resolve every disagreement in favor of official
behavior and record it in project specification state.

For nullability, inspect the matching official JAR first, including codecs, constructors, access paths, annotations,
optionals, and sentinels. If that evidence is inconclusive, consult the Wiki, then MCProtocolLib, then Minestom. Keep an
unresolved property nullable and annotate it with
`@UnknownNullability`.

Derive changing facts through the protocol refresh tasks. Keep Minecraft versions, protocol IDs, inventories, source
hashes, nullable counts, and test results out of agent guidance and skill prose.

## Kotlin design

- Write idiomatic Kotlin Multiplatform code.
- Keep models free of buffer access and network I/O.
- Express logical variants with sealed types, logical serializers, and model-associated annotations.
- Express physical byte representation in `protocol-serialization`.
- Use `kotlinx.io.Source` and `Sink` for portable binary formats, and
  `kotlinx.io.files.FileSystem` for modules that support files.
- Use the minimum practical visibility. Kotlin's default `public` visibility needs no keyword.
- Keep serializer and codec helpers internal or private when they are implementation details.
- Represent unresolved nullability with a nullable Kotlin type and
  `@UnknownNullability`.

## Verification

Protocol changes pass every relevant layer:

1. model contracts and invariants;
2. primitive and composite MinecraftFormat codecs;
3. packet branch, golden-payload, malformed-input, registry-wide tests, and finite-registry ID/name audits;
4. framing, compression, encryption, partial-I/O, and Ktor socket tests;
5. session, authentication, client/server orchestration, and initial-world projection tests;
6. differential execution through the matching official packet codecs;
7. offline-mode production-client interoperability with the matching official server;
8. offline-mode matching official-client interoperability with the production server, including initial chunks/entities
   and client acknowledgements.

`verifyProtocolUpdate` is the headless-CI aggregate protocol gate.
`verifyWorldStorageUpdate` is the deterministic NBT and world-storage gate.
`verifyMinecraftLibrary` composes both gates. The protocol gate prepares the matching official client and a
hash-verified headless launcher under `build/`; it does not require a display, launcher installation, account, or
machine-specific Minecraft path.
`officialClientToServerEndToEndTest` is the additional direct desktop-client acceptance test. Representative
multiplatform compilation remains part of final verification.

World-storage changes also pass binary format, malformed-input, compression differential, real filesystem, and official
generate/rewrite/reload tests.

## Workspace ownership

- Gradle tasks read and write transient artifacts under `build/`.
- Agent scratch, manual decompilation, and compaction notes belong under
  `temp/`.
- Checked-in version-dependent evidence belongs under
  `protocol-specification/`.
- The update workflow preserves unrelated user changes and leaves
  `.gitignore` unchanged.
