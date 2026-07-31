# Discovering implementation state

Never copy the current version, protocol number, packet counts, registry counts, hashes, or last passing result into
skill prose.

At each invocation:

1. read `MinecraftTarget.version` and confirm with `gradlew -q minecraftVersion`;
2. inspect `protocol-specification/generated/target.json`, `packets.json`, `registries.json`, `blocks.json`,
   `configuration.json`, and `server-properties.json`;
3. inspect current source annotations, KSP output wiring, and non-source generator task wiring before editing;
4. inspect existing standard tests and their latest reports under module `build/reports/tests`;
5. run affected JVM tests rather than trusting old reports;
6. run the applicable standard platform tests or `allTests` before declaring a release-wide update complete.

Production source, tests, and normal Gradle tasks must not read checked-in specification evidence.
`refreshProtocolSpecification` is the sole writer and replaces `protocol-specification/generated` with a standard Gradle
`Sync`; its hand-written README is outside that Sync.

When a newly discovered fact can be derived exactly, teach the deterministic generator to emit it. When it requires
semantic judgment, encode it in source/tests and cite the evidence in development discussion rather than adding a
mutable specification ledger.
