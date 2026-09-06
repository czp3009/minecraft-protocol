---
name: minecraft-world-format
description: "Implement or audit filesystem-independent world-format code: mutable Chunk/EntityChunk/PoiChunk graphs, properties, domain NBT codecs, standalone saved-file schemas, coordinates, data-pack parsing/stacks, Anvil containers and compression. Do not use for Okio file stores or network projection."
---

# Minecraft world format

## Choose the evidence path

Confirm the selected release with `./gradlew -q minecraftVersion`, then load the relevant workflow:

- [semantic-values.md](references/semantic-values.md): mutable world values, domain codecs and standalone schemas;
- [anvil-workflow.md](references/anvil-workflow.md): Region containers and compression;
- [datapack-workflow.md](references/datapack-workflow.md): parsing, resource stacks and partial selections.

Inspect matching official producers/readers and emitted values. Use minecraft-nbt when the underlying tag or binary
format changes, minecraft-world-io for paths and store lifecycle, and minecraft-world-projection for network adapters.
The owning AGENTS files define implementation constraints; these workflows supply inspection and test steps.

## Verify

Start with `:world-format:jvmTest`. Compression changes additionally need JS Node, WasmJS Node and host Native tests;
changed persisted bytes need `:world-io:jvmTest`. Changed computation-facing APIs need the user simulations in
protocol-world/world-io as well as direct domain tests.

Report official counterparts, intentional representation differences, unknown-field coverage and the relevant test
results. A successful official reload establishes the exercised path, not arbitrary mod/schema equivalence.
