# Protocol specification

This directory keeps the reviewable official evidence used to audit the library's protocol model and generated data.

`generated/` is replaced in full by
`./gradlew refreshProtocolSpecification`. Its files are deterministic:

- `target.json` records the selected release, protocol, Java requirement, and official artifact digests.
- `packets.json`, `registries.json`, and `blocks.json` are canonical official data-generator reports.
- `configuration.json` describes payloads observed through both official Known Packs negotiation branches.
- `server-properties.json` records the official default property inventory, with generated secrets normalized.

This README is a hand-written overview for people. No build, generation, refresh, or test task reads, rewrites, or
validates it.
