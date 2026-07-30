# Agent development guide

This file is for coding agents. Humans should start with [README.md](README.md), which explains the project, public
usage, prerequisites, and Gradle commands. Read the closest module-level `AGENTS.md` before changing that module; its
rules extend this file.

## Work in the owning layer

- `compression`: portable raw DEFLATE shared by network and world formats.
- `nbt`: binary NBT stream representation.
- `protocol-model`: format-independent packet and shared value models.
- `protocol-serialization`: Minecraft wire encodings and the runtime packet registry.
- `protocol-vanilla-data`: committed typed data captured from the matching official server.
- `protocol-transport`: Ktor sockets, framing, compression, and encryption.
- `protocol-session`: typed dispatch and connection-state transitions.
- `protocol-auth`: offline identity, session services, and cryptographic abstractions.
- `protocol-client` / `protocol-server`: connection orchestration through Play; the server also projects a finite
  initial chunk/entity view.
- `world-format`: filesystem-independent Anvil containers, coordinates, compression, and chunk NBT composition.
- `world-io`: `kotlinx.io.files` paths and filesystem adapters.
- `protocol-specification`: checked-in target-dependent evidence.
- `buildSrc`: shared Gradle configuration and deterministic protocol/storage tooling.

Do not move physical byte encodings into models, network I/O into serialization, or filesystem behavior into
`world-format`. Gameplay, authoritative ticking worlds, persistence policy, and a general Minecraft server are outside
the library's scope.

## Evidence and modeling

Use the matching official server JAR as the primary behavioral authority. Use the revision-matched Minecraft Wiki for
descriptions and facts that official code does not expose, then exact-version MCProtocolLib and Minestom as tertiary
evidence. Resolve conflicts in favor of official behavior and keep changing evidence in `protocol-specification`, not in
guidance prose.

When the official `server.properties` inventory changes, update the version-bound compatibility decisions in
`protocol-specification/server-properties-compatibility.json`. The library does not parse that file format, but
protocol-visible or storage-visible choices must remain configurable through public APIs or explicit application
extension points.

For nullability, inspect official codecs, constructors, access paths, annotations, optionals, and sentinels first. Fall
back through Wiki, MCProtocolLib, and Minestom only when the preceding evidence is inconclusive. Keep unresolved values
nullable and annotate them with `@UnknownNullability`.

Write idiomatic Kotlin Multiplatform code:

- keep shared models free of buffers and I/O;
- represent logical variants with sealed types and logical serializers;
- put physical representation in `protocol-serialization`;
- use `kotlinx.io.Source`/`Sink` and, where supported, `kotlinx.io.files.FileSystem`;
- omit redundant `public`, and keep implementation helpers internal or private.

Match tests to actual platform capabilities. Exercise portable Web code under the Gradle-provisioned Node/D8 runtimes;
browser-runtime tests are not a repository gate. Keep in-memory protocol state, NBT, compression, Anvil
`ByteArray`/`Source` loading, and chunk composition portable, but do not invent browser filesystem or listening-server
support. Run `world-io` and production socket tests only on targets that expose the required filesystem or networking
primitives. Do not add browser-driver infrastructure unless a task explicitly requires browser-specific behavior.

## Development and verification

Inspect existing code and generated specification state before editing. Preserve unrelated user changes. Prefer focused
module tests while iterating, then run the canonical repository gate:

```shell
./gradlew test
```

This gate is intentionally layered: model/codec tests do not replace malformed-input, registry, transport, session,
socket, official-codec, official-server, official-client, world-storage, or multiplatform checks. See
[README.md](README.md#verification) for the human-facing command and scope description.

Gradle owns deterministic downloads, generated runtimes, reports, worlds, and test artifacts under `build/`. Checked-in
target evidence belongs under `protocol-specification/`. Agent-only notes, manual decompilation, and other scratch
belong under `temp/`. Preserve `.gitignore`.

## Optional agent skills

`.agents/skills` contains optional playbooks that help an agent perform release-wide protocol and storage work. They may
invoke the same Gradle commands a human would invoke, but they are not project inputs:

- project source, Gradle logic, tests, and runtime code must never read skill files or skill-generated scratch;
- Gradle tasks and their helper scripts must never read from or write to `temp/`;
- removing `.agents/skills` must not affect compilation, tests, publication, or runtime behavior.

Use the narrowest applicable skill for an update or exhaustive audit. Ordinary development remains fully defined by the
source tree, Gradle tasks, specification state, and README.
