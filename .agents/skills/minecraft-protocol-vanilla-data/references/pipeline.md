# Vanilla protocol-data pipeline

## Official data producers

The root pipeline currently exposes:

- `analyzeOfficialMinecraftTarget`: Minecraft release, protocol number, and required Java major from the matching
  official server;
- `analyzeOfficialMinecraftReports`: vanilla `packets.json`, `registries.json`, and `blocks.json` reports;
- `analyzeOfficialMinecraftConfiguration`: an executable Configuration capture using report-derived packet IDs;
- `extractOfficialMinecraftDataPacks`: exact core and built-in data-pack files plus their manifest;
- `prepareOfficialMinecraftData`: the aggregate over all four producers.

Only root official-data tasks inspect the official server JAR. The server-template producer may execute it without
inspection, while data-driven source generators consume declared analysis or extracted artifacts rather than reopening
it.

## Source producers

- `:protocol-model:generateMinecraftProtocolSource` renders release constants from target analysis.
- `:protocol-datapack-vanilla:generateVanillaStaticDataSource` renders encoded static registry and block-state payload
  source from target, registry, and block reports.
- `:protocol-datapack-vanilla:generateVanillaConfigurationSource` renders encoded Configuration payload source from
  target
  and Configuration analysis.
- `:protocol-datapack-vanilla:generateVanillaDataPackSources` renders a small manifest and independently loaded encoded
  batch functions from extracted official data-pack content.

Compilation and tests are wired through the producers' artifact Providers. Do not add duplicate task ordering,
generated-source copying, or freshness comparison tasks.

## Handwritten consumers to audit

Audit `VanillaStaticData` decoding and registry APIs, `VanillaConfigurationSnapshot` packet decoding,
`VanillaProtocolData` branch selection, `ProtocolDataSet`, and dimension-layout extraction. In particular, verify any
handwritten Configuration packet ID or sequence assumption against the selected packet report; generated payload bytes
do not make such assumptions generated.

Keep these data categories distinct:

- static client-known registries and the global block-state palette come from official reports;
- Configuration feature flags, synchronized registries, tags, and Known Packs come from live official negotiation;
- accepting the exact offered Known Packs list may omit registry entry data, while another response requires the
  complete form.

Capture and retain both branches. Verify their registry ordering and identifiers agree even when entry payload presence
differs.

## Failure routing

- Missing or renamed report files: adapt the root analyzer only after confirming the official generator changed.
- Changed `packets.json` shape or KSP consumption: route source-derived validation and dispatch through the model
  workflow and `protocol-symbol-processor`; keep official-JAR inspection in the root analyzer.
- Changed Configuration order or required response: adapt the capture state machine using official server behavior and
  current packet IDs.
- Payload decode failure: first verify the packet model and physical codec, then the handwritten snapshot decoder.
- Public-data mismatch against an official server: determine whether analysis, generation, decoding, or branch selection
  owns the discrepancy before editing.

Do not repair a deterministic mismatch by embedding a corrected list or byte string in handwritten source.
