---
name: minecraft-release-update
description: "Coordinate an incremental or complete Minecraft release alignment, including target changes, protocol/world completeness audits, and version-related analysis, KSP, generated-data or official-peer failures. Use the focused domain skills for individual formats or behaviors."
---

# Minecraft release alignment

## Establish scope

Run `./gradlew -q minecraftVersion`. Change MinecraftTarget only for an explicitly requested release change, then use
`prepareOfficialMinecraftData`. Obtain client bytecode through `downloadMinecraftClientJar` when needed. Other external
selectors remain independent.

Read [routing.md](references/routing.md) and build a dependency-ordered queue from official evidence and the current
handwritten source. For an incremental request, cover the affected contracts and consumers. For a completeness audit,
account for every domain even when compilation still succeeds; record evidence of no change where applicable.

Load only relevant leaf workflows. Models/serializers, generated-data consumers and world schemas are handwritten
contracts even when their generators compile. KSP proves inventory/naming properties; oracle tests prove sampled bytes.
Neither substitutes for tracing producer and consumer semantics.

## Execute and verify

Fix lower layers before consumers. Standard tasks invoke their declared producers, KSP and fixtures; there is no
skill-specific update or acceptance pipeline. Keep the work queue and disposable coverage notes under temp.

Start with focused JVM suites, then official-peer and applicable platform tests. Follow the root workflow for wrapper
serialization and cache checks. Distinguish artifact/host failures from product failures before changing either.

Report release, audited scope, evidence for representation differences, tests and unresolved contracts, including
unknown nullability. Do not equate a passing end-to-end sample with release-wide completeness.
