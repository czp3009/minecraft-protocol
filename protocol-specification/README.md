# Generated protocol specification

This directory contains deterministic evidence extracted from the official Minecraft 26.2 server selected in
`buildSrc`.

- `target.json`: official version, protocol, Java requirement, and artifact digests.
- `packets.json`, `registries.json`, and `blocks.json`: canonical official data-generator reports.
- `configuration.json`: payload facts captured through both official Known Packs negotiation branches.
- `server-properties.json`: the official default property inventory, with generated secrets normalized.

Regenerate the directory with
`./gradlew refreshProtocolSpecification`. Runtime and compilation code never read this checked-in directory.
