# Release update routing

Use this inventory to turn an official release change into a dependency-ordered handwritten work queue. It is not a
requirement to edit every listed module.

## Locate generated evidence

Use [the vanilla pipeline inventory](../../minecraft-vanilla-data/references/pipeline.md) for current producer tasks and
artifacts. Models, annotations, loaders, analyzers and generator implementations remain handwritten; their build outputs
do not. Read the owning buildSrc or KSP guide when a producer contract changes.

## Route observable deltas

| Evidence or failure                                                                                            | Primary workflow                   | Common downstream workflow or owner                                                         |
|----------------------------------------------------------------------------------------------------------------|------------------------------------|---------------------------------------------------------------------------------------------|
| packet added, removed, renamed, renumbered, or reshaped                                                        | `minecraft-protocol-model`         | `minecraft-protocol-serialization` when bytes cannot be expressed by existing wire metadata |
| primitive, discriminator, conditional field, registry-aware codec, limit, or NBT wire form changed             | `minecraft-protocol-serialization` | `minecraft-protocol-model` for logical declarations                                         |
| registry, block-state, Known Packs, feature-flag, tag, or Configuration capture changed                        | `minecraft-vanilla-data`           | model and serialization if captured packet schemas changed                                  |
| Login, Configuration, Play, transfer, or reconfiguration ordering changed                                      | `minecraft-protocol-flow`          | model and serialization for affected packets                                                |
| tag algebra, list rules, root forms, modified UTF, or binary NBT changed                                       | `minecraft-nbt`                    | protocol serialization and/or world format consumers                                        |
| region header, sector, compression identifier, external-chunk marker, or region-record NBT composition changed | `minecraft-world-format`           | `minecraft-world-io` for disk interoperability                                              |
| standalone-file schema or semantic Chunk/property representation changed                                       | `minecraft-world-format`           | NBT, world projection and filesystem consumers                                              |
| dimension path, backup, lock, sidecar or region lifecycle changed                                              | `minecraft-world-io`               | world format for changed bytes                                                              |
| world/packet projection, missing fields or pairing changed                                                     | `minecraft-world-projection`       | endpoint adapters and physical serialization                                                |
| KSP packet-report validation or source-derived dispatch generation changed                                     | `minecraft-protocol-model`         | `protocol-symbol-processor`                                                                 |
| official packet or NBT oracle bridge no longer compiles or loads                                               | packet serialization or NBT skill  | `minecraft-test-fixture-host`                                                               |
| official server/client preparation fails before a protocol or world assertion                                  | affected flow or world-I/O skill   | `buildSrc` and fixture modules                                                              |

KSP packet diagnostics establish state/direction/ID coverage and official class/ordered field names, with explicit
exceptions. They do not prove nested types, nullability, logical variants or physical encoding. Successful source
generation establishes provenance, not correctness of handwritten loaders and consumers.

## Route cross-cutting handwritten infrastructure

- `protocol-symbol-processor/src` is handwritten source-derived generation infrastructure. Route annotation contracts,
  report validation, and generated handoff shape through the model workflow; never edit its generated outputs.
- `protocol-auth` and `protocol-transport` remain ordinary handwritten runtime modules. Route them through the flow
  workflow only when selected-release evidence changes authentication invocation or physical transport behavior.
  `account-auth` describes external account-service HTTP APIs rather than selected-release packet behavior and is not a
  release-update domain.
- Official analyzers, captures, and non-source generators in `buildSrc` are handwritten parts of the existing Gradle
  pipeline and route through vanilla data. Fixture preparation in `buildSrc`, `minecraft-test-support`, and
  `minecraft-test-fixture-host` is test evidence infrastructure; change it only after distinguishing a preparation,
  bridge, or host compatibility failure from a product-code failure.
- Minecraft, HeadlessMC, Fabric Loader, and HMC-Specifics selectors are independent. Do not bump or derive a
  non-Minecraft selector merely because the Minecraft target changed.

All of these paths are available to a human without `.agents`. Do not add a skill-only launcher, report, comparison
task, dependency edge, or acceptance gate.

## Distinguish incremental and complete alignment

For an incremental request, investigate the reported feature and its downstream consumers. For a complete release
alignment, absence of a compiler, generator, or test failure is not evidence that a handwritten contract stayed
unchanged. Work through the current handwritten inventory:

1. reconcile every official packet-report entry with local `@PacketInfo`, then inspect the corresponding official
   declaration, codec, producer, and consumer for field shape and wire semantics;
2. reconcile every handwritten discriminator table and sealed family with its official registry or dispatch codec;
3. inspect both Configuration Known Packs branches, generated-data loaders, and handwritten registry or dimension
   assumptions;
4. trace every implemented Status, Login, Configuration, Play-entry, transfer, and reconfiguration branch against both
   peers;
5. inspect NBT, physical transport, Anvil, and world-file entry points wherever selected-release evidence can affect
   their formats, limits, activation, or paths; reconcile every provided standalone-file model and serializer with the
   current official schema even when compilation still succeeds;
6. distinguish product failures from KSP, official-oracle, artifact-preparation, and Fixture Host compatibility failures
   before editing;
7. run focused lower-layer tests before official-peer and applicable platform tests.

Keep this queue in the agent's working context or disposable agent notes. Do not commit a coverage ledger or add a
Gradle task that exists only to drive the skill.

## Dependency order

Order the affected tasks by their actual dependencies: official evidence and producer compatibility first, then logical
NBT/world/packet models, physical formats, registry data and world projection, and finally endpoint/filesystem
consumers.
Run focused tests before their integration scenarios. Revisit the queue when a corrected schema exposes another delta.
