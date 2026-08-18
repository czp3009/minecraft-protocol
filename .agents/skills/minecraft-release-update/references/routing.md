# Release update routing

Use this inventory to turn an official release change into a dependency-ordered handwritten work queue. It is not a
requirement to edit every listed module.

## Gradle-owned inputs and outputs

The root task `officialMinecraftAnalysis` produces target, packet/registry/block report, and Configuration analysis
artifacts from the official server for the selected release. These are evidence and generator inputs, not source files.
It does not prepare the official client JAR; `downloadMinecraftClientJar` is the declared producer for client-bytecode
inspection when a routed change requires it.

Current production source producers are:

| Producer                                                    | Output responsibility                                                                     |
|-------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `:protocol-model:generateMinecraftProtocolSource`           | `MinecraftProtocol` release and protocol constants                                        |
| `:protocol-model:kspCommonMainKotlinMetadata`               | packet definitions and data-component serializer dispatch derived from source annotations |
| `:protocol-vanilla-data:generateVanillaStaticDataSource`    | static registry and block-state payload source                                            |
| `:protocol-vanilla-data:generateVanillaConfigurationSource` | captured Configuration payload source                                                     |

Treat every output of these producers as read-only. Task implementations in `buildSrc`, source annotations consumed by
KSP, model declarations, loaders, codecs, and tests remain handwritten.

## Route observable deltas

| Evidence or failure                                                                                            | Primary workflow                   | Common downstream workflow or owner                                                         |
|----------------------------------------------------------------------------------------------------------------|------------------------------------|---------------------------------------------------------------------------------------------|
| packet added, removed, renamed, renumbered, or reshaped                                                        | `minecraft-protocol-model`         | `minecraft-protocol-serialization` when bytes cannot be expressed by existing wire metadata |
| primitive, discriminator, conditional field, registry-aware codec, limit, or NBT wire form changed             | `minecraft-protocol-serialization` | `minecraft-protocol-model` for logical declarations                                         |
| registry, block-state, Known Packs, feature-flag, tag, or Configuration capture changed                        | `minecraft-protocol-vanilla-data`  | model and serialization if captured packet schemas changed                                  |
| Login, Configuration, Play, transfer, or reconfiguration ordering changed                                      | `minecraft-protocol-flow`          | model and serialization for affected packets                                                |
| tag algebra, list rules, root forms, modified UTF, or binary NBT changed                                       | `minecraft-nbt`                    | protocol serialization and/or world format consumers                                        |
| region header, sector, compression identifier, external-chunk marker, or region-record NBT composition changed | `minecraft-world-format`           | `minecraft-world-io` for disk interoperability                                              |
| standalone-file schema/model/serializer, dimension path, backup, lock, sidecar, or region lifecycle changed    | `minecraft-world-io`               | NBT and world format as required                                                            |
| KSP packet-report validation or source-derived dispatch generation changed                                     | `minecraft-protocol-model`         | `protocol-symbol-processor`                                                                 |
| official packet or NBT oracle bridge no longer compiles or loads                                               | packet serialization or NBT skill  | `minecraft-test-fixture-host`                                                               |
| official server/client preparation fails before a protocol or world assertion                                  | affected flow or world-I/O skill   | `buildSrc` and fixture modules                                                              |

KSP packet diagnostics establish only state/direction/ID/name inventory. They do not establish packet fields,
nullability, logical variants, or physical encoding. Likewise, successful source generation establishes data provenance,
not the correctness of handwritten loaders and consumers.

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

Use the smallest applicable prefix:

1. official download and analysis task compatibility;
2. NBT logical and binary behavior shared by protocol or storage;
3. packet/shared models and physical serialization;
4. vanilla data capture, generation, and loading;
5. session/client/server lifecycle;
6. Anvil format and world filesystem behavior;
7. focused JVM, official-peer, and applicable platform tests.

Do not treat a passing end-to-end test as a substitute for focused lower-layer coverage. Repeat the inventory after each
coherent batch because one corrected schema may expose another release delta.
