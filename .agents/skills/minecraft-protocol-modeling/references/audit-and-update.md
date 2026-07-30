# Audit and update procedure

## Work-queue order

1. selected official artifact and generated evidence;
2. primitive format correctness and safety;
3. shared leaf types and logical unions;
4. packets in state/direction/ID order;
5. registry/static/Configuration data;
6. transport, session, auth, client, and server flows;
7. official codec and server/client interoperability;
8. broader KMP verification.

For a release change, refresh specification first and review its diff. The official packets report drives registry
coverage; local packet annotations drive Kotlin class references. The one intentional non-report registry entry is the
legacy server-list ping and remains explicitly modeled/tested.

## Completeness checks

- every official report packet has one local annotated model and runtime registry entry;
- every normal packet can encode/decode and has representative branch samples;
- generated static and Configuration data match the selected official server;
- official codec fixtures decode completely and re-encode acceptably through the JAR's real codecs;
- malformed/truncated/oversized input is rejected before unbounded work;
- Status, Login, Configuration, compression, Play, and relevant reconfiguration transitions are tested;
- the production client reaches Play against the official server;
- the matching official headless client accepts the production server's initial world;
- official world generation/rewrite/reload passes when storage is affected;
- generated source is absent from Git source directories but present in published source JARs;
- no Gradle task reads skills or `temp/`, and no vanilla subprocess leaves files outside `build/`;
- affected JVM suites pass before the full root `test`.

Audit-only requests report concrete gaps and evidence without writing. Update requests continue through implementation
and verification. Do not declare completion from counts, self-round-trips, stale reports, or one end-to-end result
alone.
