# distribution-metadata

This module owns modern Mojang distribution metadata HTTP APIs from Version Manifest V2 through version, asset-index,
and Java runtime documents, plus streaming download requests by URL or asset hash.

## Local contract

- Support the current version-document schema used by the repository-selected official release and contemporary
  releases/snapshots. Keep V2 `sha1` and `complianceLevel` required; do not add legacy schemas or compatibility aliases.
- Keep `MinecraftDistributionMetadataApi` as the annotated Ktorfit interface and implement
  `MinecraftDistributionMetadataApiClient` by delegation to its generated implementation. Typed wire models use the
  caller's Ktor `ContentNegotiation` configuration; downloads return `HttpStatement` without requiring JSON plugins.
  Consumers do not apply Ktorfit or KSP.
- Keep the Ktorfit construction helper and fixed Piston Meta base URL private in the client source file; the API source
  file contains only the annotated interface contract. Expose the base URL as an optional client constructor override
  whose default is that private constant; do not rewrite absolute reference URLs against it.
- A metadata operation performs one GET. `download(url)` only prepares an `HttpStatement`; each execution sends one GET
  and the `execute` block owns the response lifetime. Fixed roots remain fixed, while URL-driven operations follow the
  caller-supplied absolute URL without host, hash, size, or cross-document identity validation.
- Preserve server-produced values as wire data. Transport, response validation, plugin, serialization, and cancellation
  failures propagate without conversion into module-specific exceptions.
- Preserve dynamic catalog platform/component names and runtime file discriminators. Keep asset URL construction based
  on `minecraftAssetPath(hash)` so consumers can reuse the same relative path beneath their own storage roots. Path
  derivation only lowercases and formats the hash; it does not validate it or choose an installation directory.
- Keep `toDownload()` projections together in `MinecraftDownload.kt`. Library and logging projections preserve `sha1`,
  `size`, and `url` exactly; asset objects derive the URL without inventing descriptor data. Keep HTTP download
  extensions
  separate from the annotated interface and delegate them to `download(url)`.
- Reuse `MinecraftDownload` for URL/SHA-1/size descriptors without additional fields or constraints, including Java
  runtime
  manifest references. Keep the additional wire fields of library, logging, and asset-index references on their own
  models.
- Prefer generated annotation-driven serializers. For untagged argument unions, use `JsonContentPolymorphicSerializer`
  with internal shape selectors and leave concrete value encoding/decoding to generated serializers.
- Keep integrity checks, retry, caching, progress reporting, installation, and filesystem behavior in consumers. The
  download API exposes the response stream without buffering it or interpreting its bytes.

## Verification

Run `:distribution-metadata:jvmTest`. Also compile or test JS, WasmJS, and an applicable Native or Apple target when
changing Ktorfit transport, dynamic URL handling, caller content-negotiation requirements, or serializers.
