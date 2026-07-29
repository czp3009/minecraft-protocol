# Gradle workflow

All commands below run from the repository root. On Windows use
`.\gradlew.bat`; on Unix-like systems use `./gradlew`.

## Update preparation

Run these in separate Gradle invocations:

```powershell
.\gradlew.bat refreshProtocolSpecification
.\gradlew.bat prepareProtocolUpdate
```

For an explicitly pinned skill invocation, pass its single target only to the refresh invocation:

```powershell
.\gradlew.bat refreshProtocolSpecification "-PprotocolTarget=<minecraft-release>"
.\gradlew.bat prepareProtocolUpdate
```

`protocol:<decimal-id>` is also accepted as a target. Omit the property to select the Wiki's current stable version.
Keep the whole `-P` argument quoted on Windows; otherwise a dotted version may be split while invoking
`gradlew.bat`.

The first refreshes the target and analysis-Java requirement. The second is a new Gradle invocation so toolchain
selection sees that refreshed requirement. It also runs `prepareWikiProtocolReferences`, which caches every linked
`Java Edition protocol/*` subpage under
`build/protocol-reference/wiki/<packet-revision>/references/` at or before the packet page's own revision timestamp.
Read its `index.json` before using those pages as evidence.

`prepareProtocolUpdate` composes:

- `downloadOfficialMinecraftServer`
- `generateOfficialMinecraftReports`
- `verifyProtocolReferenceSources`
- `unpackOfficialMinecraftServer`
- `decompileOfficialMinecraftServer`
- `indexOfficialMinecraftSources`
- `prepareOfficialMinecraftSources`
- `prepareAuxiliaryProtocolSources`
- `reportProtocolModelGaps`
- `reportProtocolNullability`
- `reportOfficialProtocolConformance`

The official client is prepared lazily by
`prepareOfficialMinecraftClient` when the client E2E runs. Its metadata, client JAR, libraries, native libraries, asset
index, and asset objects stay under `build/protocol-reference/mojang-client`. Every artifact is checked against Mojang
size and SHA-1 metadata. A complete cache is reusable without a launcher installation or a network request.

Gradle-generated caches stay under `build/protocol-reference`, including the exact Wiki wikitext revision used by the
snapshot; the generated work queue stays under `build/reports/protocol-update`. Checked-in, target-dependent snapshots
and evidence hashes stay under `protocol-specification`. The skill's
`references` directory contains stable instructions.

Every Java process that loads or launches vanilla classes uses a build-owned working directory. Vanilla logs, worlds,
generated registries, and other process-relative outputs must never appear at the repository root.

Only the language model may access `temp/`. Scratch analysis, manual checklists, redirected orchestration logs, manually
unpacked/decompiled comparison copies, and compaction-survival notes belong there. Gradle tasks and their helper scripts
must never read from or write to `temp/`. Never leave LLM scratch files in `build/` or invocation-only reports in the
repository root. The workflow must not edit `.gitignore`.

## Analysis JDK

The Mojang version metadata supplies the required Java major version. This JDK is only for running/decompiling official
artifacts and launching the client interoperability probe. It is independent of the project's Java/Kotlin/KMP targets.

Do not install a JDK automatically. If Gradle cannot find the required version, ask the user to install it. Gradle
normally auto-detects installed JDKs. A nonstandard installation can be exposed for one invocation with:

```powershell
.\gradlew.bat prepareProtocolUpdate "-Dorg.gradle.java.installations.paths=C:\path\to\jdk"
```

The data-generator executable may also be overridden with
`-PprotocolJavaExecutable=C:\path\to\java.exe`.

The official-client probe accepts
`-PminecraftClientJavaExecutable=C:\path\to\java.exe`. This is also an analysis/test process and does not change the
project toolchain.

## Iteration

Use focused tasks first:

```powershell
.\gradlew.bat :protocol-model:compileKotlinJvm
.\gradlew.bat :protocol-serialization:compileKotlinJvm
.\gradlew.bat :nbt:nbtLayerTest
.\gradlew.bat :protocol-model:jvmTest
.\gradlew.bat :protocol-serialization:jvmTest
.\gradlew.bat :protocol-model:modelContractLayerTest
.\gradlew.bat :protocol-serialization:minecraftFormatLayerTest
.\gradlew.bat :protocol-serialization:packetPayloadLayerTest
.\gradlew.bat :protocol-serialization:packetTransportLayerTest
.\gradlew.bat :protocol-vanilla-data:vanillaDataLayerTest
.\gradlew.bat :protocol-transport:transportLayerTest
.\gradlew.bat :protocol-session:sessionLayerTest
.\gradlew.bat :protocol-auth:authLayerTest
.\gradlew.bat :protocol-client:clientLayerTest
.\gradlew.bat :protocol-server:serverLayerTest
.\gradlew.bat protocolLayeredTest
.\gradlew.bat :protocol-serialization:updateVanillaProtocolData
.\gradlew.bat :protocol-serialization:checkVanillaProtocolData
.\gradlew.bat generatePacketRegistry
.\gradlew.bat checkPacketRegistry
.\gradlew.bat checkOfficialNetworkRegistries
.\gradlew.bat reportProtocolNullability
.\gradlew.bat checkProtocolNullability
.\gradlew.bat refreshOfficialProtocolConformance
.\gradlew.bat checkOfficialProtocolConformance
.\gradlew.bat checkOfficialCodecConformance
.\gradlew.bat officialServerInteropTest
.\gradlew.bat prepareOfficialMinecraftClient
.\gradlew.bat verifyPreparedOfficialMinecraftClient
.\gradlew.bat downloadHeadlessMinecraftLauncher
.\gradlew.bat headlessOfficialClientToServerEndToEndTest
.\gradlew.bat officialClientToServerEndToEndTest
.\gradlew.bat checkProtocolWorkspaceHygiene
.\gradlew.bat reportProtocolModelGaps
```

Do not ignore a nonzero strict audit. `reportProtocolModelGaps` is intentionally non-failing because it emits the
current implementation queue to
`build/reports/protocol-update/work-queue.json`.
`generatePacketRegistry` deterministically updates the committed
`GeneratedPacketRegistryEntries.kt`; `checkPacketRegistry` is the non-mutating verification counterpart used by
`verifyProtocolUpdate`.
`checkOfficialNetworkRegistries` executes representative Kotlin values to derive discriminator IDs and names, then
compares every emitted finite registry with the matching vanilla data-generator `registries.json`.

`updateVanillaProtocolData` captures both Known Packs branches. It verifies raw official packets byte-for-byte through
the Kotlin codec, then canonicalizes only order-insensitive NBT compounds and tag sets before writing committed data. It
must preserve registry and entry order because those positions define runtime IDs. Run `checkVanillaProtocolData` in a
fresh process after every generator change to prove that repeated official-server launches produce the same snapshot.

`refreshOfficialProtocolConformance` updates exact Wiki/JAR/Kotlin/test fingerprints in the committed ledger. It
deliberately converts reviews to
`pending` whenever the target, official source, packet source, aggregate
`commonMain` implementation, or test evidence changed. It never invents a passing semantic judgment. After reviewing all
invalidated entries, record their four passing verdicts and run `checkOfficialProtocolConformance`.

`checkOfficialCodecConformance` emits Kotlin packet samples from the refreshed registry and executes them through the
matching official packet codecs. It requires complete packet-key coverage and validates every emitted branch sample. It
derives normalization cases from the bytes: a changed official re-encoding is passed through the official codec again to
distinguish stable normalization from official non-deterministic representation. The generated report records those
cases and is part of the committed conformance ledger's evidence fingerprint.

`officialServerInteropTest` starts the matching downloaded server under
`build/`, configures offline mode and compression, then drives Status, Login, Configuration, and entry into Play through
both the low-level codec probe and the production Ktor client. It writes stable evidence reports under
`build/reports/protocol-update`.

`headlessOfficialClientToServerEndToEndTest` reads the target release from the refreshed snapshot, prepares that exact
official client under `build/`, and launches it through the pinned HeadlessMC LWJGL adapter against the production Ktor
server. Gradle downloads and cryptographically verifies the adapter. The task validates metadata, client, library,
asset-index, and asset-object size/SHA-1 values from Mojang metadata, uses isolated game/native directories under
`build/`, and uses an offline dummy identity without launcher credentials. Acceptance requires Login, Configuration,
Play, matching KeepAlive, initial chunks and entities, teleport and chunk-batch acknowledgements, client ticks, and a
live connection after synchronization. This task requires no display server or installed launcher and is part of the
portable protocol gate.

An existing complete client directory may be selected explicitly for diagnosis with:

```powershell
.\gradlew.bat headlessOfficialClientToServerEndToEndTest "-PminecraftClientDirectory=C:\path\to\.minecraft"
```

The default path never reads the system launcher directory.
`officialClientToServerEndToEndTest` exercises the same official-client scenario by launching the desktop client
directly. Run it as an additional acceptance test when a graphical environment is available.

`checkProtocolWorkspaceHygiene` runs after the vanilla codec and server processes and rejects process-relative logs,
properties, or worlds that escape Gradle's build directories.

## Final verification

```powershell
.\gradlew.bat verifyProtocolUpdate
```

The aggregate gate includes every local test layer, official codec differentials, fresh vanilla data, official-server
interoperability, and the headless matching-official-client acceptance test. Its downloaded inputs remain inside the
project build tree. A graphical host may additionally run the direct desktop launcher:

```powershell
.\gradlew.bat officialClientToServerEndToEndTest
```

Run the individual layer tasks while iterating so failures remain localized.

Then compile representative KMP targets supported by the current host:

```powershell
.\gradlew.bat compileCommonMainKotlinMetadata
.\gradlew.bat compileKotlinJs
.\gradlew.bat compileKotlinMingwX64
```

Add Linux/native/Apple compilation on suitable hosts or CI. A host limitation is not permission to weaken common-source
correctness.
