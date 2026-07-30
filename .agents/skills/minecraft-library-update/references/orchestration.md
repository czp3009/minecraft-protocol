# Complete library orchestration

## Dependency order

Use this order for cross-cutting changes:

1. buildSrc target and official artifacts;
2. shared protocol values, NBT algebra, and raw compression;
3. binary NBT;
4. packet serialization and vanilla Configuration data;
5. transport, sessions, authentication, client, and server;
6. region compression wrappers and containers;
7. filesystem and world paths;
8. official server and client interoperability;
9. standard multiplatform tests.

Run each sub-workflow's focused tests immediately after its layer changes.

## Aggregate gates

The complete aggregate is:

```powershell
.\gradlew.bat test
```

It composes standard KMP tests, including the matching official server, official codecs, the matching official client
launched through the pinned headless adapter, and official world reload. Proprietary artifacts are downloaded, verified,
cached inside `build/`, and never require account tokens or an installed launcher. GUI testing is excluded.

## Reporting

Report outcomes by layer and distinguish:

- headless-CI deterministic gates;
- official server differential/interoperability gates;
- build-local official client environment gates;
- supported stream and filesystem target families;
- explicit custom extension points;
- unresolved evidence or nullability.

Do not report a task as passed from historical output. Run it in the current invocation after all relevant edits.
