---
name: minecraft-world-io
description: "Implement or audit world-io filesystem behavior: Okio stores, dimension paths, region/entities/poi files and sidecars, world leases, coordinated writes, live reads, directory/ZIP data packs, recovery, or official world reload tests. Use minecraft-world-format for standalone-file model schemas and domain NBT codecs."
---

# Minecraft world I/O

Confirm the selected release, read [storage-workflow.md](references/storage-workflow.md), and inspect matching official
path construction, file stores, lock behavior and a generated world. The current world-io AGENTS defines the public
stream, recovery and coordination invariants.

Identify the failing boundary before editing: schema/tag behavior belongs below file policy, mutable coordination
belongs above physical stores, and a fixture startup failure may precede any world operation. Use the world-format or
NBT workflow when lower-layer evidence requires a change; use world-projection for caller computation across packets.

Run `:world-io:jvmTest` first. It includes FakeFileSystem tests and the official generate/rewrite/reload scenario via
hostFilesystemTest. Filesystem adapters and exception-type behavior also need non-JVM coverage on configured Node or
host Native targets; JVM aliases cannot prove the public Okio failure contract.

Report the affected file family, path/recovery or lifetime decision, official evidence and tests. Do not claim external
live reads are snapshots because one observed read succeeded.
