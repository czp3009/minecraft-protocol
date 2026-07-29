# Audit and update orchestration

## Modes

- Default/no narrower instruction: update all protocol code to the Wiki's current stable target.
- Explicit audit/review/status request: read-only audit.
- Explicit focused implementation request: change only the requested area, but still use current checked-in evidence.
- Explicit pinned-version request: do not migrate beyond that version.

The explicit command forms are `$minecraft-protocol-modeling <release>` and
`$minecraft-protocol-modeling protocol:<id>`. Zero arguments means latest.

## Acquisition and freshness

In update mode, first run `refreshProtocolSpecification` in its own Gradle invocation. It deterministically records:

- Wiki version/protocol/revision/hash;
- normal and legacy packet inventory;
- Mojang latest stable release;
- analysis-only Java requirement;
- page-internal version warnings.

Then run `prepareProtocolUpdate`. It deterministically:

- downloads and SHA-1 verifies the exact official server bundle;
- uses a locally installed analysis JDK;
- runs vanilla data generators;
- compares the Wiki list to vanilla `packets.json`;
- verifies and unpacks the nested implementation JAR;
- decompiles it and indexes every packet class/source hash;
- prepares exact-version auxiliary repositories where available;
- emits the local work queue.

The official-client E2E separately downloads and verifies its complete client runtime into `build/`. It does not require
a launcher installation.

Classify freshness as:

- `current`: snapshot, Kotlin constants, inventories, official reports, source index, auxiliary index, and audit ledgers
  target one version;
- `stale metadata`: these artifacts disagree;
- `update required`: update mode found a newer Wiki target;
- `pinned`: explicit user request retains an older target;
- `unresolved`: exact official evidence cannot be acquired or Wiki and vanilla differ beyond a documented normalization.

Never migrate based only on a class count or page introduction.

## Ordered four-source decision process

For each packet/type:

1. Inspect the matching official packet/codec source and structured reports.
2. Read the revision-pinned Wiki table and Notes for descriptive context and details that official code does not expose
   directly.
3. Resolve every discrepancy in favor of observed official behavior.
4. Consult exact-version MCProtocolLib only when it adds useful evidence.
5. Consult exact-version Minestom last.
6. If an auxiliary project has no exact target revision, record unavailable or version drift; do not blend its packet
   IDs into the target.
7. Record the final conclusion, field/condition/wire verdicts, evidence hashes, and executable test evidence in the
   conformance ledger.
8. Run `refreshOfficialProtocolConformance` before editing verdicts. Never restore a pass merely because a class name
   still matches: inspect every entry invalidated by the per-packet and aggregate implementation hashes.

The final representation may be more idiomatic than vanilla, but its encoded bytes and accepted payloads must align with
the official JAR.

## Completeness layers

Audit all of:

1. **Inventory:** one class per expected state/direction/ID and no extras; legacy unframed ping handled separately.
2. **Identity:** local `officialName` matches vanilla at that ID.
3. **Models:** fields, order, semantic types, nullability, limits, and all conditional branches. Apply the four-source
   nullability audit from
   `modeling-rules.md` to nullable and non-null properties alike; every source file's complete constructor-property
   signature must be reviewed and ledger-tracked. Unresolved fields must be nullable and
   `@UnknownNullability`.
4. **Shared types:** no unexplained opaque substitute for a structured type.
5. **Serialization:** every physical encoding and logical union is supported.
6. **Registry:** exact serializer/ID/state/direction mapping, no duplicates. Every finite type registry used as a
   discriminator also has an executable Kotlin ID/name manifest compared with vanilla `registries.json`.
7. **Model tests:** format-independent values, invariants, and sealed model contracts.
8. **Format tests:** primitive vectors, annotations, composite types, boundaries, limits, and malformed input.
9. **Packet tests:** packet-specific golden payloads, every logical branch, registry-wide round trips, and generated
   branch profiles.
10. **Transport tests:** test-only framing, partial reads, frame limits, compression threshold branches, and corrupt
    compressed data.
11. **Official codec differential:** every local packet key and each valid generated or explicit sample is completely
    consumed by the matching official codec. Require byte-identical re-encoding when it is preserved. When vanilla
    changes the representation, feed the first official output through the codec again and require a byte-identical
    stable normalization. If the official codec itself emits non-deterministic representations, require complete
    consumption on repeated passes and classify that behavior separately. Record all cases in the generated report; its
    fingerprint invalidates the checked-in conformance ledger when the set changes.
12. **Official server interoperability:** the matching server runs in offline mode with compression and reaches Play
    through both the low-level codec probe and the production Ktor client.
13. **Production server interoperability:** the production Ktor client and production Ktor server complete offline
    Login, Configuration data synchronization, Play entry, and a Play packet round trip in process.
14. **Official client interoperability:** the matching hash-verified official client connects directly to the production
    project server in offline mode, completes Configuration, processes Play Login, accepts initial chunks and entities,
    acknowledges teleportation and the chunk batch, emits ticks, and completes a bidirectional Play packet round trip.
15. **Official alignment:** every local class has current, machine-validated strong-conformance evidence for field
    order, conditions, wire encodings, and limits—not merely a matching ID/name.
16. **Auxiliary audit:** MCProtocolLib then Minestom results are recorded when usable.
17. **Build:** focused layer tasks, aggregate verification, then representative KMP targets.

Search for TODO/FIXME, placeholders, ignored tests, unchecked lengths, unconsumed bytes, duplicate IDs, and forbidden
serialization imports.

Reuse useful auxiliary-project testing patterns such as registered-packet round trips, partial-buffer checks, and
in-process socket handshakes, but do not treat an implementation's self-round-trip as wire authority. The exact vanilla
codec differential and official-server session remain the external truth gates. The build-local headless official-client
test is the portable final acceptance gate for the server-facing stack and is not a substitute for any lower test layer.
Direct desktop-client launch is an additional acceptance path.

## Work-queue order

1. source/version metadata and inventories;
2. primitive format correctness/safety;
3. shared leaf types;
4. logical unions;
5. packets in state/direction/ID order;
6. registries and payload helpers;
7. model/format/packet/transport tests and golden vectors;
8. vanilla-data, transport, session, auth, client, and server layer tests;
9. official codec differential and per-class audit;
10. official server interoperability;
11. official client to production server interoperability;
12. auxiliary comparison;
13. broader KMP verification.

After every coherent batch, compile, run focused tests, regenerate the work queue, update audit/state records, and
repair this skill if the workflow itself failed.

## Completion gates

Completion requires:

- `verifyProtocolReferenceSources` succeeds;
- local inventory/identity audit succeeds;
- no unexplained structured opaque payload remains;
- all logical serializer branches pass Minecraft-format tests;
- exact registry coverage with no duplicate IDs;
- executable finite-registry IDs and names match vanilla generated reports;
- every normal packet executes both encode and decode through its runtime registry entry;
- an automated registry-wide serializer/round-trip suite covers the full inventory, with packet-specific golden vectors,
  conditional variants, and generated branch profiles;
- model, format, packet-payload, vanilla-data, transport, session, auth, client, and server layer tasks pass
  independently;
- primitive/composite malformed-input and allocation tests pass;
- every emitted packet sample passes the matching official codec oracle;
- every non-identical official re-encoding converges to a stable official normalization or is classified as official
  non-determinism, and remains locked by the conformance evidence fingerprint;
- the matching official server completes offline Status, Login, Configuration, compression negotiation, and entry into
  Play with both codec probes and the production client;
- committed vanilla Configuration data equals a fresh official capture;
- the production client and production server complete their in-process end-to-end path;
- the matching build-local, hash-verified official client reaches Play against the production server without a display,
  accepts initial chunks/entities, acknowledges teleportation and chunk batching, emits ticks, and completes a
  Play-stage packet round trip;
- vanilla subprocesses leave no logs, properties, or world data at the repository root;
- every packet has a current official-JAR conformance record tied to exact source/class hashes and executable test
  evidence;
- the ledger's aggregate `protocol-model` + `protocol-serialization`
  `commonMain` fingerprint is current, so shared-codec changes cannot bypass per-packet invalidation;
- the all-property nullability coverage hash is current, every source batch is explicitly confirmed, and every
  nullable/unresolved property has four-source evidence;
- the conformance-ledger validator rejects missing, duplicate, stale, or non-passing field/condition/wire verdicts;
- all Wiki/official differences have rationale;
- auxiliary comparison has no unexplained exact-version difference;
- representative common/JS/native compilations pass;
- the refreshed work queue contains no unfinished target item.

## Workflow maintenance

If any deterministic step is manual, flaky, stale, or falsely passes/fails:

1. stop;
2. improve its Gradle task/script and the relevant skill rule;
3. validate the skill;
4. forward-test the task;
5. resume the same work queue.
