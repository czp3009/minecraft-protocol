# distribution-metadata

This module owns modern Mojang distribution metadata HTTP APIs from Version Manifest V2 through version, asset-index,
and Java runtime documents.

## Local contract

- Support the current version-document schema used by the repository-selected official release and contemporary
  releases/snapshots. Keep V2 `sha1` and `complianceLevel` required; do not add legacy schemas or compatibility aliases.
- Keep `MinecraftDistributionMetadataApi` as the annotated Ktorfit interface and implement
  `MinecraftDistributionMetadataApiClient` by delegation to its generated implementation. Typed wire models use the
  caller's Ktor `ContentNegotiation` configuration; consumers do not apply Ktorfit or KSP.
- Keep the Ktorfit construction helper and fixed Piston Meta base URL private in the client source file; the API source
  file contains only the annotated interface contract. Expose the base URL as an optional client constructor override
  whose default is that private constant; do not rewrite absolute reference URLs against it.
- A public API operation performs exactly one GET. Fixed roots remain fixed, while reference-driven operations accept
  and follow the caller-supplied absolute URL without host, hash, size, or cross-document identity validation.
- Preserve server-produced values as wire data. Transport, response validation, plugin, serialization, and cancellation
  failures propagate without conversion into module-specific exceptions.
- Preserve dynamic catalog platform/component names and runtime file discriminators. An asset object's download URL is a
  deterministic descriptor derived from its hash, not a network operation or an integrity check.
- Prefer generated annotation-driven serializers. Keep handwritten serialization only for untagged JSON unions, such as
  the string-or-object argument shape, that annotations cannot represent.
- Keep binary/object downloads, retry, caching, progress, installation, and filesystem behavior in consumers or a
  separately owned download layer.

## Verification

Run `:distribution-metadata:jvmTest`. Also compile or test JS, WasmJS, and an applicable Native or Apple target when
changing Ktorfit transport, dynamic URL handling, caller content-negotiation requirements, or serializers.
