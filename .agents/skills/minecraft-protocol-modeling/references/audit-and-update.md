# Protocol audit and update checklist

Use this reference for a release-wide update or an exhaustive protocol audit. For a narrow change, select only the
affected items and their downstream verification.

## Work queue

1. selected official artifact and generated target/report artifacts;
2. primitive physical encodings, limits, and malformed input;
3. shared values, logical variants, and constructor invariants;
4. packets in connection-state, direction, and ID order;
5. runtime packet and data-component dispatch;
6. static vanilla data and both Configuration Known Packs branches;
7. framing, compression, encryption, session transitions, and authentication;
8. client and server orchestration through Play and reconfiguration;
9. official codec, server, and headless-client interoperability;
10. affected standard KMP platform tests.

## Completion evidence

- Every official report packet has one annotated local model and one runtime registry entry, except the explicitly
  modeled legacy unframed server-list ping.
- Packet fields match official order, conditions, discriminators, primitive encodings, collection shapes, optional
  shapes, and limits; representative branches encode and decode completely.
- Malformed, truncated, oversized, and allocation-amplifying inputs fail at their owning boundary.
- Generated static and Configuration data match the selected official server, including complete and Known-Pack-omitted
  branches.
- Status, Login, Configuration, compression, Play, and implemented reconfiguration transitions have deterministic tests.
- The production client reaches Play against the official server, and the matching headless official client accepts the
  production server's initial world and required acknowledgements.
- OGG, PNG, and JSON are the complete fixed HeadlessMC placeholder formats. `DownloadHeadlessMcDummyFilesTask` downloads
  and verifies the upstream OGG and PNG files and creates the JSON `{}` replacement;
  `DownloadOfficialMinecraftAssetsTask`
  substitutes those formats and retains verified official objects for every other format. Before changing
  `HeadlessMcTarget.HEADLESS_MC_VERSION`, inspect the matching upstream `DummyAssets` implementation and update the
  placeholder set, source paths, expected sizes, digests, or generated JSON bytes when its behavior differs, then rerun
  official headless-client interoperability.
- Official codec fixtures pass through the real official runtime rather than a copied implementation.
- Generated source is absent from Git source directories and present in the owning publication output.
- Runtime modules contain no generator entry point, process launcher, or fixture implementation.
- Every affected focused JVM suite passes before the applicable platform aggregate.

When storage modules are affected, include the world-storage workflow and its official generate/rewrite/reload gate.
