# Discovering implementation state

Never copy the current version, protocol number, packet counts, registry counts, hashes, or last passing result into
skill prose.

At each invocation:

1. read `MinecraftTarget.MINECRAFT_VERSION` and confirm with `gradlew -q minecraftVersion`;
2. inspect the target, reports, and Configuration artifacts under
   `build/generated/official-minecraft/<version>/`, generating only the needed artifact when absent;
3. inspect current source annotations, KSP output wiring, and non-source generator task wiring before editing;
4. inspect existing standard tests and their latest reports under module `build/reports/tests`;
5. run affected JVM tests rather than trusting old reports;
6. run the applicable standard platform tests or `allTests` before declaring a release-wide update complete.

Production source and tests consume generated runtime APIs, not official-analysis files. Only root official-analysis
tasks inspect or execute the official server JAR; data-driven code generators consume the declared analysis artifacts.

When a newly discovered fact can be derived exactly, teach the deterministic generator to emit it. When it requires
semantic judgment, encode it in source/tests and cite the evidence in development discussion rather than adding a
mutable specification ledger.
