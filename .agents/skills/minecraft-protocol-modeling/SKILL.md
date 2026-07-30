---
name: minecraft-protocol-modeling
description: One-command workflow to update, implement, serialize, integrate, test, and audit this repository's Kotlin Multiplatform Minecraft Java Edition network protocol stack against the matching official server JAR as primary authority, with the Minecraft Wiki and exact-version third-party implementations as secondary evidence, plus an official client. Use for protocol upgrades, packet/type/registry/vanilla-data work, MinecraftFormat, transport, session, auth, client/server APIs, network interoperability, or completeness/freshness audits.
---

# Minecraft Protocol Modeling

Bring the complete network protocol stack into class-by-class behavioral alignment with the matching official server
JAR. Use the Minecraft Wiki as the secondary descriptive source and finish with an official-client-to-project-server
interoperability run. The project server can project a finite initial set of chunks and entities, then leaves worlds,
ticking, persistence, and gameplay to the consuming application.

This skill is an optional agent playbook. It may call the project's normal Gradle tasks, but it is not itself a build
input. Never make Gradle, production code, or tests read this skill, its references, or skill-generated scratch.

## Scope

This skill owns the `protocol-*` modules and their shared use of `compression` plus packet NBT from `nbt`. The
`minecraft-world-storage` skill owns named NBT files, Anvil region containers, dimension paths, and save
interoperability. Use
`minecraft-library-update` when a release update must close both workflows.

## Command interface

Invoke this skill with one of:

```text
$minecraft-protocol-modeling
$minecraft-protocol-modeling <minecraft-release>
$minecraft-protocol-modeling protocol:<decimal-id>
```

Accept zero or one target argument. With no argument, select the current stable version declared by the Wiki. A
Minecraft release argument pins that release;
`protocol:<decimal-id>` pins the latest Wiki revision declaring that protocol. Reject extra or malformed arguments
instead of guessing.

Pass a supplied target through the deterministic refresh task:

```powershell
.\gradlew.bat refreshProtocolSpecification "-PprotocolTarget=<target>"
```

The target applies to the whole invocation. After refresh, derive the official JAR, auxiliary versions, model inventory,
and all evidence from the resulting snapshot; never mix it with latest-version artifacts.

## Default invocation contract

Treat an invocation of this skill with no narrower request as **update mode**:

1. discover the Wiki's current stable Minecraft/protocol version;
2. refresh and cross-check all machine-readable sources;
3. update every affected packet model, serializer, vanilla-data snapshot, transport, session, authentication,
   client/server flow, test, and audit record;
4. keep iterating until every completion gate passes.

Do not stop after reporting gaps. Use audit-only mode only when the user explicitly asks for a read-only audit, review,
explanation, or status report. Honor an explicitly requested pinned version instead of silently migrating it.

## Start every invocation

1. Read these files completely:
    - [references/modeling-rules.md](references/modeling-rules.md)
    - [references/audit-and-update.md](references/audit-and-update.md)
    - [references/gradle-workflow.md](references/gradle-workflow.md)
    - [references/implementation-state.md](references/implementation-state.md)
2. Discover current implementation state using the procedure in
   `implementation-state.md`; never reuse target facts from prior prose.
3. Preserve unrelated user changes.
4. In update mode, run these as **two separate Gradle invocations**. Include
   `"-PprotocolTarget=<target>"` on the first command when the skill command has a target argument. Keep the complete
   property argument quoted on Windows so dotted Minecraft versions are not split by batch argument parsing:

   ```powershell
   .\gradlew.bat refreshProtocolSpecification ["-PprotocolTarget=<target>"]
   .\gradlew.bat prepareProtocolUpdate
   ```

   The separate invocation is required so preparation configures itself from the newly written target snapshot.
5. Read:
    - `protocol-specification/wiki-protocol-snapshot.json`;
    - `protocol-specification/official-packet-audit.json`;
    - `protocol-specification/official-source-index.json`;
    - `protocol-specification/official-packet-classes.csv`;
    - `protocol-specification/official-conformance-ledger.json`;
    - `protocol-specification/nullability-audit.yaml`;
    - `build/reports/protocol-update/work-queue.json`.
6. Build a dependency-ordered implementation queue and execute it.

## Workspace ownership

Keep the Gradle/LLM boundary explicit and absolute:

- Gradle tasks own their normal generated outputs under the root project's
  `build/` directory. This includes downloaded JARs, vanilla reports, decompiled sources, auxiliary clones, generated
  work queues, and Gradle reports. Gradle tasks and the scripts they invoke must never read from or write to the
  repository-root `temp/` directory.
- Only the language model owns and may access invocation-only scratch material under the repository-root `temp/`
  directory. This includes ad hoc notes, manual review checklists, redirected orchestration logs, manually unpacked or
  decompiled copies used during comparison, and context notes written to survive conversation compaction.

Do not let Gradle inspect, consume, or produce anything in `temp/`. Do not put LLM scratch reports in `build/`, the
repository root, or source/module directories. Never modify `.gitignore` as part of this workflow.

The skill's `references/` directory contains stable workflow and modeling instructions only. Checked-in target facts and
evidence hashes belong under
`protocol-specification/`, where refresh tasks maintain and invalidate them.

## Deterministic code versus language-model judgment

Use Gradle tasks for every operation that can be made deterministic:

- fetching Wiki source and revision metadata;
- caching every linked protocol subpage at the selected packet-page revision timestamp;
- parsing version numbers and packet-list rows;
- downloading and hashing official artifacts;
- running the vanilla data generator;
- comparing Wiki IDs/names with `reports/packets.json`;
- unpacking, decompiling, hashing, and indexing official packet classes;
- acquiring exact-version auxiliary repositories;
- scanning Kotlin annotations, packet IDs, duplicate coverage, module boundaries, all-property nullability signatures,
  and audit ledgers;
- regenerating and checking the committed runtime packet registry from those packet annotations;
- executing Kotlin serializers to produce finite-registry manifests and comparing their IDs and names with vanilla
  data-generator output;
- capturing both Known Packs branches and the matching Configuration registries/tags from the official server, then
  checking the committed
  `protocol-vanilla-data` snapshot;
- launching the production Ktor client against the official server;
- preparing a complete hash-verified official client and pinned headless launcher inside `build/`, then launching the
  client against the production Ktor server without a display;
- checking that vanilla subprocess artifacts remain under Gradle build directories;
- compilation, tests, and final verification.

Changing protocol facts must be derived and written by deterministic tasks. This includes target identifiers,
inventories, source revisions, normalization cases, codec exceptions, counts, and hashes. Do not copy those values into
this skill or its Markdown references.

Use language-model judgment only for information that is not reliably machine-structured:

- Wiki prose, Notes, and conditional-presence rules;
- semantic Kotlin model design;
- interpreting official codec/control flow;
- deciding whether a Wiki/official/auxiliary difference is version drift, documentation staleness, or an implementation
  defect;
- writing the corresponding Kotlin and tests.

If a deterministic check is being performed manually, stop and add or improve a Gradle task before continuing.

Default deterministic tasks keep downloaded sources, official runtimes, generated worlds, and reports inside the
repository's `build/` tree. They do not read a launcher installation or another project checkout from the user's home
directory. Explicit path overrides remain allowed for diagnostics. Forward-test every new Gradle task with configuration
cache enabled, then exercise any documented offline verification path against its completed build-local cache.

## Source authority

Use this order:

1. the exact-version official server JAR and its structured reports;
2. revision-pinned Minecraft Wiki protocol pages;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

Start with official structured reports where they answer the question, then inspect the official JAR's decompiled
codec/packet code for control flow and semantics. Use the Wiki second for descriptions, names, Notes, and facts not
directly exposed by official code. Auxiliary projects are clarifying implementations and never override clear official
evidence.

The official-first review must perform a strong class-by-class conformance check. This is not satisfied by matching only
packet counts, IDs, or class names. For every packet, verify field order, logical presence conditions, discriminators,
wire primitives, collection/optional encodings, and relevant limits against the official codec. Record a Wiki/JAR
disagreement as stale or ambiguous Wiki evidence and align the resulting bytes with the official JAR.

Completion requires machine-validated evidence tied to the refreshed Wiki revision, official server hash, official
packet source/class hash, and executable test evidence for every packet model. `verifyProtocolUpdate` must fail on
missing, stale, duplicate, or non-passing evidence; a free-form
"reviewed" marker is not sufficient.

## Implement and verify

For each coherent dependency batch:

1. inspect the indexed official packet source and codec;
2. read the exact Wiki packet/type section and relevant Notes; use the revision-pinned files under
   `build/protocol-reference/wiki/<revision>/references/` for linked type pages;
3. consult MCProtocolLib, then Minestom, only if useful and version-compatible;
4. implement format-neutral models and logical serializers in
   `protocol-model`;
5. implement physical wire representation in `protocol-serialization`;
6. update official-derived Configuration data in `protocol-vanilla-data` when its fresh capture changes;
7. update transport, session, authentication, client, or server orchestration when the protocol change reaches those
   layers;
8. add tests at every affected layer: model invariants, MinecraftFormat primitives/composites, packet branches and
   golden bytes, registry-wide round trips, framing/compression/encryption, state transitions, authentication,
   production client/server sockets, official codec differentials, and external interoperability;
9. run `reportProtocolNullability`; audit every changed constructor property (including properties modeled non-null) in
   the official-first fallback order from `modeling-rules.md`, then update
   `protocol-specification/nullability-audit.yaml`;
10. run `checkProtocolNullability`, compile, and test the batch;
11. run `refreshOfficialProtocolConformance`; inspect every entry it invalidates, then update the official per-class
    conformance ledger with field/condition/wire/limit verdicts and executable test evidence;
12. run `generatePacketRegistry`, then `checkPacketRegistry`;
13. run `checkOfficialNetworkRegistries`,
    `checkOfficialProtocolConformance`,
    `checkOfficialCodecConformance`, `checkProtocolWorkspaceHygiene`, and
    `reportProtocolModelGaps`;
14. run `officialServerInteropTest` for any state transition, early-state packet, vanilla data, framing, compression, or
    shared codec change;
15. run `headlessOfficialClientToServerEndToEndTest` for every release update or server-path change; run
    `officialClientToServerEndToEndTest` as an additional acceptance test when a graphical environment is available.

Finish with:

```powershell
.\gradlew.bat verifyProtocolUpdate
```

Then run the broader KMP compilation commands in
[references/gradle-workflow.md](references/gradle-workflow.md).

"Complete" means every normally framed packet has a runtime registry entry and can execute both payload encoding and
decoding. Require the complete layered suite defined in `audit-and-update.md`; no final end-to-end result substitutes
for lower-level model, format, packet, malformed-input, or differential tests. JSON compatibility is not a completion
gate: these models target the Minecraft protocol, while kotlinx.serialization supplies the structural API and format
boundary. A structured protocol payload represented as unexplained raw bytes is incomplete. Final verification must
include the strong official-JAR codec and server-interoperability gates described above. It must also include
`checkProtocolNullability`: its all-property signature catches an unaudited missing `?`, while its evidence groups
require every nullable or unresolved property to have official JAR, Wiki, MCProtocolLib, and Minestom findings. The
conformance ledger additionally locks an aggregate fingerprint of every
`commonMain` Kotlin source in both modules. A shared model, annotation, or codec change therefore invalidates old packet
reviews even when no packet declaration file changed. The executable official codec oracle must cover every registered
packet key and all generated or explicit branch samples. The official server tests must use the matching downloaded JAR,
offline mode, compression negotiation, and the production client plus packet registry through entry into Play. The
official client test must use the matching client prepared under
`build/`, verify its Mojang metadata, client, library, asset-index, and asset-object hashes, connect it directly to the
production server in offline mode, accept initial chunks and entities, acknowledge teleportation and the chunk batch,
emit client ticks, and require a bidirectional Play packet round trip. The headless launcher is a pinned build tool with
a repository-pinned SHA-256; changing its version requires a forward test against the current Minecraft target. Stable
reports must identify exact official artifacts and reached protocol stages.

`verifyProtocolUpdate` is the headless CI aggregate gate and includes
`headlessOfficialClientToServerEndToEndTest`. The task deterministically acquires proprietary client artifacts from
Mojang and the pinned HeadlessMC adapter into the repository's `build/` tree, reuses fully verified caches, and never
consults a launcher installation. An explicitly supplied client directory is an optional diagnostic override. The
official client JAR and game logic remain the matching Mojang artifacts; HeadlessMC replaces graphics library calls so
no display server is required.
`officialClientToServerEndToEndTest` directly launches the desktop client as an additional acceptance gate when that
environment exists. Do not use account tokens for offline-mode testing and do not copy launcher credentials into
commands, logs, or reports.

## Self-correction

Treat project code/tasks as production code and these optional instructions as maintained development guidance:

1. when execution exposes a repeatable omission, false positive, stale assumption, or unstable manual step, stop the
   affected workflow;
2. patch the skill, direct reference, script, or Gradle task;
3. run the skill-creator `quick_validate.py`;
4. rerun the affected Gradle task as a forward test;
5. re-read changed instructions and resume the existing work queue;
6. record stable process changes in the appropriate skill reference and changing evidence in deterministic project
   specification/report state.

Never preserve a process fix only in conversation.
